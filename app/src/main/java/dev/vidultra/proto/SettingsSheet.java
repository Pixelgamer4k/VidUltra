package dev.vidultra.proto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings, as an iOS-style grouped sheet that springs in from the right: switches, segmented
 * controls and check-mark lists. Options this phone cannot do are greyed out (from
 * {@link DeviceSetup}); options that need a camera restart are marked and disabled while
 * recording. Every row edits {@link Settings} and reports the key that changed.
 */
final class SettingsSheet extends FrameLayout implements Choreographer.FrameCallback {
    interface Listener {
        void onChanged(String key);

        void onClosed();

        /** "Support" rows: copy or share the diagnostic log. */
        void onDiagnostics(boolean share);
    }

    static final String[] RESTART_KEYS = {"res", "aspect", "fps", "bitrate", "codec"};

    private static final int BG = 0xF21C1C1E, GROUP = 0xFF2C2C2E, SEP = 0xFF3A3A3C, LABEL = 0xFFFFFFFF,
            SECONDARY = 0xFF8E8E93, ACCENT = 0xFFFFD60A, GREEN = 0xFF34C759;

    private final Settings st;
    private final Listener listener;
    private final float dp;
    private final View scrim;
    private final LinearLayout panel;
    private final LinearLayout body;
    private final Spring slide = new Spring(0.42, 0.88, 1);   // 0 open .. 1 closed
    private boolean animating, open;
    private long lastNs;
    private DeviceSetup caps;
    private boolean recording;
    private final Typeface semibold, regular;

    SettingsSheet(Context c, Settings st, Listener l) {
        super(c);
        this.st = st;
        this.listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        semibold = Typeface.create(Typeface.DEFAULT, 600, false);
        regular = Typeface.create(Typeface.DEFAULT, 400, false);

        scrim = new View(c);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(22 * dp);
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setElevation(24 * dp);
        panel.setClickable(true);   // touches on the panel do not reach the scrim

        // header: title + Done
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(i(20), i(16), i(12), i(8));
        TextView title = text("Settings", 20, LABEL, semibold);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView done = text("Done", 17, ACCENT, semibold);
        done.setPadding(i(12), i(8), i(12), i(8));
        done.setOnClickListener(v -> close());
        header.addView(done);
        panel.addView(header);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(i(16), 0, i(16), i(24));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addView(panel);
        setVisibility(GONE);
        rebuild();
    }

    void setCapabilities(DeviceSetup s) {
        caps = s;
        rebuild();
    }

    void setRecording(boolean r) {
        recording = r;
        rebuild();
    }

    boolean isOpen() {
        return open;
    }

    // ------------------------------------------------------------------ open / close

    void open(int insetRight) {
        rebuild();
        int w = getWidth() == 0 ? getResources().getDisplayMetrics().widthPixels : getWidth();
        int h = getHeight() == 0 ? getResources().getDisplayMetrics().heightPixels : getHeight();
        int pw = (int) Math.min(440 * dp, w * 0.5f);
        LayoutParams lp = new LayoutParams(pw, h - i(24), Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = insetRight + i(12);
        panel.setLayoutParams(lp);
        setVisibility(VISIBLE);
        open = true;
        slide.to(0);
        kick();
    }

    void close() {
        if (!open) return;
        open = false;
        slide.to(1);
        kick();
        listener.onClosed();
    }

    private void kick() {
        if (!animating) {
            animating = true;
            lastNs = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long t) {
        double dt = lastNs == 0 ? 1 / 60.0 : (t - lastNs) / 1e9;
        lastNs = t;
        boolean moving = slide.step(dt);
        float s = slide.f();
        panel.setTranslationX(s * (panel.getWidth() + i(40)));
        scrim.setAlpha(0.35f * (1 - s));
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            animating = false;
            if (!open) setVisibility(GONE);
        }
    }

    // ------------------------------------------------------------------ content

    private void rebuild() {
        body.removeAllViews();
        boolean lockRestart = recording;

        section("LOOK");
        LinearLayout g = group();
        segmentRow(g, "Recording", new String[]{"Rec.709", "Log"}, null, st.log ? 1 : 0, !recording,
                v -> { st.log = v == 1; changed("look"); });
        if (st.log) {
            switchRow(g, "Show log as Rec.709", "Live view only; the file stays log", st.monitor709, true,
                    v -> { st.monitor709 = v; changed("monitor709"); });
        }
        if (st.log) {
            section("LOG PROFILE");
            LinearLayout lg = group();
            String[] names = new String[ColorSpaces.LOG_PROFILES.length];
            for (int k = 0; k < names.length; k++) {
                ColorSpaces.Profile pr = ColorSpaces.LOG_PROFILES[k];
                names[k] = pr.name + "  ·  " + ColorSpaces.GAMUTS[pr.gamut].name;
            }
            choiceList(lg, names, st.logProfile, !recording, v -> { st.logProfile = v; changed("look"); });

            section("GAMUT");
            LinearLayout gg = group();
            String[] gn = new String[ColorSpaces.GAMUTS.length + 1];
            gn[0] = "Matching the profile (" + ColorSpaces.GAMUTS[st.profile().gamut].name + ")";
            for (int k = 0; k < ColorSpaces.GAMUTS.length; k++) gn[k + 1] = ColorSpaces.GAMUTS[k].name;
            choiceList(gg, gn, st.gamut + 1, !recording, v -> { st.gamut = v - 1; changed("look"); });
        }

        section("IMAGE");
        LinearLayout im = group();
        switchRow(im, "Dynamic tone mapping", "Local exposure for shadows and highlights; also in log", st.tone, true,
                v -> { st.tone = v; changed("tone"); });
        switchRow(im, "Noise reduction", "Temporal + colour, keeps natural grain", st.nr, true,
                v -> { st.nr = v; changed("nr"); });
        if (st.nr) {
            segmentRow(im, "Grain", Settings.GRAIN_NAMES, null, st.grain, true, v -> { st.grain = v; changed("grain"); });
        }

        section("MOTION");
        LinearLayout mo = group();
        switchRow(mo, "Stabilization", "Gyro + rolling-shutter correction (crops 1.2x)", st.eis, !recording,
                v -> { st.eis = v; changed("eis"); });
        switchRow(mo, "180° shutter", "Cinematic motion blur when steady", st.filmShutter, true,
                v -> { st.filmShutter = v; changed("film"); });
        segmentRow(mo, "Anti-flicker", Settings.FLICKER_NAMES, null, st.flicker, true, v -> { st.flicker = v; changed("flicker"); });

        section("FORMAT");
        LinearLayout fm = group();
        segmentRow(fm, "Resolution", Settings.RES_NAMES, caps != null ? caps.resOk : null, st.res, !lockRestart,
                v -> { st.res = v; changed("res"); });
        segmentRow(fm, "Aspect ratio", Settings.ASPECT_NAMES, caps != null ? caps.aspectOk : null, st.aspect, !lockRestart,
                v -> { st.aspect = v; changed("aspect"); });
        String[] fpsNames = new String[Settings.FPS.length];
        for (int k = 0; k < fpsNames.length; k++) fpsNames[k] = Settings.FPS[k] + " fps";
        segmentRow(fm, "Frame rate", fpsNames, caps != null ? caps.fpsOk : null, st.fps, !lockRestart,
                v -> { st.fps = v; changed("fps"); });
        segmentRow(fm, "Bitrate", Settings.BITRATE_NAMES, null, st.bitrate, !lockRestart,
                v -> { st.bitrate = v; changed("bitrate"); });
        boolean[] codecOk = caps == null ? null : new boolean[]{caps.hasHevc10 || caps.hasAvc, caps.hasHevc10, caps.hasAvc};
        segmentRow(fm, "Codec", Settings.CODEC_NAMES, codecOk, st.codec, !lockRestart,
                v -> { st.codec = v; changed("codec"); });
        footnote(recording ? "Stop recording to change the format."
                : "Format changes restart the camera (about a second). Lower resolutions are downsampled from the full sensor.");

        section("VIEWFINDER");
        LinearLayout vf = group();
        switchRow(vf, "Live view", "Show the real pipeline in the viewfinder", st.live, true, v -> { st.live = v; changed("live"); });
        switchRow(vf, "Grid", null, st.grid, true, v -> { st.grid = v; changed("grid"); });
        switchRow(vf, "Histogram", null, st.histogram, true, v -> { st.histogram = v; changed("histogram"); });
        switchRow(vf, "Audio meter", "While recording", st.audioMeter, true, v -> { st.audioMeter = v; changed("audioMeter"); });

        if (caps != null) {
            section("THIS PHONE");
            LinearLayout ab = group();
            int[] sz = st.size();
            info(ab, "Recording " + caps.outW + "×" + caps.outH + " at " + caps.fps + " fps"
                    + ((caps.outW != sz[0] || caps.outH != sz[1]) ? " (closest to " + sz[0] + "×" + sz[1] + ")" : ""));
            info(ab, "Sensor " + caps.rawSize.getWidth() + "×" + caps.rawSize.getHeight() + " " + DeviceSetup.fmtName(caps.rawFormat)
                    + "  ·  " + (caps.tenBit ? "HEVC 10-bit" : "H.264 8-bit") + " " + Math.round(caps.bitrate / 1e6) + " Mbps");
        }

        section("SUPPORT");
        LinearLayout sp = group();
        actionRow(sp, "Copy Diagnostic Log", () -> listener.onDiagnostics(false));
        actionRow(sp, "Share Diagnostic Log", () -> listener.onDiagnostics(true));
        footnote("If something goes wrong, send this to the developer. It lists the phone model, Android "
                + "version, camera and encoder setup, these settings, heat and battery state, and the app's own "
                + "messages and crash reports. It never contains your videos, photos, location, accounts, "
                + "file names or contacts; email addresses, phone numbers, network addresses and serial "
                + "numbers are removed.");
        View end = new View(getContext());
        body.addView(end, new LinearLayout.LayoutParams(1, i(28)));
    }

    private void changed(String key) {
        st.save();
        listener.onChanged(key);
        post(this::rebuild);   // rows that depend on others (log profile, grain) appear / hide
    }

    // ------------------------------------------------------------------ row builders

    private int i(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private void section(String name) {
        TextView t = text(name, 12.5f, SECONDARY, regular);
        t.setLetterSpacing(0.04f);
        t.setPadding(i(16), i(18), i(16), i(6));
        body.addView(t);
    }

    private void footnote(String s) {
        TextView t = text(s, 12.5f, SECONDARY, regular);
        t.setPadding(i(16), i(6), i(16), 0);
        body.addView(t);
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(getContext());
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(GROUP);
        d.setCornerRadius(12 * dp);
        g.setBackground(d);
        g.setClipToOutline(true);
        body.addView(g);
        return g;
    }

    private void separator(LinearLayout g) {
        if (g.getChildCount() == 0) return;
        View v = new View(getContext());
        v.setBackgroundColor(SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, i(0.5f)));
        lp.leftMargin = i(16);
        g.addView(v, lp);
    }

    private interface IntChange { void on(int v); }

    private interface BoolChange { void on(boolean v); }

    private void switchRow(LinearLayout g, String label, String sub, boolean value, boolean enabled, BoolChange ch) {
        separator(g);
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(i(16), i(10), i(14), i(10));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 16, LABEL, regular));
        if (sub != null) texts.addView(text(sub, 12.5f, SECONDARY, regular));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        IosSwitch sw = new IosSwitch(getContext(), value, v -> ch.on(v));
        sw.setEnabled(enabled);
        row.addView(sw, new LinearLayout.LayoutParams(i(51), i(31)));
        row.setAlpha(enabled ? 1 : 0.4f);
        row.setOnClickListener(v -> { if (enabled) sw.toggle(); });
        g.addView(row);
    }

    private void segmentRow(LinearLayout g, String label, String[] opts, boolean[] ok, int sel, boolean enabled, IntChange ch) {
        separator(g);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(i(16), i(10), i(16), i(12));
        row.addView(text(label, 16, LABEL, regular));
        Segmented seg = new Segmented(getContext(), opts, ok, sel, enabled, v -> ch.on(v));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, i(32));
        lp.topMargin = i(8);
        row.addView(seg, lp);
        row.setAlpha(enabled ? 1 : 0.4f);
        g.addView(row);
    }

    private void choiceList(LinearLayout g, String[] opts, int sel, boolean enabled, IntChange ch) {
        for (int k = 0; k < opts.length; k++) {
            separator(g);
            final int idx = k;
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(i(16), i(12), i(16), i(12));
            TextView t = text(opts[k], 15.5f, LABEL, regular);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setSingleLine(true);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Check mark = new Check(getContext(), k == sel);
            row.addView(mark, new LinearLayout.LayoutParams(i(22), i(22)));
            row.setAlpha(enabled ? 1 : 0.4f);
            row.setOnClickListener(v -> {
                if (!enabled || idx == sel) return;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                ch.on(idx);
            });
            g.addView(row);
        }
    }

    private void actionRow(LinearLayout g, String label, Runnable r) {
        separator(g);
        TextView t = text(label, 16, ACCENT, regular);
        t.setPadding(i(16), i(12), i(16), i(12));
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            r.run();
        });
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null));
        g.addView(t);
    }

    private void info(LinearLayout g, String s) {
        separator(g);
        TextView t = text(s, 14, SECONDARY, regular);
        t.setPadding(i(16), i(11), i(16), i(11));
        g.addView(t);
    }

    // ------------------------------------------------------------------ controls

    /** iOS switch: green track, white thumb that springs across. */
    static final class IosSwitch extends View implements Choreographer.FrameCallback {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Spring pos;
        private final BoolChange ch;
        private boolean on, anim;
        private long last;

        IosSwitch(Context c, boolean on, BoolChange ch) {
            super(c);
            this.on = on;
            this.ch = ch;
            pos = new Spring(0.32, 0.72, on ? 1 : 0);
            setOnClickListener(v -> toggle());
        }

        void toggle() {
            if (!isEnabled()) return;
            on = !on;
            pos.to(on ? 1 : 0);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (!anim) {
                anim = true;
                last = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
            ch.on(on);
        }

        @Override
        public void doFrame(long t) {
            double dt = last == 0 ? 1 / 60.0 : (t - last) / 1e9;
            last = t;
            boolean moving = pos.step(dt);
            invalidate();
            if (moving) Choreographer.getInstance().postFrameCallback(this); else anim = false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), v = Math.max(0, Math.min(1, pos.f()));
            p.setColor(blend(0xFF39393D, GREEN, v));
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            float r = h / 2 - h * 0.065f, x = r + h * 0.065f + (w - 2 * (r + h * 0.065f)) * pos.f();
            p.setColor(0x33000000);
            c.drawCircle(x, h / 2 + h * 0.04f, r, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, h / 2, r, p);
        }
    }

    /** iOS segmented control: a raised pill slides to the selected segment. */
    static final class Segmented extends View implements Choreographer.FrameCallback {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] opts;
        private final boolean[] ok;
        private final IntChange ch;
        private final boolean enabled;
        private int sel;
        private final Spring pos;
        private boolean anim;
        private long last;

        Segmented(Context c, String[] opts, boolean[] ok, int sel, boolean enabled, IntChange ch) {
            super(c);
            this.opts = opts;
            this.ok = ok;
            this.sel = sel;
            this.enabled = enabled;
            this.ch = ch;
            pos = new Spring(0.34, 0.8, sel);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            t.setTextSize(13 * c.getResources().getDisplayMetrics().density);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!enabled) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int k = (int) (e.getX() / (getWidth() / (float) opts.length));
                k = Math.max(0, Math.min(opts.length - 1, k));
                if (k != sel && (ok == null || ok[k])) {
                    sel = k;
                    pos.to(k);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (!anim) {
                        anim = true;
                        last = 0;
                        Choreographer.getInstance().postFrameCallback(this);
                    }
                    final int pick = k;
                    postDelayed(() -> ch.on(pick), 180);   // let the pill land first
                }
            }
            return true;
        }

        @Override
        public void doFrame(long tt) {
            double dt = last == 0 ? 1 / 60.0 : (tt - last) / 1e9;
            last = tt;
            boolean moving = pos.step(dt);
            invalidate();
            if (moving) Choreographer.getInstance().postFrameCallback(this); else anim = false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            p.setColor(0xFF3A3A3C);
            c.drawRoundRect(new RectF(0, 0, w, h), 9 * d, 9 * d, p);
            float sw = w / opts.length, x = pos.f() * sw;
            p.setColor(0xFF636366);
            c.drawRoundRect(new RectF(x + 2 * d, 2 * d, x + sw - 2 * d, h - 2 * d), 7 * d, 7 * d, p);
            for (int k = 0; k < opts.length; k++) {
                boolean avail = ok == null || ok[k];
                t.setColor(avail ? 0xFFFFFFFF : 0x55FFFFFF);
                c.drawText(opts[k], sw * k + sw / 2, h / 2 + 4.6f * d, t);
            }
        }
    }

    /** Check mark for list choices. */
    static final class Check extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean on;

        Check(Context c, boolean on) {
            super(c);
            this.on = on;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!on) return;
            float s = getWidth();
            Icons.stroke(p, s * 1.2f, ACCENT);
            Icons.check(c, p, s / 2, s / 2, s);
        }
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
