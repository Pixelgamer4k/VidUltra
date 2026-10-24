package dev.vidultra.proto;

import android.opengl.GLES30;
import android.util.Log;

/**
 * Video noise reduction for the stabilized path (see the shader comments in {@link Shaders}):
 *
 *   WARPCUR (full)  stabilizer warp of the current frame + its expected noise
 *   QBOTH   (1/4)   block averages of the current and the aligned previous frame
 *   MASK    (1/4)   block motion test in noise units (catches faint moved edges)
 *   TNR     (full)  temporal average where mask and per-pixel test agree nothing moved
 *   DOWN    (1/4)   block averages of the result
 *   CFILT x2 (1/4)  colour noise reduction, 5x5 then 9x9 blocks (noise-adaptive bilateral)
 *   OUTPUT  (any)   brightness sigma filter at the remaining noise, part of the natural grain
 *                   kept, full-res brightness x filtered colour, local tone, Rec.709 / LogC3
 *
 * Nothing sharpens or boosts detail. Temporal history lives in two ping-pong RGBA16F frames
 * in output space (row = output y, top down), so it follows the stabilized picture.
 */
final class NoiseReducer {
    private static final String TAG = "VIDULTRA";

    // longest temporal history (noise / sqrt(2n-1) when static): adaptive, from bright scenes
    // (little noise to remove, so less lag) to dark ones
    static float minFrames = 4f, maxFrames = 8f;
    static float motionTol = 1.5f;  // per-pixel motion test, in noise sigmas
    static float maskLo = 2.0f, maskHi = 3.0f;   // block motion score (pure noise ~1.3)
    static float lumaK = 1.0f;      // spatial brightness threshold, in remaining-noise sigmas
    static float noiseGain = 1.0f;  // extra factor on the (self-calibrated) noise level
    static float grain = 0.35f;     // share of each frame's own luminance grain kept
    // Motion compensation (block-matching) is an experiment, off by default: its first version
    // smeared texture where block vectors were slightly wrong. With it off this is the approved
    // version: history fades on fast camera moves, bilinear history, fixed clamp width.
    static boolean motionComp = false;
    static float stillPx = 6f, fadePx = 9f;            // used without motion compensation
    static float mcStillPx = 60f, mcFadePx = 40f;      // with it
    static int searchRange = 6;     // motion search, quarter-res pixels (x4 output pixels)
    private boolean motionCleared;
    static int fps = 24;                // for the motion memory's fade time
    static float memorySeconds = 0.3f;  // motion memory: fades to 10% in this time
    private int memProgram;
    private final int[] memTex = new int[2], memFbo = new int[2];
    private int eMask, eMemPrev, eAlign, eVirt, eOutSize, eQMax, eHasPrev, eDecay, tMemory;

    private final int outW, outH, qW, qH;
    private int curProgram, qProgram, sadProgram, pickProgram, maskProgram, tnrProgram, downProgram, cfiltProgram, outProgram;
    private int motionTex, motionFbo, sadTex, sadFbo, mW, mH;
    private int vQCur, vQPrev, vQMax, vRange, kSad, kRange, kQCur, kQMax, mMotion, mQSize, mMSize, tMotion, tMSize;
    private final int[] accTex = new int[2], accFbo = new int[2];
    private int curTex, curFbo, qCurTex, qPrevTex, qFbo, maskTex, maskFbo, sigmaTex;
    private int downTex, downFbo, chromaTex, chromaFbo, chroma2Tex, chroma2Fbo;
    private int cur;
    private boolean hasPrev;
    private double[] prevVirtual;

    // WARPCUR uniforms (geometry ones mirror the WARP program's)
    private int wLog, wBoxOrigin, wTexSize, wBand, wRawH, wLens, wLensSkew, wDist, wVirt, wOutSize,
            wShade, wSensorSz, wShadeSz, wNoise, wSS;
    private int qCur, qPrev, qAlign, qVirt, qOutSize, qHasPrev;
    private int mQCur, mQPrev, mQMax, mLo, mHi, mChan;
    private int tCur, tPrev, tMask, tAlign, tVirt, tOutSize, tHasPrev, tNrMax, tNrTol, tChan, tClampK,
            tClampMoving, tBicubic;
    static float clampK = 2.0f;     // history clamp: neighbourhood range, in its own spread / noise
    private int dAcc, dSigma, dFull, cDown, cQMax, cStep, cChan;
    private int oAcc, oSigma, oChroma, oQSize, oViewOrigin, oViewSize, oOutSize, oFlip, oLumaK,
            oView709, oAwgTo709, oBase, oBaseScale, oBaseMax, oLocal, oGrain, oCurve, oOutGamut;

    NoiseReducer(int outW, int outH) {
        this.outW = outW;
        this.outH = outH;
        this.qW = (outW + 3) / 4;
        this.qH = (outH + 3) / 4;
        this.mW = (qW + 7) / 8;
        this.mH = (qH + 7) / 8;
    }

    /** @return false when this GPU cannot render the float targets (NR then stays off) */
    boolean init(int rawW, int rawH, float[] lens, float[] dist, float[] virt, boolean flip180) {
        curProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.WARPCUR);
        qProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.QBOTH);
        maskProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.MASK);
        sadProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.MOTION_SAD);
        pickProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.MOTION_PICK);
        tnrProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.TNR);
        memProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.MEMORY);
        downProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.DOWN);
        cfiltProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.CFILT);
        outProgram = Engine.buildProgram(Shaders.VERTEX, Shaders.OUTPUT);

        int p = curProgram;
        wLog = loc(p, "uLog");
        wBoxOrigin = loc(p, "uBoxOrigin");
        wTexSize = loc(p, "uTexSize");
        wBand = loc(p, "uBand");
        wRawH = loc(p, "uRawH");
        wLens = loc(p, "uLens");
        wLensSkew = loc(p, "uLensSkew");
        wDist = loc(p, "uDist");
        wVirt = loc(p, "uVirt");
        wOutSize = loc(p, "uOutSize");
        wShade = loc(p, "uShadeW");
        wSensorSz = loc(p, "uSensorSz");
        wShadeSz = loc(p, "uShadeSz");
        wNoise = loc(p, "uNoise");
        wSS = loc(p, "uSS");
        GLES30.glUseProgram(p);
        GLES30.glUniform2f(wTexSize, rawW, rawH);
        GLES30.glUniform1f(wRawH, rawH);
        GLES30.glUniform4f(wLens, lens[0], lens[1], lens[2], lens[3]);
        GLES30.glUniform1f(wLensSkew, 0);
        GLES30.glUniform1fv(wDist, 5, dist, 0);
        GLES30.glUniform3f(wVirt, virt[0], virt[1], virt[2]);
        GLES30.glUniform2f(wOutSize, outW, outH);
        GLES30.glUniform2f(wSensorSz, rawW, rawH);

        p = qProgram;
        qCur = loc(p, "uCur");
        qPrev = loc(p, "uPrev");
        qAlign = loc(p, "uAlign");
        qVirt = loc(p, "uVirt");
        qOutSize = loc(p, "uOutSize");
        qHasPrev = loc(p, "uHasPrev");
        GLES30.glUseProgram(p);
        GLES30.glUniform2f(qOutSize, outW, outH);

        p = sadProgram;
        vQCur = loc(p, "uQCur");
        vQPrev = loc(p, "uQPrev");
        vQMax = loc(p, "uQMax");
        vRange = loc(p, "uRange");
        kSad = loc(pickProgram, "uSad");
        kRange = loc(pickProgram, "uRange");
        kQCur = loc(pickProgram, "uQCur");
        kQMax = loc(pickProgram, "uQMax");

        p = maskProgram;
        mMotion = loc(p, "uMotion");
        mQSize = loc(p, "uQSize");
        mMSize = loc(p, "uMSize");
        mQCur = loc(p, "uQCur");
        mQPrev = loc(p, "uQPrev");
        mQMax = loc(p, "uQMax");
        mLo = loc(p, "uMaskLo");
        mHi = loc(p, "uMaskHi");
        mChan = loc(p, "uChan");

        p = memProgram;
        eMask = loc(p, "uMask");
        eMemPrev = loc(p, "uMemPrev");
        eAlign = loc(p, "uAlign");
        eVirt = loc(p, "uVirt");
        eOutSize = loc(p, "uOutSize");
        eQMax = loc(p, "uQMax");
        eHasPrev = loc(p, "uHasPrev");
        eDecay = loc(p, "uDecay");
        GLES30.glUseProgram(p);
        GLES30.glUniform2f(eOutSize, outW, outH);

        p = tnrProgram;
        tMemory = loc(p, "uMemory");
        tCur = loc(p, "uCur");
        tPrev = loc(p, "uPrev");
        tMask = loc(p, "uMask");
        tAlign = loc(p, "uAlign");
        tVirt = loc(p, "uVirt");
        tOutSize = loc(p, "uOutSize");
        tHasPrev = loc(p, "uHasPrev");
        tNrMax = loc(p, "uNrMax");
        tNrTol = loc(p, "uNrTol");
        tChan = loc(p, "uChan");
        tClampK = loc(p, "uClampK");
        tClampMoving = loc(p, "uClampMoving");
        tBicubic = loc(p, "uBicubic");
        tMotion = loc(p, "uMotion");
        tMSize = loc(p, "uMSize");
        GLES30.glUseProgram(p);
        GLES30.glUniform2f(tOutSize, outW, outH);

        dAcc = loc(downProgram, "uAcc");
        dSigma = loc(downProgram, "uSigma");
        dFull = loc(downProgram, "uFull");
        cDown = loc(cfiltProgram, "uDown");
        cQMax = loc(cfiltProgram, "uQMax");
        cStep = loc(cfiltProgram, "uStep");
        cChan = loc(cfiltProgram, "uChan");

        p = outProgram;
        oAcc = loc(p, "uAcc");
        oSigma = loc(p, "uSigma");
        oChroma = loc(p, "uChroma");
        oQSize = loc(p, "uQSize");
        oViewOrigin = loc(p, "uViewOrigin");
        oViewSize = loc(p, "uViewSize");
        oOutSize = loc(p, "uOutSize");
        oFlip = loc(p, "uFlip");
        oLumaK = loc(p, "uLumaK");
        oView709 = loc(p, "uView709");
        oAwgTo709 = loc(p, "uAwgTo709");
        oBase = loc(p, "uBase");
        oBaseScale = loc(p, "uBaseScale");
        oBaseMax = loc(p, "uBaseMax");
        oLocal = loc(p, "uLocal");
        oGrain = loc(p, "uGrain");
        oCurve = loc(p, "uCurve");
        oOutGamut = loc(p, "uOutGamut");
        GLES30.glUseProgram(p);
        GLES30.glUniform2f(oOutSize, outW, outH);
        GLES30.glUniform2f(oQSize, qW, qH);
        GLES30.glUniform1i(oFlip, flip180 ? 1 : 0);
        GLES30.glUniformMatrix3fv(oAwgTo709, 1, true, ColorMath.awg3ToRec709(), 0);

        boolean ok = true;
        curTex = texture(GLES30.GL_RGBA16F, outW, outH);
        curFbo = fbo(curTex, 0);
        sigmaTex = texture(GLES30.GL_RG16F, outW, outH);
        for (int i = 0; i < 2; i++) {
            accTex[i] = texture(GLES30.GL_RGBA16F, outW, outH);
            accFbo[i] = fbo(accTex[i], sigmaTex);
            ok &= complete(accFbo[i]);
        }
        qCurTex = texture(GLES30.GL_RGBA16F, qW, qH);
        qPrevTex = texture(GLES30.GL_RGBA16F, qW, qH);
        qFbo = fbo(qCurTex, qPrevTex);
        motionTex = texture(GLES30.GL_RGBA16F, mW, mH);
        motionFbo = fbo(motionTex, 0);
        int n = 2 * searchRange + 1;
        sadTex = texture(GLES30.GL_R16F, mW * n, mH * n);
        sadFbo = fbo(sadTex, 0);
        ok &= complete(motionFbo) && complete(sadFbo);
        for (int i = 0; i < 2; i++) {
            memTex[i] = texture(GLES30.GL_RGBA16F, qW, qH);
            memFbo[i] = fbo(memTex[i], 0);
            ok &= complete(memFbo[i]);
        }
        maskTex = texture(GLES30.GL_RGBA16F, qW, qH);
        maskFbo = fbo(maskTex, 0);
        downTex = texture(GLES30.GL_RGBA16F, qW, qH);
        downFbo = fbo(downTex, 0);
        chromaTex = texture(GLES30.GL_RGBA16F, qW, qH);
        chromaFbo = fbo(chromaTex, 0);
        chroma2Tex = texture(GLES30.GL_RGBA16F, qW, qH);
        chroma2Fbo = fbo(chroma2Tex, 0);
        ok &= complete(curFbo) && complete(qFbo) && complete(maskFbo) && complete(downFbo)
                && complete(chromaFbo) && complete(chroma2Fbo);
        Log.i(TAG, "noise reduction " + (ok ? "ready" : "unavailable (float targets incomplete)")
                + ", " + outW + "x" + outH + " history " + minFrames + "-" + maxFrames + " frames");
        return ok;
    }

    void reset() {
        hasPrev = false;
        prevVirtual = null;
    }

    int downTex() {
        return downTex;
    }

    int qW() {
        return qW;
    }

    int qH() {
        return qH;
    }

    /**
     * All passes up to the colour filter for one frame. Leaves the GL framebuffer at 0.
     *
     * @param virtualQuat stabilized virtual camera-to-world orientation of this frame
     * @param noiseS      var(Y) per unit Y in working units (calibrated RAW S x exposure)
     * @param noiseO      constant part of var(Y) in working units
     * @param chan        per-channel noise relative to luminance noise (colour matrix)
     * @param ss          sub-samples per axis for downscaled outputs (1 = none)
     */
    void accumulate(int logTex, int shadeTex, int shadeCols, int shadeRows, int boxX, int boxY,
                    float[] bandMats, float[] virt, double[] virtualQuat, float noiseS, float noiseO,
                    float[] chan, int ss) {
        int prev = 1 - cur;
        float[] align = new float[9];
        boolean usePrev = hasPrev && prevVirtual != null;
        // relative noise at mid grey (sigma / 0.18) picks the history length
        double rel = Math.sqrt(Math.max(0, noiseS * 0.18 + noiseO)) / 0.18;
        double dark = Math.max(0, Math.min(1, (rel - 0.025) / 0.065));
        dark = dark * dark * (3 - 2 * dark);
        float longest = (float) (minFrames + (maxFrames - minFrames) * dark);
        float history = longest;
        if (usePrev) {
            // previous virtual ray = Vprev^T Vcur * current virtual ray
            double[] vp = Quat.toMat(prevVirtual), vc = Quat.toMat(virtualQuat);
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++)
                    align[r * 3 + c] = (float) (vp[r] * vc[c] + vp[3 + r] * vc[3 + c] + vp[6 + r] * vc[6 + c]);
            // how far the picture moved since the last frame (virtual camera rotation x focal):
            // full history while steady, fading out on fast moves, where alignment is least
            // certain and motion blur hides the noise anyway
            double d = Math.abs(prevVirtual[0] * virtualQuat[0] + prevVirtual[1] * virtualQuat[1]
                    + prevVirtual[2] * virtualQuat[2] + prevVirtual[3] * virtualQuat[3]);
            double shiftPx = 2 * Math.acos(Math.min(1, d)) * virt[0];
            double x = Math.max(0, shiftPx - (motionComp ? mcStillPx : stillPx)) / (motionComp ? mcFadePx : fadePx);
            history = (float) (1 + (longest - 1) * Math.exp(-x * x));
        }

        // 1. current frame, stabilized, with its expected noise
        GLES30.glUseProgram(curProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curFbo);
        GLES30.glViewport(0, 0, outW, outH);
        bind(2, logTex, wLog);
        bind(4, shadeTex, wShade);
        GLES30.glUniform2f(wShadeSz, shadeCols, shadeRows);
        GLES30.glUniform2f(wBoxOrigin, boxX, boxY);
        GLES30.glUniformMatrix3fv(wBand, Stabilizer.BANDS, true, bandMats, 0);
        GLES30.glUniform3f(wVirt, virt[0], virt[1], virt[2]);
        GLES30.glUniform2f(wNoise, noiseS, noiseO);
        GLES30.glUniform1i(wSS, ss);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 2. block averages of current and aligned previous
        GLES30.glUseProgram(qProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, qFbo);
        GLES30.glDrawBuffers(2, new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1}, 0);
        GLES30.glViewport(0, 0, qW, qH);
        bind(5, curTex, qCur);
        bind(6, accTex[prev], qPrev);
        GLES30.glUniformMatrix3fv(qAlign, 1, true, align, 0);
        GLES30.glUniform3f(qVirt, virt[0], virt[1], virt[2]);
        GLES30.glUniform1i(qHasPrev, usePrev ? 1 : 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 2b/2c. where did each 32x32 block's content come from (motion compensation): scores
        // for every candidate in parallel, then the best per block
        if (!motionComp) {
            // the approved version: no motion search at all (zero motion field, no GPU cost)
            if (!motionCleared) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, motionFbo);
                GLES30.glViewport(0, 0, mW, mH);
                GLES30.glClearColor(0, 0, 0, 0);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                motionCleared = true;
            }
        } else {
        motionCleared = false;
        int range = usePrev ? searchRange : 0, n = 2 * range + 1;
        GLES30.glUseProgram(sadProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sadFbo);
        GLES30.glViewport(0, 0, mW * n, mH * n);
        bind(7, qCurTex, vQCur);
        bind(8, qPrevTex, vQPrev);
        GLES30.glUniform2i(vQMax, qW - 1, qH - 1);
        GLES30.glUniform1i(vRange, range);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glUseProgram(pickProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, motionFbo);
        GLES30.glViewport(0, 0, mW, mH);
        bind(9, sadTex, kSad);
        bind(7, qCurTex, kQCur);
        GLES30.glUniform2i(kQMax, qW - 1, qH - 1);
        GLES30.glUniform1i(kRange, range);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        }

        // 3. block motion mask (on the motion-compensated history)
        GLES30.glUseProgram(maskProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, maskFbo);
        GLES30.glViewport(0, 0, qW, qH);
        bind(7, qCurTex, mQCur);
        bind(8, qPrevTex, mQPrev);
        bind(9, motionTex, mMotion);
        GLES30.glUniform2f(mQSize, qW, qH);
        GLES30.glUniform2f(mMSize, mW, mH);
        GLES30.glUniform2i(mQMax, qW - 1, qH - 1);
        GLES30.glUniform1f(mLo, maskLo);
        GLES30.glUniform1f(mHi, maskHi);
        GLES30.glUniform3f(mChan, chan[0], chan[1], chan[2]);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 3b. recent-motion memory (spread out, fading over ~0.3 s)
        GLES30.glUseProgram(memProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, memFbo[cur]);
        GLES30.glViewport(0, 0, qW, qH);
        bind(7, maskTex, eMask);
        bind(8, memTex[prev], eMemPrev);
        GLES30.glUniformMatrix3fv(eAlign, 1, true, align, 0);
        GLES30.glUniform3f(eVirt, virt[0], virt[1], virt[2]);
        GLES30.glUniform2i(eQMax, qW - 1, qH - 1);
        GLES30.glUniform1i(eHasPrev, usePrev ? 1 : 0);
        GLES30.glUniform1f(eDecay, (float) Math.pow(0.1, 1.0 / Math.max(1, memorySeconds * fps)));
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 4. temporal average
        GLES30.glUseProgram(tnrProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, accFbo[cur]);
        GLES30.glDrawBuffers(2, new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1}, 0);
        GLES30.glViewport(0, 0, outW, outH);
        bind(5, curTex, tCur);
        bind(6, accTex[prev], tPrev);
        bind(7, maskTex, tMask);
        bind(10, memTex[cur], tMemory);
        bind(8, motionTex, tMotion);
        GLES30.glUniform2f(tMSize, mW, mH);
        GLES30.glUniformMatrix3fv(tAlign, 1, true, align, 0);
        GLES30.glUniform3f(tVirt, virt[0], virt[1], virt[2]);
        GLES30.glUniform1i(tHasPrev, usePrev ? 1 : 0);
        GLES30.glUniform1f(tNrMax, history);
        GLES30.glUniform1f(tNrTol, motionTol);
        GLES30.glUniform3f(tChan, chan[0], chan[1], chan[2]);
        GLES30.glUniform1f(tClampK, clampK);
        GLES30.glUniform1f(tClampMoving, motionComp ? 1f : clampK);
        GLES30.glUniform1i(tBicubic, motionComp ? 1 : 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 5. block averages of the result
        GLES30.glUseProgram(downProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, downFbo);
        GLES30.glViewport(0, 0, qW, qH);
        bind(5, accTex[cur], dAcc);
        bind(6, sigmaTex, dSigma);
        GLES30.glUniform2f(dFull, outW, outH);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 6. colour noise reduction, 5x5 then 9x9 blocks
        GLES30.glUseProgram(cfiltProgram);
        GLES30.glUniform2i(cQMax, qW - 1, qH - 1);
        GLES30.glUniform3f(cChan, chan[0], chan[1], chan[2]);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, chromaFbo);
        bind(7, downTex, cDown);
        GLES30.glUniform1i(cStep, 1);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, chroma2Fbo);
        bind(7, chromaTex, cDown);
        GLES30.glUniform1i(cStep, 2);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        prevVirtual = virtualQuat.clone();
        hasPrev = true;
        cur = prev;   // next frame writes the other buffer; the one just written is now "prev"
    }

    /**
     * Draws the finished picture into the current surface's viewport.
     *
     * @param awgTo709 AWG3 -> Rec.709 matrix (with the ambience tint), row-major
     * @param curve    log curve (ColorSpaces id) and outGamut AWG3 -> its gamut, when not view709
     */
    void draw(int x, int y, int vw, int vh, boolean view709, float[] awgTo709,
              int baseTex, int bw, int bh, boolean local, int curve, float[] outGamut) {
        int last = 1 - cur;   // accumulate() already flipped cur
        GLES30.glUseProgram(outProgram);
        GLES30.glViewport(x, y, vw, vh);
        GLES30.glUniform2i(oViewOrigin, x, y);
        GLES30.glUniform2f(oViewSize, vw, vh);
        bind(5, accTex[last], oAcc);
        bind(6, sigmaTex, oSigma);
        bind(7, chroma2Tex, oChroma);
        bind(3, baseTex, oBase);
        GLES30.glUniform2f(oBaseScale, bw / (float) outW, bh / (float) outH);
        GLES30.glUniform2i(oBaseMax, bw - 1, bh - 1);
        GLES30.glUniform1i(oLocal, local ? 1 : 0);
        GLES30.glUniform1i(oView709, view709 ? 1 : 0);
        GLES30.glUniformMatrix3fv(oAwgTo709, 1, true, awgTo709, 0);
        GLES30.glUniform1f(oLumaK, lumaK);
        GLES30.glUniform1f(oGrain, grain);
        GLES30.glUniform1i(oCurve, curve);
        GLES30.glUniformMatrix3fv(oOutGamut, 1, true, outGamut, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    private static void bind(int unit, int tex, int uniform) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(uniform, unit);
    }

    private static int texture(int format, int w, int h) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int fbo(int tex0, int tex1) {
        int[] f = new int[1];
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex0, 0);
        if (tex1 != 0) {
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, tex1, 0);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return f[0];
    }

    private static boolean complete(int f) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return st == GLES30.GL_FRAMEBUFFER_COMPLETE;
    }

    private static int loc(int program, String name) {
        return GLES30.glGetUniformLocation(program, name);
    }
}
