package dev.vidultra.proto;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.util.Log;
import android.util.Rational;

/**
 * DNG-style camera colour: estimate the white point's colour temperature from the
 * as-shot neutral, interpolate ForwardMatrix1/2 by it, and build one matrix that takes
 * white-balanced camera RGB to ARRI Wide Gamut 3 (D65).
 */
final class ColorMath {
    private static final String TAG = "VIDULTRA";

    // ARRI Wide Gamut 3 -> XYZ (D65)
    private static final float[] AWG3_TO_XYZ = {
            0.638008f, 0.214704f, 0.097744f,
            0.291954f, 0.823841f, -0.115795f,
            0.002798f, -0.067034f, 1.153294f};
    // Bradford chromatic adaptation D50 -> D65
    private static final float[] D50_TO_D65 = {
            0.9555766f, -0.0230393f, 0.0631636f,
            -0.0282895f, 1.0099416f, 0.0210077f,
            0.0122982f, -0.0204830f, 1.3299098f};
    // linear sRGB -> XYZ (D65), fallback path when the camera has no forward matrix
    private static final float[] SRGB_TO_XYZ = {
            0.4124564f, 0.3575761f, 0.1804375f,
            0.2126729f, 0.7151522f, 0.0721750f,
            0.0193339f, 0.1191920f, 0.9503041f};

    // Bradford cone response, for adapting an arbitrary scene white to D65
    private static final float[] BRADFORD = {
            0.8951f, 0.2664f, -0.1614f,
            -0.7502f, 1.7135f, 0.0367f,
            0.0389f, -0.0685f, 1.0296f};
    private static final float[] D65_XYZ = {0.95047f, 1f, 1.08883f};

    private final float[] cm1, cm2, fm1, fm2, cc1, cc2;
    // Some vendors (seen on OnePlus) ship the generic sRGB->XYZ(D50) matrix as both forward
    // matrices. It carries no sensor calibration and leaves colours desaturated, so it is
    // ignored in favour of the DNG ColorMatrix path.
    private final boolean fmIsPlaceholder;
    private boolean cmIsPlaceholder;
    private final float t1, t2;
    private final float[] xyzToAwg3 = inverse(AWG3_TO_XYZ);

    ColorMath(CameraCharacteristics ch) {
        cm1 = toArray(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        cm2 = toArray(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        fm1 = toArray(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        fm2 = toArray(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        float[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        float[] c1 = toArray(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        float[] c2 = toArray(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        cc1 = c1 == null ? id : c1;
        cc2 = c2 == null ? cc1 : c2;
        Log.i(TAG, "ColorMath: CC1=" + java.util.Arrays.toString(cc1) + " CC2=" + java.util.Arrays.toString(cc2));
        fmIsPlaceholder = fm1 != null && isPlaceholder(fm1) && (fm2 == null || isPlaceholder(fm2));
        // an uncalibrated lens (seen on OnePlus ultra-wides) ships identity / sRGB colour
        // matrices: the ISP's per-frame matrix is then the better source
        cmIsPlaceholder = cm1 != null && cmPlaceholder(cm1) && (cm2 == null || cmPlaceholder(cm2));
        if (cmIsPlaceholder) Log.i(TAG, "ColorMath: colour matrix is a placeholder -> using the ISP's per-frame matrix");
        Log.i(TAG, "ColorMath: forward matrix " + (fm1 == null ? "absent"
                : fmIsPlaceholder ? "is a generic sRGB placeholder -> using ColorMatrix path"
                : "looks calibrated -> using ForwardMatrix path"));
        Integer i1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Byte i2 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        t1 = illuminantTemp(i1 == null ? CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A : i1);
        t2 = illuminantTemp(i2 == null ? CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65 : i2);
        Log.i(TAG, "ColorMath: illum1=" + i1 + " (" + t1 + "K) illum2=" + i2 + " (" + t2
                + "K) hasFM=" + (fm1 != null) + " hasCM=" + (cm1 != null));
        // A real phone sensor's colour filters overlap, so its camera -> sRGB matrix is always
        // strong (diagonal ~1.5-2, negative cross terms). Template "calibration" (seen on OnePlus
        // ultra-wide and front cameras) gives a near-identity matrix and grey colours: then
        // the ISP's per-frame matrix is used instead.
        if (cm1 != null && !cmIsPlaceholder) {
            float[] toSrgb = srgbMatrixAtD65();
            float diag = Math.max(toSrgb[0], Math.max(toSrgb[4], toSrgb[8])), off = 0;
            for (int i = 0; i < 9; i++) if (i % 4 != 0) off = Math.max(off, Math.abs(toSrgb[i]));
            if (diag < 1.3f && off < 0.25f) {
                cmIsPlaceholder = true;
                Log.i(TAG, "ColorMath: colour matrix too weak to be a real calibration (cam->sRGB "
                        + java.util.Arrays.toString(toSrgb) + ") -> using the ISP's per-frame matrix");
            }
        }
    }

    /** Row-major AWG3 linear -> Rec.709 (sRGB primaries) linear, for the preview check view. */
    static float[] awg3ToRec709() {
        return mul(inverse(SRGB_TO_XYZ), AWG3_TO_XYZ);
    }

    /** Scene illuminant colour temperature from the last matrix computation (K). */
    volatile float lastCct = 6500f;

    /**
     * Partial chromatic adaptation, like a phone camera's "keep the ambience": the picture is
     * rendered as if lit by a light part-way (in mired) between D65 and the real illuminant, so
     * warm rooms stay a little warm. Returns Rec.709-linear channel gains, luminance 1.
     *
     * @param keepWarm share of a warm (below 6500 K) illuminant's shift that is kept
     * @param keepCool share kept for a cool illuminant
     */
    static float[] ambienceTint(float cct, float keepWarm, float keepCool) {
        double d65 = 1e6 / 6504, m = 1e6 / cct;
        double keep = m > d65 ? keepWarm : keepCool;
        double render = 1e6 / (d65 + keep * (m - d65));
        double[] a = planckRgb709(render), r = planckRgb709(6504);
        double[] g = {a[0] / r[0], a[1] / r[1], a[2] / r[2]};
        double y = 0.2126 * g[0] + 0.7152 * g[1] + 0.0722 * g[2];
        return new float[]{(float) (g[0] / y), (float) (g[1] / y), (float) (g[2] / y)};
    }

    /** Rec.709-linear RGB of a Planckian light (Kim et al. cubic fit of the locus), Y = 1. */
    private static double[] planckRgb709(double t) {
        t = Math.max(1700, Math.min(20000, t));
        double x = t <= 4000 ? -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
                : -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        double y = t <= 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                : t <= 4000 ? -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                : 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        double X = x / y, Z = (1 - x - y) / y;
        return new double[]{3.2404542 * X - 1.5371385 - 0.4985314 * Z,
                -0.9692660 * X + 1.8760108 + 0.0415560 * Z,
                0.0556434 * X - 0.2040259 + 1.0572252 * Z};
    }

    boolean hasColorMatrix() {
        return cm1 != null && !cmIsPlaceholder;
    }

    /** camera (white-balanced) -> linear sRGB from the colour matrix, for a D65 scene. */
    private float[] srgbMatrixAtD65() {
        float w = Math.max(0f, Math.min(1f, (1f / 6504f - 1f / t2) / (1f / t1 - 1f / t2)));
        float[] cm = cm2 == null ? cm1 : lerp(cm1, cm2, w);
        float[] x2c = mul(lerp(cc1, cc2, w), cm);
        float[] neutral = mul(x2c, D65_XYZ);
        float g = neutral[1];
        for (int i = 0; i < 3; i++) neutral[i] /= g;
        long keep = lastLog;
        lastLog = Long.MAX_VALUE / 2;   // no log line from this probe
        float[] m = cameraToAwg3ViaColorMatrix(neutral, 1f);
        lastLog = keep;
        return mul(inverse(SRGB_TO_XYZ), mul(AWG3_TO_XYZ, m));
    }

    /** XYZ -> camera that is (a scaled) identity or plain sRGB: no sensor calibration. */
    private static boolean cmPlaceholder(float[] cm) {
        for (int c = 0; c < 2; c++) {
            float[] ref = c == 0 ? new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1} : inverse(SRGB_TO_XYZ);
            float sc = (cm[0] + cm[4] + cm[8]) / (ref[0] + ref[4] + ref[8]);
            if (Math.abs(sc) < 1e-6) continue;
            float err = 0;
            for (int i = 0; i < 9; i++) err = Math.max(err, Math.abs(cm[i] / sc - ref[i]));
            if (err < 0.05f) return true;
        }
        return false;
    }

    /** True when FM (D50) relative to sRGB is ~identity: i.e. it does no colour correction. */
    private static boolean isPlaceholder(float[] fm) {
        float[] a = mul(inverse(SRGB_TO_XYZ), mul(D50_TO_D65, fm));
        float err = 0;
        for (int i = 0; i < 9; i++) err = Math.max(err, Math.abs(a[i] - (i % 4 == 0 ? 1f : 0f)));
        return err < 0.05f;
    }

    /** Bradford adaptation from white {@code src} (XYZ) to D65. */
    private static float[] adaptToD65(float[] src) {
        float[] s = mul(BRADFORD, src), d = mul(BRADFORD, D65_XYZ);
        float[] g = {d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
        return mul(inverse(BRADFORD), mul(g, BRADFORD));
    }

    /** Interpolation weight towards illuminant 1, from the as-shot neutral (DNG spec). */
    private float illuminantWeight(float[] neutral) {
        float w = 0.5f;
        for (int iter = 0; iter < 4; iter++) {
            float[] cm = cm2 == null ? cm1 : lerp(cm1, cm2, w);
            float[] xyz = mul(inverse(mul(lerp(cc1, cc2, w), cm)), neutral);
            float sum = xyz[0] + xyz[1] + xyz[2];
            float x = xyz[0] / sum, y = xyz[1] / sum;
            float n = (x - 0.3320f) / (0.1858f - y);
            float cct = 449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f;
            cct = Math.max(2000f, Math.min(12000f, cct));
            lastCct = cct;
            w = (1f / cct - 1f / t2) / (1f / t1 - 1f / t2);
            w = Math.max(0f, Math.min(1f, w));
        }
        return w;
    }

    private long lastLog;

    /** Shader WB camera RGB -> AWG3 (scaled by exposure), choosing the right DNG path. */
    float[] cameraToAwg3Auto(float[] neutral, float exposure) {
        return fm1 != null && !fmIsPlaceholder ? cameraToAwg3(neutral, exposure)
                : cameraToAwg3ViaColorMatrix(neutral, exposure);
    }

    /**
     * DNG path without a forward matrix: camera -> XYZ is inv(CC * CM) at the interpolated
     * illuminant, then Bradford from the scene white to D65, then XYZ -> AWG3.
     */
    float[] cameraToAwg3ViaColorMatrix(float[] neutral, float exposure) {
        float w = illuminantWeight(neutral);
        float[] x2c = mul(lerp(cc1, cc2, w), cm2 == null ? cm1 : lerp(cm1, cm2, w));
        float[] c2x = inverse(x2c);
        float[] white = mul(c2x, neutral);
        float[] undoShaderWb = {neutral[0] / neutral[1], 0, 0, 0, 1, 0, 0, 0, neutral[2] / neutral[1]};
        float[] m = mul(xyzToAwg3, mul(adaptToD65(white), mul(c2x, undoShaderWb)));
        // neutral (after shader WB) must land on Y = 1
        scale(m, exposure * neutral[1] / white[1]);
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLog > 5000) {
            lastLog = now;
            float[] toSrgb = mul(inverse(SRGB_TO_XYZ), mul(AWG3_TO_XYZ, m));
            scale(toSrgb, 1f / exposure);
            Log.i(TAG, "ColorMatrix path: w=" + w + " cam->sRGB=" + java.util.Arrays.toString(toSrgb));
        }
        return m;
    }

    boolean hasForwardMatrix() {
        return fm1 != null && cm1 != null;
    }

    /** Vendor runtime CCM (WB camera -> linear sRGB, row-major) -> AWG3, scaled by exposure. */
    float[] fromIspCcm(float[] ccm, float exposure) {
        float[] m = mul(xyzToAwg3, mul(SRGB_TO_XYZ, ccm));
        scale(m, exposure);
        return m;
    }

    /**
     * @param neutral as-shot neutral (camera RGB of white)
     * @param greenGain the white-balance scale already applied to green (neutral_g / neutral_g = 1)
     * @return row-major WB camera RGB -> AWG3 matrix, scaled by exposure
     */
    float[] cameraToAwg3(float[] neutral, float exposure) {
        // DNG: XYZ->camera = CC * CM, so the scene white in XYZ is inv(CC * CM) * neutral.
        float w = 0.5f;
        for (int iter = 0; iter < 4; iter++) {
            float[] cm = cm2 == null ? cm1 : lerp(cm1, cm2, w);
            float[] xyz = mul(inverse(mul(lerp(cc1, cc2, w), cm)), neutral);
            float sum = xyz[0] + xyz[1] + xyz[2];
            float x = xyz[0] / sum, y = xyz[1] / sum;
            float n = (x - 0.3320f) / (0.1858f - y);
            float cct = 449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f;
            cct = Math.max(2000f, Math.min(12000f, cct));
            lastCct = cct;
            w = (1f / cct - 1f / t2) / (1f / t1 - 1f / t2);
            w = Math.max(0f, Math.min(1f, w));
        }
        float[] fm = fm2 == null ? fm1 : lerp(fm1, fm2, w);
        // DNG forward path: ref = inv(CC) * cam, white-balanced by the reference-space neutral,
        // then FM -> XYZ D50. The shader has already scaled camera channel c by
        // g_c = neutral_g / neutral_c, so undo that first: cam = diag(1/g) * shaderRgb.
        float[] invCc = inverse(lerp(cc1, cc2, w));
        float[] refN = mul(invCc, neutral);
        float[] wb = {1f / refN[0], 0, 0, 0, 1f / refN[1], 0, 0, 0, 1f / refN[2]};
        float[] undoShaderWb = {neutral[0] / neutral[1], 0, 0, 0, 1, 0, 0, 0, neutral[2] / neutral[1]};
        float[] m = mul(xyzToAwg3, mul(D50_TO_D65, mul(fm, mul(wb, mul(invCc, undoShaderWb)))));
        scale(m, exposure);
        return m;
    }

    /** Fallback when there is no forward matrix: the HAL's camera->sRGB transform. */
    float[] fromSrgbTransform(ColorSpaceTransform t, float exposure) {
        float[] tr = t == null ? new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1} : toArray(t);
        float[] m = mul(xyzToAwg3, mul(SRGB_TO_XYZ, tr));
        scale(m, exposure);
        return m;
    }

    private static float illuminantTemp(int code) {
        switch (code) {
            case 17: case 3: return 2856f;   // Standard A / tungsten
            case 18: return 4874f;           // Standard B
            case 19: return 6774f;           // Standard C
            case 20: return 5503f;           // D55
            case 21: case 10: return 6504f;  // D65 / cloudy
            case 22: case 11: return 7504f;  // D75 / shade
            case 23: return 5003f;           // D50
            case 2: case 14: return 4150f;   // fluorescent / cool white fluorescent
            case 12: return 6430f;           // daylight fluorescent
            case 13: return 5000f;           // day white fluorescent
            case 15: return 3450f;           // white fluorescent
            case 24: return 3200f;           // ISO studio tungsten
            default: return 5500f;           // daylight, flash, fine weather
        }
    }

    static float[] toArray(ColorSpaceTransform t) {
        if (t == null) return null;
        Rational[] r = new Rational[9];
        t.copyElements(r, 0);
        float[] m = new float[9];
        for (int i = 0; i < 9; i++) m[i] = r[i].floatValue();
        return m;
    }

    static float[] mul(float[] a, float[] b) {
        if (b.length == 3) {
            return new float[]{
                    a[0] * b[0] + a[1] * b[1] + a[2] * b[2],
                    a[3] * b[0] + a[4] * b[1] + a[5] * b[2],
                    a[6] * b[0] + a[7] * b[1] + a[8] * b[2]};
        }
        float[] c = new float[9];
        for (int r = 0; r < 3; r++)
            for (int k = 0; k < 3; k++)
                c[r * 3 + k] = a[r * 3] * b[k] + a[r * 3 + 1] * b[3 + k] + a[r * 3 + 2] * b[6 + k];
        return c;
    }

    static float[] inverse(float[] m) {
        float a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        float det = a * A + b * B + c * C;
        float s = 1f / det;
        return new float[]{
                A * s, -(b * i - c * h) * s, (b * f - c * e) * s,
                B * s, (a * i - c * g) * s, -(a * f - c * d) * s,
                C * s, -(a * h - b * g) * s, (a * e - b * d) * s};
    }

    private static float[] lerp(float[] a, float[] b, float w) {
        float[] c = new float[9];
        for (int i = 0; i < 9; i++) c[i] = a[i] * w + b[i] * (1f - w);
        return c;
    }

    private static void scale(float[] m, float s) {
        for (int i = 0; i < 9; i++) m[i] *= s;
    }
}
