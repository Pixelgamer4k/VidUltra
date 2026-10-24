package dev.vidultra.proto;

/**
 * Video auto-exposure that moves like a cinema camera operator instead of stock AE.
 *
 * Everything runs in log2 exposure units ("EV" here = log2 of exposure_ns * ISO).
 *
 *  1. Metering (from the RAW frame, so linear): centre/face-weighted midtone mean, aimed at
 *     0.18 scene-linear, and the 98th-percentile highlight, kept below 0.85 of sensor clip (highlight reconstruction recovers what lies above).
 *     Highlights win; when they force the exposure below what the midtones want (backlight),
 *     the gap becomes a smoothed shadow lift applied in the shader.
 *  2. Gyro-coupled dead-band: still camera = wide dead-band (+-0.25 EV) and a 350 ms hold, so
 *     people crossing the frame do not move the exposure; panning (> 0.14 rad/s) = narrow
 *     dead-band (+-0.08 EV), no hold, so exposure glides with the pan.
 *  3. A critically damped spring toward the target, with asymmetric speed limits: darken fast
 *     (1.8 EV/s, protects highlights), brighten slowly (0.65 EV/s).
 *  4. The hardware gets the spring's value split into shutter (base ISO first, up to the
 *     flicker-safe cap) then ISO. Sensor steps and the 2-3 frame request lag are removed by a
 *     per-frame linear trim: 2^(smooth EV at that frame - EV the frame was actually shot at).
 *  5. Film shutter: while the camera is calm or panning gently the shutter may open to 180
 *     degrees (half the frame time, flicker-safe), the natural motion blur of film and cinema
 *     cameras; while it shakes (walking) it stays at the short cap so stabilized footage shows
 *     no blur smears. Switching has hysteresis; ISO compensates, so brightness does not change.
 */
final class AeController {
    // targets
    static final double MID_TARGET = 0.18;       // scene-linear midtone
    static final double HIGH_CAP = 0.85;          // 98th percentile, fraction of sensor clip
    // dead-band / hold
    double stillDeadband = 0.25, panDeadband = 0.08, holdS = 0.35, panRate = 0.14;
    // spring
    double omega = 6.0;                           // rad/s; critically damped
    double maxDarken = 1.8, maxBrighten = 0.65;   // EV/s, for normal changes
    // Gross errors (sudden dark -> bright, or the reverse) move much faster, like a phone's
    // video AE does: a clipped, >1 EV overexposed frame skips the hold and darkens at up to
    // 6 EV/s. Brightening speeds up smoothly with the size of the gap: small corrections
    // keep the slow glide, coming back from a light source (2-3 EV) recovers in about 1 s.
    double emergencyDarken = 6.0, emergencyBrighten = 2.5;
    // shadow lift
    double liftShare = 0.6;                       // share of the backlight gap given back
    double maxLiftEv = 1.5;
    double liftTauS = 1.0;

    private final double isoMin, isoMax, expMin, expMax, capReqNs;
    private double capNs, flickerNs, filmCapNs;
    private final double sceneScale;              // raw-normalized (after WB) -> scene-linear
    private final double evLo, evHi;              // what the sensor can deliver (+ a little trim)
    boolean filmShutter = true;
    volatile boolean locked;                      // AE lock: exposure held where it is
    volatile double bias;                         // exposure compensation (EV), from the UI slider
    volatile boolean spot;                        // a tapped point: the subject wins over highlights
    double calmRate = 0.35, shakeRate = 0.7, calmHoldS = 0.5;   // rad/s, s
    private boolean calm;
    private double calmSince = -1;

    private boolean started;
    private double ev, vel;                       // spring state (log2 of exposure_ns * iso)
    private double target;                        // current goal
    private double pendingSince = -1;             // when the error first left the dead-band
    private double liftEv;                        // smoothed shadow lift (EV)
    private double lastT = -1;
    private long reqExp;
    private int reqIso;

    // history of the smooth EV by frame time, for the trim at render time
    private static final int CAP = 512;
    private final long[] ht = new long[CAP];
    private final double[] hev = new double[CAP];
    private int hHead = -1, hCount;

    /**
     * @param capNs     longest shutter wanted (motion blur limit)
     * @param flickerHz 50 or 60 (exposures >= one half-cycle are kept to whole half-cycles so
     *                  mains lighting does not flicker), 0 = no anti-flicker
     */
    AeController(int isoMin, int isoMax, long expMin, long expMax, long capNs, int flickerHz, double sceneScale) {
        this.isoMin = isoMin;
        this.isoMax = isoMax;
        this.expMin = expMin;
        this.expMax = expMax;
        this.capReqNs = capNs;
        this.sceneScale = sceneScale;
        setFlicker(flickerHz);
        // anti-windup: the smooth value never runs past what the hardware can reach (plus half
        // a stop of shader trim), so leaving a pitch-dark or blinding scene does not jolt
        this.evLo = Math.log(expMin * (double) isoMin) / Math.log(2) - 0.5;
        this.evHi = Math.log(expMax * (double) isoMax) / Math.log(2) + 0.5;
    }

    /** Mains frequency to stay safe from (50 / 60 Hz, 0 = none): shutter caps snap to half-cycles. */
    void setFlicker(int flickerHz) {
        flickerNs = flickerHz > 0 ? 1e9 / (2.0 * flickerHz) : 0;
        // the cap itself must be flicker-safe: the longest whole half-cycle that fits it
        double cap = capReqNs;
        if (flickerNs > 0 && cap >= flickerNs) cap = Math.floor(cap / flickerNs) * flickerNs;
        capNs = Math.min(cap, expMax);
        // expMax is one frame time; 180 degrees is half of it
        double film = expMax / 2.0;
        if (flickerNs > 0 && film >= flickerNs) film = Math.floor(film / flickerNs) * flickerNs;
        filmCapNs = Math.max(capNs, film);
    }

    long requestExposureNs() {
        return reqExp;
    }

    int requestIso() {
        return reqIso;
    }

    boolean started() {
        return started;
    }

    /** Shadow lift gain for the shader (1 = none). */
    double liftGain() {
        return Math.pow(2, liftEv);
    }

    void reset() {
        started = false;
        calm = false;
        calmSince = -1;
        hCount = 0;
        hHead = -1;
        liftEv = 0;
        pendingSince = -1;
        lastT = -1;
    }

    /**
     * One metered frame.
     *
     * @param t        frame time (s)
     * @param midRaw   centre/face-weighted mean of white-balanced RAW luminance (0..1 of clip)
     * @param p98Raw   98th percentile of the same (0..1 of clip)
     * @param frameEv  log2(exposure_ns * iso) this frame was actually shot at
     * @param gyroRate camera angular speed (rad/s)
     * @return true if the hardware request changed
     */
    boolean onFrame(double t, long tsNs, double midRaw, double p98Raw, double frameEv, double gyroRate) {
        double dt = lastT < 0 ? 1.0 / 24 : Math.max(1e-3, Math.min(0.2, t - lastT));
        lastT = t;
        // EV this frame would need: midtones at 0.18, highlights below the cap; highlights win
        double needMid = Math.log(MID_TARGET / Math.max(1e-6, midRaw * sceneScale)) / Math.log(2);
        double needHigh = Math.log(HIGH_CAP / Math.max(1e-6, p98Raw)) / Math.log(2);
        // a tapped subject takes priority (like a phone camera): highlights may clip 1.5 EV sooner
        double need = Math.min(needMid, needHigh + (spot ? 1.5 : 0));
        double goal = Math.max(evLo, Math.min(evHi, frameEv + need + bias));
        if (!started) {
            started = true;
            ev = frameEv;
            vel = 0;
            target = goal;
        }

        // backlight: midtones wanted more than the highlights allow -> lift them in the shader
        double gap = Math.max(0, needMid - need);
        double lw = 1 - Math.exp(-dt / liftTauS);
        liftEv += (Math.min(maxLiftEv, liftShare * gap) - liftEv) * lw;

        // AE lock: keep the exposure where it is (the spring settles on the held target)
        if (locked) goal = target;
        // dead-band and hold, coupled to the gyro
        boolean panning = gyroRate > panRate;
        boolean blownOut = !locked && p98Raw >= 0.98 && goal < target - 1.0;
        if (blownOut) {
            target = goal;          // no hold when the picture is burning out
            pendingSince = -1;
        }
        double band = panning ? panDeadband : stillDeadband;
        double hold = panning ? 0 : holdS;
        if (Math.abs(goal - target) > band) {
            if (pendingSince < 0) pendingSince = t;
            if (t - pendingSince >= hold) {
                target = goal;
                pendingSince = -1;
            }
        } else {
            pendingSince = -1;
            if (panning) target = goal;   // glide continuously while panning
        }

        // critically damped spring with asymmetric speed limits (faster for gross errors)
        double err = target - ev;
        double w = err < -1.0 ? omega * 1.8 : err > 1.0 ? omega * 1.4 : omega;
        double acc = w * w * err - 2 * w * vel;
        vel += acc * dt;
        double dark = err < -1.0 ? maxDarken + (emergencyDarken - maxDarken) * Math.min(1, (-err - 1) / 2) : maxDarken;
        double gb = Math.max(0, Math.min(1, (err - 0.75) / 1.75));
        double bright = maxBrighten + (emergencyBrighten - maxBrighten) * gb * gb * (3 - 2 * gb);
        vel = Math.max(-dark, Math.min(bright, vel));
        ev += vel * dt;
        if (ev > evHi) { ev = evHi; vel = Math.min(vel, 0); }
        if (ev < evLo) { ev = evLo; vel = Math.max(vel, 0); }
        record(tsNs, ev);

        // film shutter: open up only after the camera has been calm for a moment, close at once
        if (gyroRate > shakeRate) {
            calm = false;
            calmSince = -1;
        } else if (gyroRate < calmRate) {
            if (calmSince < 0) calmSince = t;
            if (t - calmSince >= calmHoldS) calm = true;
        } else {
            calmSince = -1;
        }

        // hardware request; lean 0.15 EV darker so the shader trim mostly brightens (a trim
        // below 1 would turn clipped highlights grey)
        return split(ev - 0.15);
    }

    /** Base ISO first, shutter up to the flicker-safe cap, then ISO. */
    private boolean split(double evReq) {
        double p = Math.pow(2, evReq);
        double cap = filmShutter && calm ? filmCapNs : capNs;
        double e = Math.max(expMin, Math.min(cap, p / isoMin));
        double s = p / e;
        if (s > isoMax) {
            s = isoMax;
            e = Math.min(expMax, p / isoMax);     // only past max ISO does the shutter grow
        }
        if (flickerNs > 0 && e >= flickerNs) e = Math.max(flickerNs, Math.floor(e / flickerNs) * flickerNs);
        s = Math.max(isoMin, Math.min(isoMax, p / e));
        long ne = (long) e;
        int ns = (int) Math.round(s);
        boolean changed = Math.abs(ne - reqExp) > reqExp / 100 || Math.abs(ns - reqIso) > reqIso / 100;
        reqExp = ne;
        reqIso = ns;
        return changed;
    }

    private void record(long ts, double v) {
        hHead = (hHead + 1) % CAP;
        ht[hHead] = ts;
        hev[hHead] = v;
        if (hCount < CAP) hCount++;
    }

    /** Linear gain for the frame at ts shot at frameEv: smooth EV minus hardware EV. */
    double trimGain(long ts, double frameEv) {
        if (hCount == 0) return 1;
        double best = hev[hHead];
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < hCount; i++) {
            int k = (hHead - i + CAP) % CAP;
            long d = Math.abs(ht[k] - ts);
            if (d < bestD) {
                bestD = d;
                best = hev[k];
            }
            if (ht[k] < ts - 1_000_000_000L) break;
        }
        double residual = Math.max(-1, Math.min(1, best - frameEv));
        return Math.pow(2, residual);
    }
}
