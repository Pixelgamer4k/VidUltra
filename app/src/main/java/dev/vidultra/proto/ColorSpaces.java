package dev.vidultra.proto;

/**
 * Output colour spaces: camera log curves and their gamuts, from the manufacturers' published
 * specifications. The pipeline works in scene-linear ARRI Wide Gamut 3 (18% grey = 0.18); an
 * output is a 3x3 matrix AWG3 -> target gamut and a transfer curve (GLSL in {@link Shaders}).
 *
 * Curve ids must match {@code curveEncode} in Shaders.COMMON.
 */
final class ColorSpaces {
    private ColorSpaces() {}

    // transfer curves
    static final int LOGC3 = 0, LOGC4 = 1, SLOG3 = 2, VLOG = 3, FLOG = 4, FLOG2 = 5, CLOG3 = 6,
            APPLE_LOG = 7, LOG3G10 = 8, DAVINCI_INTERMEDIATE = 9, ACESCCT = 10, NLOG = 11, DLOG = 12, HLG = 13;

    /** Gamut primaries (xy for R, G, B) and white point. */
    static final class Gamut {
        final String name;
        final double[] xy;   // rx ry gx gy bx by wx wy

        Gamut(String name, double... xy) {
            this.name = name;
            this.xy = xy;
        }
    }

    private static final double D65X = 0.3127, D65Y = 0.3290, ACESX = 0.32168, ACESY = 0.33767;

    static final Gamut[] GAMUTS = {
            new Gamut("ARRI Wide Gamut 3", 0.6840, 0.3130, 0.2210, 0.8480, 0.0861, -0.1020, D65X, D65Y),
            new Gamut("ARRI Wide Gamut 4", 0.7347, 0.2653, 0.1424, 0.8576, 0.0991, -0.0308, D65X, D65Y),
            new Gamut("S-Gamut3", 0.730, 0.280, 0.140, 0.855, 0.100, -0.050, D65X, D65Y),
            new Gamut("S-Gamut3.Cine", 0.766, 0.275, 0.225, 0.800, 0.089, -0.087, D65X, D65Y),
            new Gamut("V-Gamut", 0.730, 0.280, 0.165, 0.840, 0.100, -0.030, D65X, D65Y),
            new Gamut("Rec.2020 / F-Gamut", 0.708, 0.292, 0.170, 0.797, 0.131, 0.046, D65X, D65Y),
            new Gamut("Cinema Gamut", 0.740, 0.270, 0.170, 1.140, 0.080, -0.100, D65X, D65Y),
            new Gamut("REDWideGamutRGB", 0.780308, 0.304253, 0.121595, 1.493994, 0.095612, -0.084589, D65X, D65Y),
            new Gamut("DaVinci Wide Gamut", 0.8000, 0.3130, 0.1682, 0.9877, 0.0790, -0.1155, D65X, D65Y),
            new Gamut("ACES AP1", 0.713, 0.293, 0.165, 0.830, 0.128, 0.044, ACESX, ACESY),
            new Gamut("ACES AP0", 0.7347, 0.2653, 0.0, 1.0, 0.0001, -0.0770, ACESX, ACESY),
            new Gamut("DJI D-Gamut", 0.71, 0.31, 0.21, 0.88, 0.09, -0.08, D65X, D65Y),
            new Gamut("P3-D65", 0.680, 0.320, 0.265, 0.690, 0.150, 0.060, D65X, D65Y),
            new Gamut("Rec.709", 0.640, 0.330, 0.300, 0.600, 0.150, 0.060, D65X, D65Y),
    };
    static final int G_AWG3 = 0, G_AWG4 = 1, G_SGAMUT3_CINE = 3, G_VGAMUT = 4, G_REC2020 = 5,
            G_CINEMA = 6, G_RWG = 7, G_DWG = 8, G_AP1 = 9, G_DGAMUT = 11;

    /** A recording look: log curve over its native gamut (or Rec.709, the display look). */
    static final class Profile {
        final String name, shortName, fileTag;
        final int curve, gamut;

        Profile(String name, String shortName, String fileTag, int curve, int gamut) {
            this.name = name;
            this.shortName = shortName;
            this.fileTag = fileTag;
            this.curve = curve;
            this.gamut = gamut;
        }
    }

    static final Profile[] LOG_PROFILES = {
            new Profile("ARRI LogC3", "LogC3", "LogC3", LOGC3, G_AWG3),
            new Profile("ARRI LogC4", "LogC4", "LogC4", LOGC4, G_AWG4),
            new Profile("Sony S-Log3", "S-Log3", "SLog3", SLOG3, G_SGAMUT3_CINE),
            new Profile("Panasonic V-Log", "V-Log", "VLog", VLOG, G_VGAMUT),
            new Profile("Fujifilm F-Log", "F-Log", "FLog", FLOG, G_REC2020),
            new Profile("Fujifilm F-Log2", "F-Log2", "FLog2", FLOG2, G_REC2020),
            new Profile("Canon Log 3", "C-Log3", "CLog3", CLOG3, G_CINEMA),
            new Profile("Apple Log", "Apple Log", "AppleLog", APPLE_LOG, G_REC2020),
            new Profile("RED Log3G10", "Log3G10", "Log3G10", LOG3G10, G_RWG),
            new Profile("DaVinci Intermediate", "DaVinci", "DaVinciIntermediate", DAVINCI_INTERMEDIATE, G_DWG),
            new Profile("ACEScct", "ACEScct", "ACEScct", ACESCCT, G_AP1),
            new Profile("Nikon N-Log", "N-Log", "NLog", NLOG, G_REC2020),
            new Profile("DJI D-Log", "D-Log", "DLog", DLOG, G_DGAMUT),
            new Profile("HLG (BT.2100)", "HLG", "HLG", HLG, G_REC2020),
    };

    /** Row-major AWG3 linear -> gamut g linear (Bradford adaptation when the white differs). */
    static float[] awg3To(int g) {
        double[] src = rgbToXyz(GAMUTS[G_AWG3].xy);
        double[] dst = rgbToXyz(GAMUTS[g].xy);
        double[] m = mul(inv(dst), src);
        double[] w0 = GAMUTS[G_AWG3].xy, w1 = GAMUTS[g].xy;
        if (Math.abs(w0[6] - w1[6]) > 1e-6 || Math.abs(w0[7] - w1[7]) > 1e-6) {
            m = mul(inv(dst), mul(bradford(w0[6], w0[7], w1[6], w1[7]), src));
        }
        float[] f = new float[9];
        for (int i = 0; i < 9; i++) f[i] = (float) m[i];
        return f;
    }

    private static double[] rgbToXyz(double[] p) {
        double[] cols = new double[9];
        for (int i = 0; i < 3; i++) {
            double x = p[2 * i], y = p[2 * i + 1];
            cols[i] = x / y;                 // X
            cols[3 + i] = 1;                 // Y
            cols[6 + i] = (1 - x - y) / y;   // Z
        }
        double[] w = {p[6] / p[7], 1, (1 - p[6] - p[7]) / p[7]};
        double[] s = mulVec(inv(cols), w);
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) cols[r * 3 + c] *= s[c];
        return cols;
    }

    private static double[] bradford(double sx, double sy, double dx, double dy) {
        double[] b = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
        double[] s = mulVec(b, new double[]{sx / sy, 1, (1 - sx - sy) / sy});
        double[] d = mulVec(b, new double[]{dx / dy, 1, (1 - dx - dy) / dy});
        double[] g = {d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
        return mul(inv(b), mul(g, b));
    }

    static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    static double[] mulVec(double[] m, double[] v) {
        return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    static double[] inv(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }

    /** Reference implementation of the GLSL curves (used by the offline check). */
    static double encode(int curve, double x) {
        switch (curve) {
            case LOGC3:
                return x > 0.010591 ? 0.247190 * Math.log10(5.555556 * x + 0.052272) + 0.385537 : 5.367655 * x + 0.092809;
            case LOGC4: {
                double a = (Math.pow(2, 18) - 16) / 117.45, b = (1023 - 95) / 1023.0, c = 95 / 1023.0;
                double s = (7 * Math.log(2) * Math.pow(2, 7 - 14 * c / b)) / (a * b);
                double t = (Math.pow(2, 14 * (-c / b) + 6) - 64) / a;
                return x >= t ? (Math.log(a * x + 64) / Math.log(2) - 6) / 14 * b + c : (x - t) / s;
            }
            case SLOG3:
                return x >= 0.01125 ? (Math.log10((x + 0.01) / 0.19) * 261.5 + 420) / 1023 : (x * 76.2102946929 / 0.01125 + 95) / 1023;
            case VLOG:
                return x < 0.01 ? 5.6 * x + 0.125 : 0.241514 * Math.log10(x + 0.00873) + 0.598206;
            case FLOG:
                return x >= 0.00089 ? 0.344676 * Math.log10(0.555556 * x + 0.009468) + 0.790453 : 8.735631 * x + 0.092864;
            case FLOG2:
                return x >= 0.000889 ? 0.245281 * Math.log10(5.555556 * x + 0.064829) + 0.384316 : 8.799461 * x + 0.092864;
            case CLOG3: {
                double v = x / 0.9;
                if (v < -0.014) return -0.36726845 * Math.log10(-v * 14.98325 + 1) + 0.12783901;
                if (v <= 0.014) return 1.9754798 * v + 0.12512219;
                return 0.36726845 * Math.log10(v * 14.98325 + 1) + 0.12240537;
            }
            case APPLE_LOG: {
                double r0 = -0.05641088, rt = 0.01, c = 47.28711236, beta = 0.00964052, gamma = 0.08550479, delta = 0.69336945;
                if (x >= rt) return gamma * Math.log(x + beta) / Math.log(2) + delta;
                if (x >= r0) return c * (x - r0) * (x - r0);
                return 0;
            }
            case LOG3G10: {
                double v = x + 0.01;
                return v < 0 ? v * 15.1927 : 0.224282 * Math.log10(v * 155.975327 + 1);
            }
            case DAVINCI_INTERMEDIATE:
                return x <= 0.00262409 ? x * 10.44426855 : (Math.log(x + 0.0075) / Math.log(2) + 7.0) * 0.07329248;
            case ACESCCT:
                return x <= 0.0078125 ? 10.5402377416545 * x + 0.0729055341958355 : (Math.log(x) / Math.log(2) + 9.72) / 17.52;
            case NLOG:
                return x < 0.328 ? 650 * Math.cbrt(x + 0.0075) / 1023 : (150 * Math.log(x) + 619) / 1023;
            case DLOG:
                return x <= 0.0078 ? 6.025 * x + 0.0929 : Math.log10(x * 0.9892 + 0.0108) * 0.256663 + 0.584555;
            case HLG: {
                double e = Math.max(0, Math.min(1, x * HLG_SCALE));
                return e <= 1.0 / 12 ? Math.sqrt(3 * e) : 0.17883277 * Math.log(12 * e - 0.28466892) + 0.55991073;
            }
            default:
                return x;
        }
    }

    /** Scene-linear -> HLG signal light: diffuse white (0.9) at the BT.2408 reference 75%. */
    static final double HLG_SCALE = 0.2647 / 0.9;
}
