package dev.vidultra.proto;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Vector icons in the style of Apple's SF Symbols: regular-weight strokes with round caps and
 * joins, and solid ".fill" variants for the "on" state. Each is drawn centred on (x, y) in a
 * nominal box of size s (pixels), in the paint's colour.
 */
final class Icons {
    private Icons() {}

    private static final Path P = new Path();
    private static final RectF R = new RectF();

    static Paint stroke(Paint p, float s, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, s * 0.08f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        return p;
    }

    private static void fill(Canvas c, Paint p, Path path) {
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(path, p);
        p.setStyle(st);
    }

    private static void slash(Canvas c, Paint p, float x, float y, float s) {
        c.drawLine(x - s * 0.4f, y + s * 0.4f, x + s * 0.4f, y - s * 0.4f, p);
    }

    /** eye / eye.fill (live view) */
    static void eye(Canvas c, Paint p, float x, float y, float s, boolean on, int hole) {
        float w = s * 0.5f, h = s * 0.3f;
        P.reset();
        P.moveTo(x - w, y);
        P.cubicTo(x - w * 0.6f, y - h * 1.4f, x + w * 0.6f, y - h * 1.4f, x + w, y);
        P.cubicTo(x + w * 0.6f, y + h * 1.4f, x - w * 0.6f, y + h * 1.4f, x - w, y);
        P.close();
        if (on) {
            fill(c, p, P);
            int col = p.getColor();
            p.setColor(hole);
            Paint.Style st = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, s * 0.17f, p);
            p.setStyle(st);
            p.setColor(col);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, s * 0.08f, p);
            p.setStyle(st);
        } else {
            c.drawPath(P, p);
            c.drawCircle(x, y, s * 0.14f, p);
            slash(c, p, x, y, s);
        }
    }

    /** lock.fill / lock.open */
    static void lock(Canvas c, Paint p, float x, float y, float s, boolean closed, int hole) {
        float bw = s * 0.6f, bh = s * 0.44f, top = y - s * 0.02f;
        float sw = s * 0.19f, sh = s * 0.26f;
        P.reset();
        P.moveTo(x - sw, top);
        P.lineTo(x - sw, top - sh * 0.5f);
        if (closed) {
            P.cubicTo(x - sw, top - sh * 1.55f, x + sw, top - sh * 1.55f, x + sw, top - sh * 0.5f);
            P.lineTo(x + sw, top);
        } else {
            P.cubicTo(x - sw, top - sh * 1.55f, x + sw, top - sh * 1.55f, x + sw, top - sh * 0.8f);
        }
        c.drawPath(P, p);
        R.set(x - bw / 2, top, x + bw / 2, top + bh);
        if (closed) {
            P.reset();
            P.addRoundRect(R, s * 0.09f, s * 0.09f, Path.Direction.CW);
            fill(c, p, P);
            int col = p.getColor();
            p.setColor(hole);
            Paint.Style st = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, top + bh * 0.48f, s * 0.05f, p);
            p.setStyle(st);
            p.setColor(col);
        } else {
            c.drawRoundRect(R, s * 0.09f, s * 0.09f, p);
            c.drawCircle(x, top + bh * 0.48f, s * 0.03f, p);
        }
    }

    /** gyroscope (stabilization): a ring with two crossing orbits */
    static void gyroscope(Canvas c, Paint p, float x, float y, float s, boolean on) {
        float r = s * 0.36f;
        c.drawCircle(x, y, r, p);
        for (int k = -1; k <= 1; k += 2) {
            c.save();
            c.rotate(k * 32, x, y);
            R.set(x - r * 1.18f, y - r * 0.42f, x + r * 1.18f, y + r * 0.42f);
            c.drawOval(R, p);
            c.restore();
        }
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, s * 0.06f, p);
        p.setStyle(st);
        if (!on) slash(c, p, x, y, s * 1.1f);
    }

    /** sparkles (noise reduction); filled when on */
    static void sparkles(Canvas c, Paint p, float x, float y, float s, boolean on) {
        star(c, p, x - s * 0.08f, y + s * 0.06f, s * 0.32f, on);
        star(c, p, x + s * 0.24f, y - s * 0.22f, s * 0.15f, on);
        star(c, p, x + s * 0.26f, y + s * 0.27f, s * 0.1f, on);
        if (!on) slash(c, p, x, y, s);
    }

    private static void star(Canvas c, Paint p, float x, float y, float r, boolean filled) {
        P.reset();
        P.moveTo(x, y - r);
        P.quadTo(x + r * 0.12f, y - r * 0.12f, x + r, y);
        P.quadTo(x + r * 0.12f, y + r * 0.12f, x, y + r);
        P.quadTo(x - r * 0.12f, y + r * 0.12f, x - r, y);
        P.quadTo(x - r * 0.12f, y - r * 0.12f, x, y - r);
        P.close();
        if (filled) fill(c, p, P); else c.drawPath(P, p);
    }

    /** circle.lefthalf.filled (dynamic tone mapping) */
    static void tone(Canvas c, Paint p, float x, float y, float s, boolean on) {
        float r = s * 0.36f;
        c.drawCircle(x, y, r, p);
        if (on) {
            Paint.Style st = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            R.set(x - r, y - r, x + r, y + r);
            c.drawArc(R, 90, 180, true, p);
            p.setStyle(st);
        } else {
            c.drawLine(x, y - r, x, y + r, p);
            slash(c, p, x, y, s * 1.1f);
        }
    }

    /** gearshape (settings): eight rounded teeth */
    static void gear(Canvas c, Paint p, float x, float y, float s) {
        float r0 = s * 0.33f, amp = s * 0.06f;
        P.reset();
        int n = 240;
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            double w = Math.cos(a * 8);                       // eight teeth
            double sq = Math.tanh(w * 1.8) / Math.tanh(1.8);  // rounded teeth
            float r = (float) (r0 + amp * sq);
            float px = x + (float) Math.cos(a) * r, py = y + (float) Math.sin(a) * r;
            if (i == 0) P.moveTo(px, py); else P.lineTo(px, py);
        }
        P.close();
        c.drawPath(P, p);
        c.drawCircle(x, y, s * 0.13f, p);
    }

    /** sun.max (exposure) */
    static void sun(Canvas c, Paint p, float x, float y, float s) {
        c.drawCircle(x, y, s * 0.19f, p);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            float r0 = s * 0.32f, r1 = s * 0.45f;
            c.drawLine(x + (float) Math.cos(a) * r0, y + (float) Math.sin(a) * r0,
                    x + (float) Math.cos(a) * r1, y + (float) Math.sin(a) * r1, p);
        }
    }

    /** battery.75 style, level 0..1 */
    static void battery(Canvas c, Paint p, float x, float y, float s, float level, boolean low) {
        float w = s * 0.78f, h = s * 0.38f;
        R.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        c.drawRoundRect(R, h * 0.28f, h * 0.28f, p);
        c.drawLine(x + w / 2 + s * 0.07f, y - h * 0.18f, x + w / 2 + s * 0.07f, y + h * 0.18f, p);
        float in = p.getStrokeWidth() * 1.3f;
        int col = p.getColor();
        if (low) p.setColor(0xFFFF453A);
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        R.set(x - w / 2 + in, y - h / 2 + in, x - w / 2 + in + (w - 2 * in) * Math.max(0.04f, Math.min(1, level)), y + h / 2 - in);
        c.drawRoundRect(R, h * 0.12f, h * 0.12f, p);
        p.setStyle(st);
        p.setColor(col);
    }

    static void check(Canvas c, Paint p, float x, float y, float s) {
        P.reset();
        P.moveTo(x - s * 0.32f, y + s * 0.02f);
        P.lineTo(x - s * 0.08f, y + s * 0.26f);
        P.lineTo(x + s * 0.34f, y - s * 0.24f);
        c.drawPath(P, p);
    }
}
