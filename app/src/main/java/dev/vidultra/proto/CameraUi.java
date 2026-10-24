package dev.vidultra.proto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The viewfinder, laid out like a pro camera app in landscape: a black control bar on each
 * side and the picture fitted between them (never under a control). Left bar: labelled
 * toggles. Right bar: settings, shutter, look, gallery. Over the top of the picture, a readout
 * bar (format, shutter, ISO, white balance, exposure bias, timer, recording time left,
 * battery); inside it, focus / exposure, AE/AF lock, grid, histogram and audio meter. Motion
 * uses springs ({@link Spring}); the layout follows the camera cut-out.
 */
final class CameraUi extends View implements Choreographer.FrameCallback {
    interface Callbacks {
        void onShutter();

        void onGallery();

        void onSettings();

        void onLiveView(boolean on);

        void onLock(boolean on);

        void onStabilization(boolean on);

        void onNoiseReduction(boolean on);

        void onToneMapping(boolean on);

        void onLookToggle();

        void onResolutionCycle();

        void onFpsCycle();

        /** nx, ny: 0..1 of the picture; lock = long press (AE/AF lock) */
        void onFocus(float nx, float ny, boolean lock);

        void onExposureBias(float ev);

        /** A lens (camera id) was chosen from the lens picker. */
        void onLens(String id);

        /** The bars' sizes: the picture is drawn in the space they leave. */
        void onPadding(int left, int right, int bottom);
    }

    private static final int YELLOW = 0xFFFFD60A, RED = 0xFFFF3B30, WHITE = 0xFFFFFFFF,
            GREY = 0xFF98989F, TILE = 0xFF1C1C1E, TILE_ON = 0xFF2C2C2E, BAR = 0xFF000000;

    private final Callbacks cb;
    private final float dp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Matrix thumbMatrix = new Matrix();
    private final Path path = new Path();

    // state
    private boolean recording, live, locked, stab = true, nr = true, tone = true, grid, histOn = true, audioOn = true;
    private String look = "Rec.709", res = "4K";
    private int fps = 24, outW = 3840, outH = 2160;
    private long recStart;
    private Bitmap thumb;
    private int insetL, insetR, insetT, insetB;
    private Engine.Readout readout;
    private int storageMin = -1, battery = -1;
    private boolean charging;

    // animation
    private final Spring shutterMorph = new Spring(0.35, 0.8, 0);
    private final Spring shutterPress = new Spring(0.25, 0.6, 1);
    private final Spring[] press = new Spring[12];
    private final Spring[] glow = new Spring[5];          // toggle on/off cross-fade
    private final Spring focusScale = new Spring(0.32, 0.72, 1);
    private final Spring focusAlpha = new Spring(0.3, 1, 0);
    private final Spring timerRed = new Spring(0.3, 1, 0);
    private final Spring thumbPop = new Spring(0.4, 0.65, 1);
    private final Spring sunOffset = new Spring(0.18, 1, 0);
    private final Spring audioLevel = new Spring(0.12, 1, 0);
    private final Spring hudAlpha = new Spring(0.3, 1, 0);
    private final Spring lensMenuA = new Spring(0.3, 0.82, 0);
    private java.util.List<DeviceSetup.Lens> lenses = new java.util.ArrayList<>();
    private String lensId;
    private boolean lensMenu;
    private final Spring cover = new Spring(0.32, 1, 1);          // loading cover over the picture
    private final Spring levelAlpha = new Spring(0.3, 1, 0);
    private String hudText, switchLabel;
    private float levelDeg = Float.NaN;
    private final Runnable hideHud = () -> { hudAlpha.to(0); kick(); };
    private long lastFrameNs;
    private boolean animating;

    // focus
    private float focusPx = -1, focusPy = -1;
    private float bias;
    private long focusShownAt;

    // touch
    private int pressed = -1;
    private float downX, downY, biasAtDown;
    private boolean dragBias, longFired;
    private final Runnable longPress = () -> {
        if (pressed != CameraUi.PICTURE) return;
        longFired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        focusTap(downX, downY, true);
    };

    private static final int T_LIVE = 0, T_LOCK = 1, T_STAB = 2, T_NR = 3, T_TONE = 4,
            B_SHUTTER = 5, B_GALLERY = 6, B_SETTINGS = 7, B_LOOK = 8, B_RES = 9, B_FPS = 10, B_LENS = 11,
            MENU_ROW = 200, PICTURE = 100;
    private static final String[] TILE_LABELS = {"LIVE", "AE/AF", "STAB", "NR", "TONE"};

    CameraUi(Context c, Callbacks cb) {
        super(c);
        this.cb = cb;
        dp = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < press.length; i++) press[i] = new Spring(0.28, 0.55, 1);
        for (int i = 0; i < glow.length; i++) glow[i] = new Spring(0.3, 1, 0);
        value.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        value.setTextAlign(Paint.Align.CENTER);
        value.setFontFeatureSettings("tnum");
        value.setColor(WHITE);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        label.setTextAlign(Paint.Align.CENTER);
        label.setLetterSpacing(0.06f);
        label.setColor(GREY);
        setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(android.view.WindowInsets.Type.displayCutout()
                    | android.view.WindowInsets.Type.navigationBars());
            insetL = s.left;
            insetR = s.right;
            insetT = s.top;
            insetB = s.bottom;
            reportPadding();
            invalidate();
            return ins;
        });
        syncGlow(true);
    }

    // ------------------------------------------------------------------ state from the app

    void setRecording(boolean r) {
        recording = r;
        if (r) recStart = SystemClock.elapsedRealtime();
        shutterMorph.to(r ? 1 : 0);
        timerRed.to(r ? 1 : 0);
        kick();
    }

    void setLive(boolean on) { live = on; syncGlow(false); }
    void setStab(boolean on) { stab = on; syncGlow(false); }
    void setNr(boolean on) { nr = on; syncGlow(false); }
    void setTone(boolean on) { tone = on; syncGlow(false); }
    void setGrid(boolean on) { grid = on; invalidate(); }
    void setHistogram(boolean on) { histOn = on; invalidate(); }
    void setAudioMeter(boolean on) { audioOn = on; invalidate(); }
    void setLook(String l) { look = l; invalidate(); }

    void setLenses(java.util.List<DeviceSetup.Lens> list, String current) {
        lenses = list;
        lensId = current;
        invalidate();
    }

    private DeviceSetup.Lens currentLens() {
        for (DeviceSetup.Lens l : lenses) if (l.id.equals(lensId)) return l;
        return null;
    }

    void setFormat(String res, int fps, int outW, int outH) {
        this.res = res;
        this.fps = fps;
        this.outW = outW;
        this.outH = outH;
        invalidate();
    }

    void setLocked(boolean on) {
        locked = on;
        syncGlow(false);
        if (on && focusPx >= 0) {
            focusShownAt = SystemClock.elapsedRealtime();
            focusAlpha.to(1);
            focusScale.velocity = -3;
        }
        kick();
    }

    void setThumbnail(Bitmap b) {
        thumb = b;
        thumbPop.snap(0.55);
        thumbPop.to(1);
        kick();
    }

    void setReadout(Engine.Readout r) {
        readout = r;
        audioLevel.to(Math.max(0, Math.min(1, (r.audioDb + 60) / 60)));
        kick();
    }

    void setDeviceStatus(int storageMinutes, int batteryPct, boolean charging) {
        storageMin = storageMinutes;
        battery = batteryPct;
        this.charging = charging;
        invalidate();
    }

    /** A short status message under the readout row ("Noise Reduction Off"), fading out. */
    void showHud(String text) {
        hudText = text;
        hudAlpha.to(1);
        removeCallbacks(hideHud);
        postDelayed(hideHud, 1400);
        kick();
    }

    /** Loading cover over the picture while the camera (re)starts; null lifts it. */
    void setSwitching(String label) {
        if (label != null) switchLabel = label;
        cover.to(label != null ? 1 : 0);
        kick();
    }

    boolean isSwitching() {
        return cover.target > 0.5;
    }

    /** Horizon tilt on screen (degrees, NaN = unknown); shown with the grid like a phone level. */
    void setLevel(float deg) {
        levelDeg = deg;
        if (grid) invalidate();
    }

    void hideFocus() {
        focusAlpha.to(0);
        bias = 0;
        sunOffset.to(0);
        kick();
    }

    private void syncGlow(boolean snap) {
        boolean[] on = {live, locked, stab, nr, tone};
        for (int i = 0; i < glow.length; i++) {
            if (snap) glow[i].snap(on[i] ? 1 : 0); else glow[i].to(on[i] ? 1 : 0);
        }
        kick();
    }

    // ------------------------------------------------------------------ layout

    private float leftBar() { return insetL + 84 * dp; }
    private float rightBar() { return insetR + 112 * dp; }
    private float leftX() { return insetL + 42 * dp; }
    private float rightX() { return getWidth() - insetR - 56 * dp; }
    private float midY() { return getHeight() / 2f; }
    private float settingsY() { return insetT + 46 * dp; }
    private float lookY() { return midY() + 74 * dp; }
    private float galleryY() { return getHeight() - insetB - 46 * dp; }
    /** Lens button: between the settings button and the shutter. */
    private float lensY() { return (settingsY() + 22 * dp + midY() - 40 * dp) / 2; }

    /** Lens picker rows, to the left of the right bar, centred on the lens button. */
    private RectF menuRow(int k) {
        float rowH = 44 * dp, w = 176 * dp, right = getWidth() - rightBar() - 10 * dp;
        float top = lensY() - 22 * dp;
        top = Math.min(top, getHeight() - insetB - 12 * dp - rowH * lenses.size());
        return new RectF(right - w, top + rowH * k, right, top + rowH * (k + 1));
    }

    /** Toggle tiles, evenly over the left bar's height. */
    private float tileY(int i) {
        float top = insetT + 34 * dp, bottom = getHeight() - insetB - 34 * dp;
        float gap = Math.min(66 * dp, (bottom - top) / 4f);
        return midY() + (i - 2) * gap;
    }

    /** Status bar along the bottom, below the picture. */
    private float bottomBar() { return insetB + 42 * dp; }

    private void reportPadding() {
        cb.onPadding(Math.round(leftBar()), Math.round(rightBar()), Math.round(bottomBar()));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        reportPadding();
    }

    /** The picture rectangle: exactly the engine's preview viewport. */
    private RectF picture() {
        int[] v = Engine.fitViewport(getWidth(), getHeight(), Math.round(leftBar()), Math.round(rightBar()),
                Math.round(bottomBar()), outW, outH);
        // GL's origin is bottom-left; flip to view coordinates
        return new RectF(v[0], getHeight() - v[1] - v[3], v[0] + v[2], getHeight() - v[1]);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        RectF pic = picture();
        // the bars: solid black, the picture never runs under them
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(BAR);
        c.drawRect(0, 0, pic.left, getHeight(), p);
        c.drawRect(pic.right, 0, getWidth(), getHeight(), p);
        c.drawRect(pic.left, 0, pic.right, pic.top, p);
        c.drawRect(pic.left, pic.bottom, pic.right, getHeight(), p);

        if (grid) {
            drawGrid(c, pic);
            drawLevel(c, pic);
        }
        drawCover(c, pic);
        roundCorners(c, pic);
        drawFocus(c, pic);
        drawReadoutBar(c, pic);
        if (histOn && readout != null) drawHistogram(c, pic);
        drawTiles(c);
        drawRight(c);
        drawLensMenu(c);
        if (recording) postInvalidateDelayed(250);
    }

    /** Rounded picture corners (masked with the bar colour). */
    private void roundCorners(Canvas c, RectF pic) {
        float r = 18 * dp;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(pic, Path.Direction.CW);
        path.addRoundRect(pic, r, r, Path.Direction.CW);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(BAR);
        c.drawPath(path, p);
        path.setFillType(Path.FillType.WINDING);
    }

    /** Loading: the picture dims to black with an iOS-style activity indicator and the format. */
    private void drawCover(Canvas c, RectF pic) {
        float a = Math.max(0, Math.min(1, cover.f()));
        if (a < 0.01f) return;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0C);
        p.setAlpha((int) (255 * a));
        c.drawRect(pic, p);
        p.setAlpha(255);
        float cx = pic.centerX(), cy = pic.centerY() - 10 * dp;
        long t = SystemClock.uptimeMillis();
        int spokes = 12, lead = (int) ((t / 80) % spokes);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3 * dp);
        for (int k = 0; k < spokes; k++) {
            double ang = Math.PI * 2 * k / spokes - Math.PI / 2;
            int age = (lead - k + spokes) % spokes;
            p.setColor(WHITE);
            p.setAlpha((int) (a * 255 * Math.max(0.15, 1 - age / (double) spokes)));
            float r0 = 9 * dp, r1 = 16 * dp;
            c.drawLine(cx + (float) Math.cos(ang) * r0, cy + (float) Math.sin(ang) * r0,
                    cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1, p);
        }
        p.setAlpha(255);
        if (switchLabel != null) {
            value.setTextSize(14 * dp);
            value.setAlpha((int) (255 * a));
            c.drawText(switchLabel, cx, cy + 40 * dp, value);
            value.setAlpha(255);
        }
        if (a > 0.01f) postInvalidateOnAnimation();
    }

    /** Level: side marks fixed, the centre bar follows the horizon; yellow when level. */
    private void drawLevel(Canvas c, RectF pic) {
        if (Float.isNaN(levelDeg) || Math.abs(levelDeg) > 30) return;
        boolean level = Math.abs(levelDeg) < 1f;
        float cx = pic.centerX(), cy = pic.centerY();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(1.6f * dp);
        p.setColor(level ? YELLOW : 0xCCFFFFFF);
        if (!level) {
            c.drawLine(cx - 70 * dp, cy, cx - 50 * dp, cy, p);
            c.drawLine(cx + 50 * dp, cy, cx + 70 * dp, cy, p);
        }
        c.save();
        c.rotate(level ? 0 : levelDeg, cx, cy);
        c.drawLine(cx - (level ? 70 : 42) * dp, cy, cx + (level ? 70 : 42) * dp, cy, p);
        c.restore();
    }

    private void drawGrid(Canvas c, RectF r) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, 0.6f * dp));
        p.setColor(0x55FFFFFF);
        for (int i = 1; i <= 2; i++) {
            float x = r.left + r.width() * i / 3, y = r.top + r.height() * i / 3;
            c.drawLine(x, r.top, x, r.bottom, p);
            c.drawLine(r.left, y, r.right, y, p);
        }
    }

    private void drawTiles(Canvas c) {
        float x = leftX(), s = 23 * dp;
        boolean[] on = {live, locked, stab, nr, tone};
        for (int i = 0; i < 5; i++) {
            float y = tileY(i), sc = press[i].f(), g = glow[i].f();
            boolean disabled = i == T_STAB && recording;
            int tile = SettingsSheet.blend(TILE, TILE_ON, g);
            c.save();
            c.scale(sc, sc, x, y);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(tile);
            rf.set(x - 27 * dp, y - 27 * dp, x + 27 * dp, y + 27 * dp);
            c.drawRoundRect(rf, 14 * dp, 14 * dp, p);
            int col = disabled ? 0x66FFFFFF : SettingsSheet.blend(WHITE, YELLOW, g);
            Icons.stroke(p, s, col);
            float iy = y - 6 * dp;
            switch (i) {
                case T_LIVE: Icons.eye(c, p, x, iy, s, on[i], tile); break;
                case T_LOCK: Icons.lock(c, p, x, iy, s, on[i], tile); break;
                case T_STAB: Icons.gyroscope(c, p, x, iy, s, on[i]); break;
                case T_NR: Icons.sparkles(c, p, x, iy, s, on[i]); break;
                default: Icons.tone(c, p, x, iy, s, on[i]); break;
            }
            label.setTextSize(9.5f * dp);
            label.setColor(disabled ? 0x66FFFFFF : SettingsSheet.blend(GREY, YELLOW, g));
            c.drawText(TILE_LABELS[i], x, y + 17 * dp, label);
            c.restore();
        }
        label.setColor(GREY);
    }

    private void drawRight(Canvas c) {
        float x = rightX(), y = midY();
        // settings
        float sy = settingsY(), sc = press[B_SETTINGS].f();
        c.save();
        c.scale(sc, sc, x, sy);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(TILE);
        c.drawCircle(x, sy, 22 * dp, p);
        Icons.stroke(p, 25 * dp, recording ? 0x66FFFFFF : WHITE);
        Icons.gear(c, p, x, sy, 25 * dp);
        c.restore();

        // lens button (only when there is more than one lens)
        DeviceSetup.Lens cl = currentLens();
        if (lenses.size() > 1 && cl != null) {
            float ly2 = lensY(), ls2 = press[B_LENS].f();
            c.save();
            c.scale(ls2, ls2, x, ly2);
            String lt2 = cl.front ? "FRONT" : cl.label();
            value.setTextSize(12.5f * dp);
            float w2 = Math.max(60 * dp, value.measureText(lt2) + 26 * dp);
            rf.set(x - w2 / 2, ly2 - 15 * dp, x + w2 / 2, ly2 + 15 * dp);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(lensMenu ? TILE_ON : TILE);
            c.drawRoundRect(rf, 15 * dp, 15 * dp, p);
            value.setColor(lensMenu ? YELLOW : WHITE);
            value.setAlpha(recording ? 120 : 255);
            c.drawText(lt2, x, ly2 + 4.5f * dp, value);
            value.setColor(WHITE);
            value.setAlpha(255);
            c.restore();
        }

        // shutter: white ring, red disc that morphs to a rounded square while recording
        float ps = shutterPress.f();
        c.save();
        c.scale(ps, ps, x, y);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f * dp);
        p.setColor(WHITE);
        c.drawCircle(x, y, 36 * dp, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(RED);
        float m = shutterMorph.f();
        float half = lerp(30 * dp, 14 * dp, m), radius = lerp(30 * dp, 5 * dp, m);
        rf.set(x - half, y - half, x + half, y + half);
        c.drawRoundRect(rf, radius, radius, p);
        c.restore();

        // look pill (tap: Rec.709 <-> log)
        boolean isLog = !look.equals("Rec.709");
        value.setTextSize(12.5f * dp);
        String lt = look.toUpperCase(Locale.US);
        float lw = Math.min(100 * dp, Math.max(64 * dp, value.measureText(lt) + 22 * dp)), ly = lookY(), ls = press[B_LOOK].f();
        c.save();
        c.scale(ls, ls, x, ly);
        rf.set(x - lw / 2, ly - 14 * dp, x + lw / 2, ly + 14 * dp);
        p.setColor(isLog ? YELLOW : TILE);
        c.drawRoundRect(rf, 14 * dp, 14 * dp, p);
        value.setColor(isLog ? 0xFF000000 : WHITE);
        value.setAlpha(recording ? 150 : 255);
        c.drawText(lt, x, ly + 4.5f * dp, value);
        value.setColor(WHITE);
        value.setAlpha(255);
        c.restore();

        // gallery thumbnail
        float gy = galleryY(), g = 23 * dp * press[B_GALLERY].f() * thumbPop.f();
        rf.set(x - g, gy - g, x + g, gy + g);
        if (thumb != null) {
            BitmapShader sh = new BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = Math.max(rf.width() / thumb.getWidth(), rf.height() / thumb.getHeight());
            thumbMatrix.reset();
            thumbMatrix.setScale(s, s);
            thumbMatrix.postTranslate(rf.centerX() - thumb.getWidth() * s / 2, rf.centerY() - thumb.getHeight() * s / 2);
            sh.setLocalMatrix(thumbMatrix);
            p.setShader(sh);
            c.drawRoundRect(rf, 10 * dp, 10 * dp, p);
            p.setShader(null);
        } else {
            p.setColor(TILE);
            c.drawRoundRect(rf, 10 * dp, 10 * dp, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * dp);
        p.setColor(0x4DFFFFFF);
        c.drawRoundRect(rf, 10 * dp, 10 * dp, p);
    }

    // ---------------------------------------------------------------- status bar
    // The readouts live in a black bar below the picture (between the side bars), so the
    // picture stays clean. Slots are sized from the bar, not the picture, so any aspect ratio
    // lays out the same; on narrow screens the least important readouts drop out.

    private static final int R_RES = 0, R_FPS = 1, R_SHUTTER = 2, R_ISO = 3, R_WB = 4, R_EV = 5, R_MIN = 6, R_BAT = 7;

    private float rowY() {
        return getHeight() - insetB - 21 * dp;
    }

    private float timerWidth() {
        value.setTextSize(15 * dp);
        return value.measureText("00:00:00") + 58 * dp;   // dot + time + audio meter
    }

    /** Slot rectangles for the readouts; null where one does not fit. */
    private RectF[] rowLayout() {
        float x0 = leftBar() + 4 * dp, x1 = getWidth() - rightBar() - 4 * dp;
        float tw = timerWidth(), y = rowY();
        int perSide = 4;
        float slot = 0;
        while (perSide > 2) {
            slot = ((x1 - x0) / 2 - tw / 2) / perSide;
            if (slot >= 46 * dp) break;
            perSide--;
        }
        slot = ((x1 - x0) / 2 - tw / 2) / perSide;
        float w = Math.min(slot - 4 * dp, 74 * dp), h = 34 * dp;
        RectF[] r = new RectF[8];
        int[] left = {R_RES, R_FPS, R_SHUTTER, R_ISO};
        int[] right = {R_WB, R_EV, R_MIN, R_BAT};
        for (int k = 0; k < perSide; k++) {
            float lc = x0 + slot * (k + 0.5f), rc = x1 - slot * (perSide - k - 0.5f);
            r[left[k]] = new RectF(lc - w / 2, y - h / 2, lc + w / 2, y + h / 2);
            r[right[k]] = new RectF(rc - w / 2, y - h / 2, rc + w / 2, y + h / 2);
        }
        return r;
    }

    private RectF timerRect() {
        float x0 = leftBar() + 4 * dp, x1 = getWidth() - rightBar() - 4 * dp;
        float cx = (x0 + x1) / 2, tw = timerWidth(), y = rowY();
        return new RectF(cx - tw / 2, y - 15 * dp, cx + tw / 2, y + 15 * dp);
    }

    private void drawReadoutBar(Canvas c, RectF pic) {
        Engine.Readout r = readout;
        String shutter = "—", angle = "SHUTTER", iso = "—", wb = "—", ev = "0.0";
        if (r != null && r.exposureNs > 0) {
            double sec = r.exposureNs / 1e9;
            shutter = sec >= 0.25 ? String.format(Locale.US, "%.1f\"", sec) : "1/" + Math.round(1 / sec);
            angle = Math.round(r.shutterAngle) + "°";
            iso = String.valueOf(r.iso);
            wb = r.cctK > 0 ? String.valueOf(r.cctK) : "—";
            ev = Math.abs(r.evBias) < 0.05f ? "0.0" : String.format(Locale.US, "%+.1f", r.evBias);
        }
        RectF[] lay = rowLayout();
        if (lay[R_RES] != null) chip(c, lay[R_RES], B_RES, res, "RES");
        if (lay[R_FPS] != null) chip(c, lay[R_FPS], B_FPS, String.valueOf(fps), "FPS");
        if (lay[R_SHUTTER] != null) readout(c, lay[R_SHUTTER], shutter, angle, 0);
        if (lay[R_ISO] != null) readout(c, lay[R_ISO], iso, "ISO", 0);
        if (lay[R_WB] != null) readout(c, lay[R_WB], wb, "KELVIN", 0);
        if (lay[R_EV] != null) readout(c, lay[R_EV], ev, locked ? "AE LOCK" : "EV",
                locked || Math.abs(bias) >= 0.05f ? YELLOW : 0);
        if (lay[R_MIN] != null) {
            String m = storageMin < 0 ? "—" : storageMin >= 600 ? "10h+" : String.valueOf(storageMin);
            readout(c, lay[R_MIN], m, "MIN", storageMin >= 0 && storageMin < 5 ? RED : 0);
        }
        if (lay[R_BAT] != null) {
            boolean low = battery >= 0 && battery <= 15 && !charging;
            RectF b = lay[R_BAT];
            value.setTextSize(13 * dp);
            value.setColor(low ? 0xFFFF6961 : WHITE);
            c.drawText(battery < 0 ? "—" : battery + "%", b.centerX(), b.centerY() - 1 * dp, value);
            value.setColor(WHITE);
            Icons.stroke(p, 15 * dp, charging ? 0xFF30D158 : low ? 0xFFFF6961 : GREY);
            Icons.battery(c, p, b.centerX(), b.centerY() + 10 * dp, 15 * dp, battery / 100f, low);
        }
        // thin dividers between slots
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2C2C2E);
        for (int k = 0; k < lay.length; k++) {
            if (lay[k] == null || k == R_RES || k == R_FPS || k == R_WB) continue;   // keys have their own background
            float x = lay[k].left - 2 * dp;
            c.drawRect(x, rowY() - 11 * dp, x + Math.max(1, 0.7f * dp), rowY() + 11 * dp, p);
        }

        // centre: timer (red pill while recording, blinking dot, audio meter), or a status message
        RectF tr = timerRect();
        float ha = Math.max(0, Math.min(1, hudAlpha.f()));
        float red = timerRed.f();
        float ta = 1 - ha;
        if (red > 0.01f) {
            p.setColor(0xFFE0352B);
            p.setAlpha((int) (255 * red * ta));
            c.drawRoundRect(tr, 9 * dp, 9 * dp, p);
            p.setAlpha(255);
        }
        long secs = recording ? (SystemClock.elapsedRealtime() - recStart) / 1000 : 0;
        String t = String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        value.setTextSize(15 * dp);
        value.setAlpha((int) (255 * ta));
        c.drawText(t, tr.centerX(), tr.centerY() + 5.5f * dp, value);
        value.setAlpha(255);
        if (recording && ta > 0.5f) {
            if ((SystemClock.elapsedRealtime() / 500) % 2 == 0) {
                p.setColor(WHITE);
                c.drawCircle(tr.left + 14 * dp, tr.centerY(), 4 * dp, p);
            }
            if (audioOn) {
                // audio: five bars, green / yellow / red
                float lv = Math.max(0, Math.min(1, audioLevel.f()));
                for (int k = 0; k < 5; k++) {
                    float f0 = k / 5f;
                    float bx = tr.right - 26 * dp + k * 4 * dp, bh = 6 * dp + k * 2.2f * dp;
                    p.setColor(f0 < lv ? (k == 4 ? RED : k == 3 ? YELLOW : 0xFF30D158) : 0x55FFFFFF);
                    rf.set(bx, tr.centerY() + 7 * dp - bh, bx + 2.6f * dp, tr.centerY() + 7 * dp);
                    c.drawRoundRect(rf, 1.2f * dp, 1.2f * dp, p);
                }
            }
        }
        if (ha > 0.01f && hudText != null) {
            value.setTextSize(13.5f * dp);
            value.setColor(WHITE);
            value.setAlpha((int) (255 * ha));
            c.drawText(hudText, tr.centerX(), tr.centerY() + 5 * dp, value);
            value.setAlpha(255);
        }
    }

    private void readout(Canvas c, RectF r, String v, String l, int accent) {
        value.setTextSize(13 * dp);
        value.setColor(accent == RED ? 0xFFFF6961 : accent != 0 ? accent : WHITE);
        c.drawText(v, r.centerX(), r.centerY() + 0.5f * dp, value);
        value.setColor(WHITE);
        label.setTextSize(8.5f * dp);
        label.setColor(accent == YELLOW ? YELLOW : GREY);
        c.drawText(l, r.centerX(), r.centerY() + 12.5f * dp, label);
        label.setColor(GREY);
    }

    /** Tappable format slot (resolution, frame rate): a subtle rounded key with a chevron. */
    private void chip(Canvas c, RectF r, int id, String v, String l) {
        float s = press[id].f();
        c.save();
        c.scale(s, s, r.centerX(), r.centerY());
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(TILE);
        c.drawRoundRect(r, 9 * dp, 9 * dp, p);
        value.setTextSize(13 * dp);
        value.setAlpha(recording ? 120 : 255);
        c.drawText(v, r.centerX(), r.centerY() + 0.5f * dp, value);
        value.setAlpha(255);
        label.setTextSize(8.5f * dp);
        label.setColor(GREY);
        c.drawText(l + " ▾", r.centerX(), r.centerY() + 12.5f * dp, label);
        c.restore();
    }

    private void drawHistogram(Canvas c, RectF pic) {
        int[] h = readout.histogram;
        float w = 92 * dp, hh = 34 * dp, x0 = pic.left + 14 * dp, y1 = pic.bottom - 14 * dp;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x59000000);
        rf.set(x0, y1 - hh, x0 + w, y1);
        c.drawRoundRect(rf, 8 * dp, 8 * dp, p);
        int max = 1;
        for (int k = 1; k < h.length - 1; k++) max = Math.max(max, h[k]);
        path.reset();
        float bw = (w - 8 * dp) / h.length;
        path.moveTo(x0 + 4 * dp, y1 - 4 * dp);
        for (int k = 0; k < h.length; k++) {
            float v = Math.min(1f, (float) Math.sqrt(h[k] / (double) max));
            path.lineTo(x0 + 4 * dp + bw * (k + 0.5f), y1 - 4 * dp - v * (hh - 8 * dp));
        }
        path.lineTo(x0 + w - 4 * dp, y1 - 4 * dp);
        path.close();
        p.setColor(0xB3FFFFFF);
        c.drawPath(path, p);
        // clipping warning: a red edge when many pixels sit in the top bin
        if (h[h.length - 1] > max * 0.5f) {
            p.setColor(RED);
            rf.set(x0 + w - 6 * dp, y1 - hh + 4 * dp, x0 + w - 3 * dp, y1 - 4 * dp);
            c.drawRoundRect(rf, 1.5f * dp, 1.5f * dp, p);
        }
    }

    private void drawFocus(Canvas c, RectF pic) {
        float a = focusAlpha.f();
        if (a < 0.01f || focusPx < 0) return;
        // iPhone timing: bright, dimmed after 2 s, gone after 5 s (a locked box stays, dimmed)
        long shown = SystemClock.elapsedRealtime() - focusShownAt;
        if (!dragBias) {
            double want = shown < 2000 ? 1 : locked ? 0.75 : shown < 5000 ? 0.5 : 0;
            if (Math.abs(focusAlpha.target - want) > 0.01) {
                focusAlpha.to(want);
                kick();
            }
        }
        float s = focusScale.f(), half = 38 * dp * s;
        int alpha = (int) (255 * Math.min(1, a));
        c.save();
        c.clipRect(pic);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f * dp);
        p.setColor(YELLOW);
        p.setAlpha(alpha);
        rf.set(focusPx - half, focusPy - half, focusPx + half, focusPy + half);
        c.drawRect(rf, p);
        float tick = 6 * dp;
        c.drawLine(focusPx, rf.top, focusPx, rf.top + tick, p);
        c.drawLine(focusPx, rf.bottom, focusPx, rf.bottom - tick, p);
        c.drawLine(rf.left, focusPy, rf.left + tick, focusPy, p);
        c.drawLine(rf.right, focusPy, rf.right - tick, focusPy, p);
        float sx = rf.right + 20 * dp, sy = focusPy + sunOffset.f();
        if (sx > pic.right - 16 * dp) sx = rf.left - 20 * dp;   // keep the sun inside the picture
        if (dragBias) {
            c.drawLine(sx, focusPy - 90 * dp, sx, sy - 13 * dp, p);
            c.drawLine(sx, sy + 13 * dp, sx, focusPy + 90 * dp, p);
        }
        Icons.stroke(p, 22 * dp, YELLOW);
        p.setAlpha(alpha);
        Icons.sun(c, p, sx, sy, 22 * dp);
        p.setAlpha(255);
        c.restore();
    }

    /** The lens picker: one row per lens - name, 35 mm equivalent, real focal length. */
    private void drawLensMenu(Canvas c) {
        float a = Math.max(0, Math.min(1, lensMenuA.f()));
        if (a < 0.01f || lenses.isEmpty()) return;
        RectF first = menuRow(0), last = menuRow(lenses.size() - 1);
        RectF box = new RectF(first.left, first.top, first.right, last.bottom);
        c.save();
        float s = 0.92f + 0.08f * a;
        c.scale(s, s, box.right, (box.top + box.bottom) / 2);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF21C1C1E);
        p.setAlpha((int) (242 * a));
        c.drawRoundRect(box, 14 * dp, 14 * dp, p);
        for (int k = 0; k < lenses.size(); k++) {
            DeviceSetup.Lens l = lenses.get(k);
            RectF r = menuRow(k);
            boolean cur = l.id.equals(lensId);
            if (k > 0) {
                p.setColor(0xFF3A3A3C);
                p.setAlpha((int) (255 * a));
                c.drawRect(r.left + 14 * dp, r.top, r.right, r.top + Math.max(1, 0.6f * dp), p);
            }
            value.setTextAlign(Paint.Align.LEFT);
            value.setTextSize(14 * dp);
            value.setColor(cur ? YELLOW : WHITE);
            value.setAlpha((int) (255 * a));
            c.drawText(l.name, r.left + 16 * dp, r.centerY() - 1 * dp, value);
            label.setTextAlign(Paint.Align.LEFT);
            label.setTextSize(10 * dp);
            label.setColor(GREY);
            label.setAlpha((int) (255 * a));
            c.drawText(l.label() + "  ·  " + String.format(Locale.US, "%.2f mm", l.focalMm), r.left + 16 * dp, r.centerY() + 12 * dp, label);
            value.setTextAlign(Paint.Align.CENTER);
            label.setTextAlign(Paint.Align.CENTER);
            label.setAlpha(255);
            if (cur) {
                Icons.stroke(p, 18 * dp, YELLOW);
                p.setAlpha((int) (255 * a));
                Icons.check(c, p, r.right - 22 * dp, r.centerY(), 16 * dp);
            }
        }
        value.setColor(WHITE);
        value.setAlpha(255);
        c.restore();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------------ touch

    private int hit(float x, float y) {
        float r = 32 * dp;
        if (lensMenu) {
            for (int k = 0; k < lenses.size(); k++) if (menuRow(k).contains(x, y)) return MENU_ROW + k;
            return -3;   // any other tap closes the menu
        }
        if (lenses.size() > 1 && Math.abs(x - rightX()) < 46 * dp && Math.abs(y - lensY()) < 20 * dp) return B_LENS;
        for (int i = 0; i < 5; i++) {
            if (Math.abs(x - leftX()) < r && Math.abs(y - tileY(i)) < r) return i;
        }
        float rx = rightX();
        if (near(x, y, rx, midY(), 44 * dp)) return B_SHUTTER;
        if (near(x, y, rx, galleryY(), r)) return B_GALLERY;
        if (near(x, y, rx, settingsY(), r)) return B_SETTINGS;
        if (Math.abs(x - rx) < 50 * dp && Math.abs(y - lookY()) < 22 * dp) return B_LOOK;
        RectF[] lay = rowLayout();
        for (int k : new int[]{R_RES, R_FPS}) {
            if (lay[k] == null) continue;
            RectF cr = new RectF(lay[k]);
            cr.inset(-4 * dp, -8 * dp);
            if (cr.contains(x, y)) return k == R_RES ? B_RES : B_FPS;
        }
        if (picture().contains(x, y)) return PICTURE;
        return -1;
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        return (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                longFired = false;
                dragBias = false;
                pressed = hit(x, y);
                if (pressed == B_SHUTTER) shutterPress.to(0.9);
                else if (pressed >= 0 && pressed < press.length) press[pressed].to(0.88);
                if (pressed == PICTURE) {
                    biasAtDown = bias;
                    postDelayed(longPress, 500);
                }
                kick();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed == PICTURE && focusPx >= 0 && focusAlpha.f() > 0.05f
                        && Math.abs(y - downY) > 10 * dp && Math.abs(y - downY) > Math.abs(x - downX)) {
                    removeCallbacks(longPress);
                    dragBias = true;
                    bias = Math.max(-2f, Math.min(2f, biasAtDown - (y - downY) / (45 * dp)));
                    sunOffset.snap(-bias * 40 * dp);
                    focusAlpha.to(1);
                    focusShownAt = SystemClock.elapsedRealtime();
                    cb.onExposureBias(bias);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPress);
                int b = pressed;
                pressed = -1;
                shutterPress.to(1);
                for (Spring s : press) s.to(1);
                kick();
                if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
                if (b == PICTURE) {
                    if (!dragBias && !longFired) focusTap(x, y, false);
                    dragBias = false;
                } else if (b == -3) {
                    setLensMenu(false);
                } else if (b >= MENU_ROW && hit(x, y) == b) {
                    DeviceSetup.Lens l = lenses.get(b - MENU_ROW);
                    setLensMenu(false);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (!l.id.equals(lensId)) cb.onLens(l.id);
                } else if (b >= 0 && hit(x, y) == b) {
                    click(b);
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void focusTap(float x, float y, boolean lock) {
        RectF pic = picture();
        if (!pic.contains(x, y) || isSwitching()) return;
        focusPx = x;
        focusPy = y;
        bias = 0;
        sunOffset.snap(0);
        focusScale.snap(1.45);
        focusScale.to(1);
        focusAlpha.snap(1);
        focusShownAt = SystemClock.elapsedRealtime();
        if (!lock && locked) setLocked(false);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        cb.onFocus((x - pic.left) / pic.width(), (y - pic.top) / pic.height(), lock);
        kick();
    }

    private void click(int b) {
        if (isSwitching() && b != B_GALLERY) return;   // camera restarting
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        switch (b) {
            case B_SHUTTER: cb.onShutter(); break;
            case B_GALLERY: cb.onGallery(); break;
            case B_SETTINGS: if (!recording) cb.onSettings(); break;
            case T_LIVE: live = !live; syncGlow(false); cb.onLiveView(live); break;
            case T_LOCK:
                if (!locked && focusPx < 0) {
                    RectF pic = picture();
                    focusTap(pic.centerX(), pic.centerY(), true);
                } else {
                    cb.onLock(!locked);
                }
                break;
            case T_STAB: if (!recording) { stab = !stab; syncGlow(false); cb.onStabilization(stab); } break;
            case T_NR: nr = !nr; syncGlow(false); cb.onNoiseReduction(nr); break;
            case T_TONE: tone = !tone; syncGlow(false); cb.onToneMapping(tone); break;
            case B_LOOK: if (!recording) cb.onLookToggle(); break;
            case B_RES: if (!recording) cb.onResolutionCycle(); break;
            case B_FPS: if (!recording) cb.onFpsCycle(); break;
            case B_LENS: if (!recording) setLensMenu(!lensMenu); break;
            default: break;
        }
        invalidate();
    }

    private void setLensMenu(boolean open) {
        lensMenu = open;
        lensMenuA.to(open ? 1 : 0);
        kick();
    }

    // ------------------------------------------------------------------ animation loop

    private void kick() {
        if (!animating) {
            animating = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
        invalidate();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        double dt = lastFrameNs == 0 ? 1 / 60.0 : (frameTimeNanos - lastFrameNs) / 1e9;
        lastFrameNs = frameTimeNanos;
        boolean moving = false;
        for (Spring s : press) moving |= s.step(dt);
        for (Spring s : glow) moving |= s.step(dt);
        moving |= shutterMorph.step(dt);
        moving |= shutterPress.step(dt);
        moving |= focusScale.step(dt);
        moving |= focusAlpha.step(dt);
        moving |= timerRed.step(dt);
        moving |= thumbPop.step(dt);
        moving |= sunOffset.step(dt);
        moving |= audioLevel.step(dt);
        moving |= hudAlpha.step(dt);
        moving |= lensMenuA.step(dt);
        moving |= cover.step(dt);
        moving |= levelAlpha.step(dt);
        invalidate();
        boolean focusTimer = focusAlpha.value > 0.01 && SystemClock.elapsedRealtime() - focusShownAt < 5600;
        if (moving || focusTimer) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            animating = false;
        }
    }
}
