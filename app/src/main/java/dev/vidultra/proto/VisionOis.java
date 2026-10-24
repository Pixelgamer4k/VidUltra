package dev.vidultra.proto;

/**
 * Measures the image motion the gyro cannot explain, from the picture: what a hidden OIS lens
 * did (any phone, controllable or not), and the shift from the camera moving (walking bob:
 * translation, which no gyro sees). Both are removed the same way.
 *
 * Per frame, three horizontal strips of a small grey image (the ISP side stream on the phone)
 * are phase-correlated against the previous frame. Each strip was exposed at its own time
 * (rolling shutter); the gyro says how far pure rotation should have moved the content between
 * those times. What is left, measured minus predicted, is the extra image shift: the OIS lens
 * and the camera's own movement (step bob). The strips are combined centre-weighted, robust to a
 * strip on a different depth. The shifts are accumulated into a path P(t); the correction is P
 * minus its centred (zero-phase) Gaussian smoothing, so slow deliberate moves stay and fast
 * shake goes, with no phase lag (the recording runs behind by the stabilizer's look-ahead, so
 * the future of P is known). A frame that cannot be matched (heel-strike blur) is skipped and
 * the next frame is matched against the last good one, so no motion is lost.
 *
 * The stabilizer then treats o(t) as an extra camera rotation when mapping output pixels into
 * the RAW frame, so the warp follows the image the lens actually captured.
 */
final class VisionOis {
    static final int FW = 512, FH = 64;          // FFT window per strip

    private final OrientationSource gyro;
    private final int w, h;
    private final double scale;                  // raw pixels per vision pixel
    private final double originX, originY;       // raw coords of vision pixel (0,0)
    private final double fx, fy;
    private final int rawH;
    private final int[] stripY;
    long gyroOffsetNs;
    // Gaussian sigma of the path smoothing: motion slower than ~1/(2 pi sigma) is kept.
    double smoothSigmaS = 0.2;
    double minPeak = 0.03;      // phase-correlation peak below this = no usable texture
    double maxSpread = 12;      // strips may disagree (parallax); only reject wild outliers
    // Hybrid mode: a parametric OIS model (orientation -> captured orientation) is applied to the
    // gyro prediction first, so vision only measures what the model misses.
    java.util.function.BiConsumer<Long, double[]> baseCaptured;
    double maxShiftRaw;                          // OIS travel limit, raw pixels

    private double[][][] prevSpec;
    private long prevTs, prevExp, prevSkew;
    private double ox, oy;                       // accumulated path P, raw pixels
    // per-frame log for diagnosis: t, measured dy, gyro-predicted dy, residual dy, used, P_y
    java.io.Writer log;
    private long lastSampleT;

    // samples of o(t), ring buffer
    private static final int CAP = 1024;
    private final long[] st = new long[CAP];
    private final double[] sx = new double[CAP], sy = new double[CAP];
    private int head = -1, count;

    // diagnostics
    int framesUsed, framesRejected;

    VisionOis(OrientationSource gyro, int w, int h, double scale, double originX, double originY,
              double fx, double fy, int rawH) {
        this.gyro = gyro;
        this.w = w;
        this.h = h;
        this.scale = scale;
        this.originX = originX;
        this.originY = originY;
        this.fx = fx;
        this.fy = fy;
        this.rawH = rawH;
        // three strips spread over the frame height
        stripY = new int[]{h / 6 - FH / 2, h / 2 - FH / 2, 5 * h / 6 - FH / 2};
        maxShiftRaw = Math.toRadians(2.0) * fx;
    }

    synchronized String describe() {
        return String.format(java.util.Locale.US, "used %d rejected %d, offset now (%.1f, %.1f) raw px",
                framesUsed, framesRejected, ox, oy);
    }

    /** Forget the previous frame and the integrated offset (new recording). */
    synchronized void reset() {
        prevSpec = null;
        ox = oy = 0;
        count = 0;
        head = -1;
        framesUsed = framesRejected = 0;
    }

    /** Sensor-time of a vision row (rolling shutter). */
    private long rowTime(long ts, long exposure, long skew, double visionRow) {
        double sensorRow = originY + visionRow * scale;
        return ts + exposure / 2 + (long) (skew * (sensorRow / rawH)) + gyroOffsetNs;
    }

    /**
     * Feed one grey frame (w x h, row-major). Called from one thread only; the FFT work runs
     * without the lock so the render thread's shiftAt() never waits on it.
     */
    void onFrame(byte[] gray, long ts, long exposure, long skew) {
        double[][][] spec = new double[3][][];
        for (int s = 0; s < 3; s++) spec[s] = stripSpectrum(gray, stripY[s]);
        if (prevSpec != null) {
            double[] rx = new double[3], ry = new double[3];
            double[] q0 = new double[4], q1 = new double[4];
            boolean okAll = true;
            double mdy = 0, pdy = 0;
            for (int s = 0; s < 3; s++) {
                double[] d = phaseCorrelate(prevSpec[s], spec[s]);
                if (d[2] < minPeak) okAll = false;   // weak peak: no usable texture / heavy blur
                double row = stripY[s] + FH / 2.0;
                long ta = rowTime(prevTs, prevExp, prevSkew, row), tb = rowTime(ts, exposure, skew, row);
                if (baseCaptured != null) {
                    baseCaptured.accept(ta, q0);
                    baseCaptured.accept(tb, q1);
                } else {
                    gyro.orientation(ta, q0);
                    gyro.orientation(tb, q1);
                }
                double[] rv = Quat.toRotVec(Quat.mul(Quat.conj(q0), q1));
                // pure rotation moves content by -f * rot_y horizontally, +f * rot_x vertically
                double px = -fx * rv[1] / scale, py = fy * rv[0] / scale;
                rx[s] = d[0] - px;
                ry[s] = d[1] - py;
                if (s == 1) {
                    mdy = d[1];
                    pdy = py;
                }
            }
            double mx = median(rx), my = median(ry);
            // centre-weighted mean of the strips that agree with the median (a strip on a much
            // nearer or farther surface is left out)
            double[] wgt = {0.25, 0.5, 0.25};
            double wx = 0, wy = 0, ws = 0;
            for (int s = 0; s < 3; s++) {
                if (Math.abs(rx[s] - mx) > 4 || Math.abs(ry[s] - my) > 4) continue;
                wx += wgt[s] * rx[s];
                wy += wgt[s] * ry[s];
                ws += wgt[s];
            }
            boolean used = okAll && ws > 0;
            long t = rowTime(ts, exposure, skew, h / 2.0) - gyroOffsetNs;
            if (used) {
                synchronized (this) {
                    ox += wx / ws * scale;
                    oy += wy / ws * scale;
                    framesUsed++;
                    add(t, ox, oy);
                }
            } else {
                framesRejected++;
            }
            if (log != null) {
                try {
                    log.write(t + "," + (float) mdy + "," + (float) pdy + "," + (float) (used ? wy / ws : Double.NaN)
                            + "," + (used ? 1 : 0) + "," + (float) oy + "\n");
                } catch (java.io.IOException e) {
                    log = null;
                }
            }
            if (!used) return;   // keep the last good frame as the reference for the next one
        }
        prevSpec = spec;
        prevTs = ts;
        prevExp = exposure;
        prevSkew = skew;
    }

    private void add(long t, double x, double y) {
        head = (head + 1) % CAP;
        st[head] = t;
        sx[head] = x;
        sy[head] = y;
        if (count < CAP) count++;
        lastSampleT = t;
    }

    /**
     * Shake displacement at time t (raw pixels): the path P minus its centred Gaussian
     * smoothing (zero phase), clamped to a plausible range.
     */
    synchronized void shiftAt(long t, double[] out) {
        double[] p = new double[2];
        pathAt(t, p);
        double half = 3 * smoothSigmaS * 1e9;
        double sxw = 0, syw = 0, sw = 0;
        for (int i = 0; i < count; i++) {
            int k = (head - i + CAP) % CAP;
            double d = st[k] - t;
            if (d > half) continue;
            if (d < -half) break;
            double wgt = Math.exp(-0.5 * (d / (smoothSigmaS * 1e9)) * (d / (smoothSigmaS * 1e9)));
            sxw += wgt * sx[k];
            syw += wgt * sy[k];
            sw += wgt;
        }
        if (sw == 0) {
            out[0] = out[1] = 0;
            return;
        }
        out[0] = Math.max(-maxShiftRaw, Math.min(maxShiftRaw, p[0] - sxw / sw));
        out[1] = Math.max(-maxShiftRaw, Math.min(maxShiftRaw, p[1] - syw / sw));
    }

    /** Accumulated path P at time t (raw pixels), linearly interpolated. */
    private void pathAt(long t, double[] out) {
        out[0] = out[1] = 0;
        if (count == 0) return;
        int oldest = (head - count + 1 + CAP) % CAP;
        if (t <= st[oldest]) { out[0] = sx[oldest]; out[1] = sy[oldest]; return; }
        if (t >= st[head]) { out[0] = sx[head]; out[1] = sy[head]; return; }
        int lo = 0, hi = count - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (st[(oldest + mid) % CAP] <= t) lo = mid; else hi = mid;
        }
        int a = (oldest + lo) % CAP, b = (oldest + hi) % CAP;
        double f = (double) (t - st[a]) / (st[b] - st[a]);
        out[0] = sx[a] + (sx[b] - sx[a]) * f;
        out[1] = sy[a] + (sy[b] - sy[a]) * f;
    }

    /** The extra camera rotation equivalent to an OIS content shift (small angle). */
    void rotationAt(long t, double[] rotVec) {
        double[] o = new double[2];
        shiftAt(t, o);
        rotVec[0] = o[1] / fy;     // vertical shift <- rotation about X
        rotVec[1] = -o[0] / fx;    // horizontal shift <- rotation about Y
        rotVec[2] = 0;
    }

    private static double median(double[] v) {
        double a = v[0], b = v[1], c = v[2];
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    // ---------------------------------------------------------------- phase correlation

    private double[][] stripSpectrum(byte[] img, int y0) {
        double[] re = new double[FW * FH], im = new double[FW * FH];
        int x0 = (w - FW) / 2;
        for (int y = 0; y < FH; y++) {
            double wy = 0.5 - 0.5 * Math.cos(2 * Math.PI * y / (FH - 1));
            for (int x = 0; x < FW; x++) {
                double wx = 0.5 - 0.5 * Math.cos(2 * Math.PI * x / (FW - 1));
                re[y * FW + x] = (img[(y0 + y) * w + x0 + x] & 255) * wx * wy;
            }
        }
        fft2(re, im, FW, FH, false);
        return new double[][]{re, im};
    }

    /** Content shift of b relative to a (vision px), and the correlation peak height. */
    static double[] phaseCorrelate(double[][] a, double[][] b) {
        int n = FW * FH;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) {
            double r = b[0][i] * a[0][i] + b[1][i] * a[1][i];
            double c = b[1][i] * a[0][i] - b[0][i] * a[1][i];
            double m = Math.hypot(r, c) + 1e-9;
            re[i] = r / m;
            im[i] = c / m;
        }
        fft2(re, im, FW, FH, true);
        int best = 0;
        for (int i = 1; i < n; i++) if (re[i] > re[best]) best = i;
        int bx = best % FW, by = best / FW;
        double fxp = bx + parabola(re[by * FW + (bx - 1 + FW) % FW], re[best], re[by * FW + (bx + 1) % FW]);
        double fyp = by + parabola(re[((by - 1 + FH) % FH) * FW + bx], re[best], re[((by + 1) % FH) * FW + bx]);
        if (fxp > FW / 2.0) fxp -= FW;
        if (fyp > FH / 2.0) fyp -= FH;
        return new double[]{fxp, fyp, re[best] / n};
    }

    private static double parabola(double l, double c, double r) {
        double d = l - 2 * c + r;
        return Math.abs(d) < 1e-12 ? 0 : 0.5 * (l - r) / d;
    }

    static void fft2(double[] re, double[] im, int w, int h, boolean inverse) {
        double[] rr = new double[Math.max(w, h)], ii = new double[Math.max(w, h)];
        for (int y = 0; y < h; y++) {
            System.arraycopy(re, y * w, rr, 0, w);
            System.arraycopy(im, y * w, ii, 0, w);
            fft(rr, ii, w, inverse);
            System.arraycopy(rr, 0, re, y * w, w);
            System.arraycopy(ii, 0, im, y * w, w);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                rr[y] = re[y * w + x];
                ii[y] = im[y * w + x];
            }
            fft(rr, ii, h, inverse);
            for (int y = 0; y < h; y++) {
                re[y * w + x] = rr[y];
                im[y * w + x] = ii[y];
            }
        }
    }

    private static void fft(double[] re, double[] im, int n, boolean inverse) {
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = i + k + len / 2;
                    double xr = re[v] * cr - im[v] * ci, xi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - xr;
                    im[v] = im[u] - xi;
                    re[u] += xr;
                    im[u] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
