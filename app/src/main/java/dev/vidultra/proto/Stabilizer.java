package dev.vidultra.proto;

import java.util.Locale;

/**
 * Gyro stabilization for one frame at a time, run when the frame is rendered (which is
 * {@code lookaheadNs} after capture, so the smoothed path can see the future).
 *
 * Model: a virtual, smoothly moving camera V renders the output. Each sensor row y was
 * exposed at its own time t(y) = ts + exposure/2 + skew * y/H (rolling shutter), when the real
 * camera had orientation C(t(y)). A virtual ray n maps to the real camera ray C(t)^T V n,
 * then through the lens (intrinsics + Brown distortion) to a raw pixel. The per-row rotation
 * is evaluated in BANDS row bands and interpolated in the shader.
 */
final class Stabilizer {
    static final int BANDS = 32;

    // Tunables
    double smoothSigmaS = 0.35;        // Gaussian sigma of the smoothed path, seconds (sim-tuned)
    long gyroOffsetNs = 0;             // gyro timestamp correction, if the clocks disagree
    boolean enabled = true;
    boolean limitToMargin = true;      // test switch: false lets the warp leave the sensor
    // Hidden-OIS model, for lenses whose OIS cannot be turned off or read back. The OIS servo
    // cancels the fast part of pitch/yaw (rotation minus its first-order low-pass with time
    // constant oisTauS), scaled by oisGain and limited to oisLimitRad. 0 gain = no OIS.
    double oisGain = 0;
    double oisTauS = 0.3;
    double oisLimitRad = Math.toRadians(1.0);
    // Measured OIS (from the picture). When set, it replaces the oisGain model.
    VisionOis visionOis;
    // Sensor region the warp may read (defaults to the whole sensor).
    double validX0, validY0, validX1 = -1, validY1 = -1;

    private final OrientationSource gyro;
    private final int rawW, rawH;
    private final double fx, fy, cx, cy, skewCoef;
    private final double k1, k2, k3, p1, p2;
    private final int outW, outH;
    private final double foBase;       // virtual focal at the base zoom
    private double fo;                 // virtual focal this frame (dynamic zoom)
    private final int border;          // keep sampling this far inside the sensor
    private double alphaPrev = 1;

    // Dynamic crop ("Auto Boost"): zoom in beyond the base crop only as far as upcoming shake
    // needs, starting before it arrives (look-ahead) and relaxing slowly afterwards.
    boolean dynamicZoom = true;
    double maxExtraZoom = 1.35;        // on top of the base zoom
    double zoomNow = 1;                // current extra zoom factor
    private static final int NEED_CAP = 256;
    private final long[] needT = new long[NEED_CAP];
    private final double[] needZ = new double[NEED_CAP];
    private int needHead = -1, needCount;

    // Planned margin easing (fixed crop): how much smoothing each frame can afford, evaluated
    // for this frame and the look-ahead edge, so the smoothing eases off before big motion
    // instead of snapping back when the margin runs out.
    boolean plannedAlpha = true;
    // Per-axis soft limit: each axis of the correction (pitch, yaw, roll) is compressed with
    // tanh toward the room that axis has, so only the axis running out of margin gives way,
    // and it gives way gradually.
    boolean softClamp = false;

    // Look-ahead path planner: smooth the camera path over the window, pull it back inside each
    // axis's margin wherever it would leave the crop, re-smooth, repeat. Corrections blend in
    // early instead of at the last moment, and already-rendered frames keep the path continuous.
    boolean planPath = false;
    int planIterations = 6;
    boolean planFixPast = false;       // pin the past to what was rendered (adds lag; off)
    private static final int HIST = 256;
    private final long[] histT = new long[HIST];
    private final double[][] histV = new double[HIST][];
    private int histHead = -1, histCount;
    double softFraction = 0.85;        // share of the geometric margin the soft limit aims at
    private double alphaPlan = 1;
    private final long[] aNeedT = new long[NEED_CAP];
    private final double[] aNeed = new double[NEED_CAP];
    private int aHead = -1, aCount;

    // Per-frame results
    final float[] bandMats = new float[BANDS * 9];
    int boxX, boxY, boxW, boxH;        // sensor region the warp reads (pass-1 render area)
    double alpha;                      // 1 = full smoothing, <1 = limited by the margin
    final double[] virtual = new double[4];   // smoothed camera-to-world orientation

    Stabilizer(OrientationSource gyro, int rawW, int rawH, float[] intrinsics, float[] distortion,
               int outW, int outH, float baseCropW, double zoom, int border) {
        this.gyro = gyro;
        this.rawW = rawW;
        this.rawH = rawH;
        fx = intrinsics[0];
        fy = intrinsics[1];
        cx = intrinsics[2];
        cy = intrinsics[3];
        skewCoef = intrinsics[4];
        k1 = distortion[0];
        k2 = distortion[1];
        k3 = distortion[2];
        p1 = distortion[3];
        p2 = distortion[4];
        this.outW = outW;
        this.outH = outH;
        this.border = border;
        // At zoom 1 the output covers baseCropW sensor pixels (the unstabilized crop); a larger
        // zoom crops in, which is the margin the stabilizer can move within.
        foBase = fx * (outW / (double) baseCropW) * zoom;
        fo = foBase;
    }

    float[] virtualIntrinsics() {
        return new float[]{(float) fo, outW * 0.5f, outH * 0.5f};
    }

    float[] lens() {
        return new float[]{(float) fx, (float) fy, (float) cx, (float) cy};
    }

    float[] distortion() {
        return new float[]{(float) k1, (float) k2, (float) k3, (float) p1, (float) p2};
    }

    /**
     * @param ts       SENSOR_TIMESTAMP (start of exposure of the first row)
     * @param exposure SENSOR_EXPOSURE_TIME
     * @param skew     SENSOR_ROLLING_SHUTTER_SKEW (first to last row)
     * @return false if gyro data does not cover the frame yet
     */
    boolean compute(long ts, long exposure, long skew) {
        ts += gyroOffsetNs;
        long tc = ts + exposure / 2 + skew / 2;
        double[] cc = new double[4];
        if (!gyro.orientation(tc, cc)) return false;

        double[] v = cc.clone();
        if (enabled) {
            v = smoothed(tc, cc);
            if (dynamicZoom && limitToMargin) {
                // zoom this frame needs, and what the newest (look-ahead edge) frame needs
                recordNeed(tc, zoomNeeded(ts, exposure, skew, v));
                long tf = Math.min(gyro.latest() - 50_000_000L, tc + 450_000_000L);
                if (tf > tc) {
                    double[] cf = new double[4];
                    gyro.orientation(tf, cf);
                    long tsf = tf - exposure / 2 - skew / 2;
                    recordNeed(tf, zoomNeeded(tsf, exposure, skew, smoothed(tf, cf)));
                }
                // aim at the largest need from 0.25 s ago to 0.45 s ahead; rise quickly
                // enough to arrive in time, relax slowly
                double target = maxNeed(tc - 250_000_000L, tc + 450_000_000L);
                zoomNow += Math.max(-0.004, Math.min(0.03, target - zoomNow));
                zoomNow = Math.max(1, Math.min(maxExtraZoom, zoomNow));
                fo = foBase * zoomNow;
            }
            if (planPath && limitToMargin) v = planned(tc, cc);
            else if (softClamp && limitToMargin) v = softLimit(cc, v);
            if (plannedAlpha && limitToMargin) {
                recordAlpha(tc, alphaNeeded(ts, exposure, skew, cc, v));
                long tf = Math.min(gyro.latest() - 50_000_000L, tc + 450_000_000L);
                if (tf > tc) {
                    double[] cf = new double[4];
                    gyro.orientation(tf, cf);
                    long tsf = tf - exposure / 2 - skew / 2;
                    recordAlpha(tf, alphaNeeded(tsf, exposure, skew, cf, smoothed(tf, cf)));
                }
                double target = minAlpha(tc - 250_000_000L, tc + 450_000_000L);
                alphaPlan += Math.max(-0.08, Math.min(0.02, target - alphaPlan));
                alphaPlan = Math.max(0, Math.min(1, alphaPlan));
                v = slerp(cc, v, alphaPlan);
            }
            // Last resort: the largest smoothing that keeps the output inside the sensor.
            double a = 1;
            if (limitToMargin && !fits(ts, exposure, skew, v)) {
                double lo = 0, hi = 1;
                for (int i = 0; i < 10; i++) {
                    double mid = (lo + hi) * 0.5;
                    if (fits(ts, exposure, skew, slerp(cc, v, mid))) lo = mid; else hi = mid;
                }
                a = lo;
            }
            a = Math.min(a, alphaPrev + 0.2);
            alphaPrev = a;
            alpha = a * (plannedAlpha && limitToMargin ? alphaPlan : 1);
            v = slerp(cc, v, a);
        } else {
            skew = 0;   // plain crop: no rolling-shutter warp either
        }

        System.arraycopy(v, 0, virtual, 0, 4);
        if (planPath) {
            histHead = (histHead + 1) % HIST;
            histT[histHead] = tc;
            histV[histHead] = v.clone();
            if (histCount < HIST) histCount++;
        }
        // Per-band rotation: virtual ray -> real camera ray at that row's exposure time.
        double[] vm = Quat.toMat(v);
        double[] ci = new double[4];
        for (int b = 0; b < BANDS; b++) {
            double y = (b + 0.5) * rawH / BANDS;
            long t = ts + exposure / 2 + (long) (skew * (y / rawH));
            if (!enabled) System.arraycopy(cc, 0, ci, 0, 4);
            else captured(t, ci);
            double[] cm = Quat.toMat(ci);
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++) {
                    // (C^T V)[r][c] = sum_k C[k][r] * V[k][c]
                    double s = cm[r] * vm[c] + cm[3 + r] * vm[3 + c] + cm[6 + r] * vm[6 + c];
                    bandMats[b * 9 + r * 3 + c] = (float) s;
                }
        }
        computeBox();
        return true;
    }

    /**
     * Orientation the image was effectively captured with at time t: the camera orientation,
     * minus what a hidden OIS servo moved the image by (see oisGain).
     */
    void captured(long t, double[] out) {
        gyro.orientation(t, out);
        if (oisGain > 0) modelCaptured(t, out);
        if (visionOis != null) {
            double[] rv = new double[3];
            visionOis.rotationAt(t, rv);
            double[] eff = Quat.mul(out, Quat.fromRotVec(rv[0], rv[1], rv[2]));
            System.arraycopy(eff, 0, out, 0, 4);
        }
    }

    /** Camera orientation minus the parametric OIS model (no vision term). */
    void modelCaptured(long t, double[] out) {
        gyro.orientation(t, out);
        if (oisGain <= 0) return;
        // causal first-order low-pass of the orientation (what the OIS servo lets through)
        double[] inv = Quat.conj(out);
        double sx = 0, sy = 0, sw = 0;
        double[] qi = new double[4];
        for (double dt = 0; dt <= 4 * oisTauS; dt += 1.0 / 200) {
            gyro.orientation(t - (long) (dt * 1e9), qi);
            double[] rv = Quat.toRotVec(Quat.mul(inv, qi));
            double wgt = Math.exp(-dt / oisTauS);
            sx += wgt * rv[0];
            sy += wgt * rv[1];
            sw += wgt;
        }
        // high-pass = orientation relative to its low-pass; OIS cancels pitch (X) and yaw (Y)
        double cx = clamp(oisGain * (-sx / sw), oisLimitRad), cy = clamp(oisGain * (-sy / sw), oisLimitRad);
        double[] eff = Quat.mul(out, Quat.fromRotVec(-cx, -cy, 0));
        System.arraycopy(eff, 0, out, 0, 4);
    }

    private static double clamp(double v, double l) {
        return Math.max(-l, Math.min(l, v));
    }

    /** Smallest extra zoom (<= maxExtraZoom) at which orientation v fits inside the sensor. */
    private double zoomNeeded(long ts, long exposure, long skew, double[] v) {
        double keep = fo;
        double lo = 1, hi = maxExtraZoom;
        fo = foBase * hi;
        boolean fitsMax = fits(ts, exposure, skew, v);
        fo = foBase;
        if (fits(ts, exposure, skew, v)) {
            fo = keep;
            return 1;
        }
        if (!fitsMax) {
            fo = keep;
            return maxExtraZoom;
        }
        for (int i = 0; i < 6; i++) {
            double mid = (lo + hi) * 0.5;
            fo = foBase * mid;
            if (fits(ts, exposure, skew, v)) hi = mid; else lo = mid;
        }
        fo = keep;
        return hi;
    }

    /** Per-axis margins (rad) for pitch, yaw, roll at the current virtual focal. */
    private double[] margins() {
        double hx = outW * 0.5 * fx / fo, hy = outH * 0.5 * fy / fo;
        double x1 = validX1 > 0 ? validX1 : rawW, y1 = validY1 > 0 ? validY1 : rawH;
        double mx = Math.max(1, Math.min(cx - validX0, x1 - cx) - hx - border);
        double my = Math.max(1, Math.min(cy - validY0, y1 - cy) - hy - border);
        return new double[]{softFraction * Math.atan(my / fy), softFraction * Math.atan(mx / fx),
                softFraction * Math.min(mx, my) / Math.hypot(hx, hy)};
    }

    /** The virtual orientation already rendered at time t, if it is in the history. */
    private double[] renderedAt(long t) {
        if (histCount == 0) return null;
        int newest = histHead, oldest = (histHead - histCount + 1 + HIST) % HIST;
        if (t < histT[oldest] || t > histT[newest]) return null;
        for (int i = 0; i < histCount - 1; i++) {
            int a = (newest - i - 1 + HIST) % HIST, b = (newest - i + HIST) % HIST;
            if (histT[a] <= t && t <= histT[b]) {
                double[] o = new double[4];
                Quat.slerp(histV[a], histV[b], (double) (t - histT[a]) / Math.max(1, histT[b] - histT[a]), o);
                return o;
            }
        }
        return null;
    }

    /**
     * Plan the virtual orientation for the frame at tc. Works in rotation vectors relative to
     * the current camera orientation cc (small enough angles within the window).
     */
    private double[] planned(long tc, double[] cc) {
        double step = 1.0 / 48;
        double back = 2.5 * smoothSigmaS, ahead = Math.min(2.5 * smoothSigmaS, (gyro.latest() - tc) * 1e-9);
        int j0 = -(int) Math.floor(back / step), j1 = (int) Math.floor(ahead / step);
        int n = j1 - j0 + 1, zero = -j0;
        double[][] r = new double[n][], fixed = new double[n][], sp = new double[n][];
        double[] inv = Quat.conj(cc), q = new double[4];
        for (int i = 0; i < n; i++) {
            long t = tc + (long) ((j0 + i) * step * 1e9);
            gyro.orientation(t, q);
            r[i] = Quat.toRotVec(Quat.mul(inv, q));
            if (planFixPast && t < tc) {
                double[] rv = renderedAt(t);
                if (rv != null) fixed[i] = Quat.toRotVec(Quat.mul(inv, rv));
            }
        }
        double[] m = margins();
        // Gaussian weights for the smoothing pass
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = Math.exp(-0.5 * Math.pow((i - zero) * step / smoothSigmaS, 2));
        // initial path: smoothed camera
        for (int i = 0; i < n; i++) sp[i] = smoothAt(r, i, step);
        for (int it = 0; it < planIterations; it++) {
            for (int i = 0; i < n; i++) {
                if (fixed[i] != null) {
                    sp[i] = fixed[i].clone();
                    continue;
                }
                for (int a = 0; a < 3; a++) sp[i][a] = Math.max(r[i][a] - m[a], Math.min(r[i][a] + m[a], sp[i][a]));
            }
            double[][] nx = new double[n][];
            for (int i = 0; i < n; i++) nx[i] = smoothAt(sp, i, step);
            sp = nx;
        }
        double[] s0 = sp[zero];
        for (int a = 0; a < 3; a++) s0[a] = Math.max(-m[a], Math.min(m[a], s0[a]));   // r[zero] = 0
        return Quat.mul(cc, Quat.fromRotVec(s0[0], s0[1], s0[2]));
    }

    /** Gaussian-weighted mean of path x around index i (sigma = smoothSigmaS). */
    private double[] smoothAt(double[][] x, int i, double step) {
        double sx = 0, sy = 0, sz = 0, sw = 0;
        int rad = (int) Math.ceil(2.5 * smoothSigmaS / step);
        for (int k = Math.max(0, i - rad); k <= Math.min(x.length - 1, i + rad); k++) {
            double wt = Math.exp(-0.5 * Math.pow((k - i) * step / smoothSigmaS, 2));
            sx += wt * x[k][0];
            sy += wt * x[k][1];
            sz += wt * x[k][2];
            sw += wt;
        }
        return new double[]{sx / sw, sy / sw, sz / sw};
    }

    /** Compress each axis of the correction cc -> v into the margin that axis has. */
    private double[] softLimit(double[] cc, double[] v) {
        double[] r = Quat.toRotVec(Quat.mul(Quat.conj(cc), v));
        // visible half-size of the output on the sensor, at this frame's virtual focal
        double hx = outW * 0.5 * fx / fo, hy = outH * 0.5 * fy / fo;
        double x1 = validX1 > 0 ? validX1 : rawW, y1 = validY1 > 0 ? validY1 : rawH;
        double mx = Math.max(1, Math.min(cx - validX0, x1 - cx) - hx - border);
        double my = Math.max(1, Math.min(cy - validY0, y1 - cy) - hy - border);
        double yawMax = softFraction * Math.atan(mx / fx), pitchMax = softFraction * Math.atan(my / fy);
        // roll swings the corners: the smaller of the two margins over the half-diagonal
        double rollMax = softFraction * Math.min(mx, my) / Math.hypot(hx, hy);
        r[0] = pitchMax * Math.tanh(r[0] / pitchMax);
        r[1] = yawMax * Math.tanh(r[1] / yawMax);
        r[2] = rollMax * Math.tanh(r[2] / rollMax);
        return Quat.mul(cc, Quat.fromRotVec(r[0], r[1], r[2]));
    }

    /** Largest blend toward the smoothed path (0..1) whose output stays inside the sensor. */
    private double alphaNeeded(long ts, long exposure, long skew, double[] cc, double[] v) {
        if (fits(ts, exposure, skew, v)) return 1;
        double lo = 0, hi = 1;
        for (int i = 0; i < 7; i++) {
            double mid = (lo + hi) * 0.5;
            if (fits(ts, exposure, skew, slerp(cc, v, mid))) lo = mid; else hi = mid;
        }
        return lo;
    }

    private void recordAlpha(long t, double a) {
        aHead = (aHead + 1) % NEED_CAP;
        aNeedT[aHead] = t;
        aNeed[aHead] = a;
        if (aCount < NEED_CAP) aCount++;
    }

    private double minAlpha(long t0, long t1) {
        double m = 1;
        for (int i = 0; i < aCount; i++) {
            int k = (aHead - i + NEED_CAP) % NEED_CAP;
            if (aNeedT[k] >= t0 && aNeedT[k] <= t1) m = Math.min(m, aNeed[k]);
        }
        return m;
    }

    private void recordNeed(long t, double z) {
        needHead = (needHead + 1) % NEED_CAP;
        needT[needHead] = t;
        needZ[needHead] = z;
        if (needCount < NEED_CAP) needCount++;
    }

    private double maxNeed(long t0, long t1) {
        double m = 1;
        for (int i = 0; i < needCount; i++) {
            int k = (needHead - i + NEED_CAP) % NEED_CAP;
            if (needT[k] >= t0 && needT[k] <= t1) m = Math.max(m, needZ[k]);
        }
        return m;
    }

    private double[] smoothed(long tc, double[] cc) {
        double[] inv = Quat.conj(cc);
        double sx = 0, sy = 0, sz = 0, sw = 0;
        double half = 2.5 * smoothSigmaS;
        long newest = gyro.latest();
        double[] qi = new double[4];
        for (double dt = -half; dt <= half + 1e-9; dt += 1.0 / 240) {
            long t = tc + (long) (dt * 1e9);
            if (t > newest) break;   // only what has been measured
            gyro.orientation(t, qi);
            double[] rv = Quat.toRotVec(Quat.mul(inv, qi));
            double w = Math.exp(-0.5 * (dt / smoothSigmaS) * (dt / smoothSigmaS));
            sx += w * rv[0];
            sy += w * rv[1];
            sz += w * rv[2];
            sw += w;
        }
        return Quat.mul(cc, Quat.fromRotVec(sx / sw, sy / sw, sz / sw));
    }

    private static double[] slerp(double[] a, double[] b, double t) {
        double[] o = new double[4];
        Quat.slerp(a, b, t, o);
        return o;
    }

    // Captured orientation per row band for the frame being tested; fits() is called many times
    // per frame (zoom search, margin limit), and this is the expensive part (OIS model).
    private final double[][] bandCap = new double[BANDS][];
    private long bandCapTs = Long.MIN_VALUE;

    private double[] bandCaptured(long ts, long exposure, long skew, double y) {
        if (bandCapTs != ts) {
            for (int b = 0; b < BANDS; b++) {
                double[] q = new double[4];
                captured(ts + exposure / 2 + (long) (skew * ((b + 0.5) / BANDS)), q);
                bandCap[b] = Quat.toMat(q);
            }
            bandCapTs = ts;
        }
        int b = (int) Math.max(0, Math.min(BANDS - 1, y / rawH * BANDS));
        return bandCap[b];
    }

    /** Output points on the border must land inside the sensor. */
    private boolean fits(long ts, long exposure, long skew, double[] v) {
        double[] vm = Quat.toMat(v);
        double[] uv = new double[2];
        for (int i = 0; i < 16; i++) {
            double ox, oy;
            int side = i / 4, k = i % 4;
            double f = k / 3.0;
            if (side == 0) { ox = f * outW; oy = 0; }
            else if (side == 1) { ox = outW; oy = f * outH; }
            else if (side == 2) { ox = (1 - f) * outW; oy = outH; }
            else { ox = 0; oy = (1 - f) * outH; }
            // row time from a first guess at the source row
            double[] m = null;
            double y = rawH * 0.5;
            for (int it = 0; it < 2; it++) {
                m = mat(bandCaptured(ts, exposure, skew, y), vm);
                project(m, ox, oy, uv);
                y = uv[1];
            }
            double x1 = validX1 > 0 ? validX1 : rawW, y1 = validY1 > 0 ? validY1 : rawH;
            if (uv[0] < validX0 + border || uv[0] > x1 - border || uv[1] < validY0 + border || uv[1] > y1 - border)
                return false;
        }
        return true;
    }

    private static double[] mat(double[] cm, double[] vm) {
        double[] m = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                m[r * 3 + c] = cm[r] * vm[c] + cm[3 + r] * vm[3 + c] + cm[6 + r] * vm[6 + c];
        return m;
    }

    /** Output pixel -> raw pixel through rotation m, intrinsics and distortion. */
    private void project(double[] m, double ox, double oy, double[] out) {
        double nx = (ox - outW * 0.5) / fo, ny = (oy - outH * 0.5) / fo;
        double rx = m[0] * nx + m[1] * ny + m[2];
        double ry = m[3] * nx + m[4] * ny + m[5];
        double rz = m[6] * nx + m[7] * ny + m[8];
        double x = rx / rz, y = ry / rz;
        double r2 = x * x + y * y;
        double rad = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2;
        double xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x);
        double yd = y * rad + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y;
        out[0] = fx * xd + skewCoef * yd + cx;
        out[1] = fy * yd + cy;
    }

    /** Bounding box of where the output samples from, using the band matrices. */
    private void computeBox() {
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        double[] uv = new double[2];
        double[] m = new double[9];
        for (int j = 0; j <= 8; j++) {
            for (int i = 0; i <= 8; i++) {
                if (i != 0 && i != 8 && j != 0 && j != 8) continue;   // border is enough
                double ox = outW * i / 8.0, oy = outH * j / 8.0;
                // band from a first-guess row, then refine once
                double y = rawH * 0.5;
                for (int it = 0; it < 2; it++) {
                    int b = (int) Math.max(0, Math.min(BANDS - 1, y / rawH * BANDS));
                    for (int k = 0; k < 9; k++) m[k] = bandMats[b * 9 + k];
                    project(m, ox, oy, uv);
                    y = uv[1];
                }
                minX = Math.min(minX, uv[0]);
                maxX = Math.max(maxX, uv[0]);
                minY = Math.min(minY, uv[1]);
                maxY = Math.max(maxY, uv[1]);
            }
        }
        int pad = 8;
        int x0 = Math.max(0, (int) Math.floor(minX) - pad) & ~3;
        int y0 = Math.max(0, (int) Math.floor(minY) - pad) & ~1;
        int x1 = Math.min(rawW, (int) Math.ceil(maxX) + pad);
        int y1 = Math.min(rawH, (int) Math.ceil(maxY) + pad);
        boxX = x0;
        boxY = y0;
        boxW = ((x1 - x0) + 3) & ~3;
        if (boxX + boxW > rawW) boxW = (rawW - boxX) & ~3;
        boxH = ((y1 - y0) + 1) & ~1;
        if (boxY + boxH > rawH) boxH = (rawH - boxY) & ~1;
    }

    String describe() {
        return String.format(Locale.US, "alpha=%.2f box=%dx%d@%d,%d", alpha, boxW, boxH, boxX, boxY);
    }

}
