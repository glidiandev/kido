package dev.glidian.kido;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * Seven-segment clock: h:mm with small seconds, plus AM/PM unless the TV is set to 24-hour time.
 * Segments are hex-ended polygons; unlit segments stay faintly visible like a real display. Redraws
 * once a second. As a timer it shows a time it's given instead (m:ss, or h:mm:ss past an hour).
 */
final class SegClockView extends View {
    // Segments a..g on a 60x100 digit cell.
    private static final float[][] SEGS = new float[7][];
    private static final String[] DIGITS = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};

    static {
        float g = 4, l = 8, r = 52, t = 8, m = 50, b = 92;
        SEGS[0] = new float[]{l + g, t, r - g, t};
        SEGS[1] = new float[]{r, t + g, r, m - g};
        SEGS[2] = new float[]{r, m + g, r, b - g};
        SEGS[3] = new float[]{l + g, b, r - g, b};
        SEGS[4] = new float[]{l, m + g, l, b - g};
        SEGS[5] = new float[]{l, t + g, l, m - g};
        SEGS[6] = new float[]{l + g, m, r - g, m};
    }

    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ampm = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean timer;
    private long elapsed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    SegClockView(Context c) {
        this(c, false);
    }

    SegClockView(Context c, boolean timer) {
        super(c);
        this.timer = timer;
        on.setColor(Kido.INK);
        on.setShadowLayer(Kido.px(12), 0, 0, Kido.withAlpha(Kido.ORANGE, 0.6f));
        off.setColor(Kido.withAlpha(Kido.AMBER, 0.07f));
        ampm.setColor(Kido.INK);
        ampm.setTypeface(Kido.condBold);
    }

    /** The digits' color and glow, for a timer that follows an app's color. */
    void setColor(int color) {
        on.setColor(color);
        on.setShadowLayer(Kido.px(8), 0, 0, Kido.withAlpha(color, 0.6f));
        invalidate();
    }

    /** Timer mode: the time to show, in seconds. */
    void showElapsed(long seconds) {
        if (seconds == elapsed) return;
        elapsed = seconds;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!timer) post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (timer) {
            drawTimer(canvas);
            return;
        }
        // The view is padded so the glow around the digits has room to show.
        float pad = Kido.px(14);
        canvas.translate(pad, pad);
        Calendar now = Calendar.getInstance();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
        int h = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (h == 0 && !h24) h = 12;
        String hh = h24 ? String.format(Locale.ROOT, "%02d", h) : String.valueOf(h);
        String mm = String.format(Locale.ROOT, "%02d", now.get(Calendar.MINUTE));
        String ss = String.format(Locale.ROOT, "%02d", now.get(Calendar.SECOND));

        float big = getHeight() - pad * 2;    // digit height
        float bigW = big * 0.58f, gap = big * 0.06f;
        float small = big * 0.6f, smallW = small * 0.58f;
        canvas.save();
        canvas.skew(-0.12f, 0);               // the slight lean of a real segment display
        canvas.translate(big * 0.12f, 0);
        float x = 0;
        for (char d : hh.toCharArray()) { digit(canvas, d - '0', x, 0, big); x += bigW + gap; }
        x = colon(canvas, x, big);
        for (char d : mm.toCharArray()) { digit(canvas, d - '0', x, 0, big); x += bigW + gap; }
        x = colon(canvas, x, big);
        for (char d : ss.toCharArray()) { digit(canvas, d - '0', x, big - small, small); x += smallW + gap * 0.6f; }
        canvas.restore();
        ampm.setTextSize(big * 0.24f);
        if (!h24) canvas.drawText(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", x + big * 0.2f, big - big * 0.02f, ampm);
    }

    private void drawTimer(Canvas canvas) {
        float pad = getHeight() * 0.15f;
        float big = getHeight() - pad * 2, bigW = big * 0.58f, gap = big * 0.06f;
        long h = elapsed / 3600, m = elapsed / 60 % 60, s = elapsed % 60;
        String[] groups = h > 0
                ? new String[]{String.valueOf(h), String.format(Locale.ROOT, "%02d", m), String.format(Locale.ROOT, "%02d", s)}
                : new String[]{String.valueOf(m), String.format(Locale.ROOT, "%02d", s)};
        canvas.translate(pad + big * 0.12f, pad);
        canvas.skew(-0.12f, 0);
        float x = 0;
        for (int g = 0; g < groups.length; g++) {
            if (g > 0) x = colon(canvas, x, big);
            for (char d : groups[g].toCharArray()) { digit(canvas, d - '0', x, 0, big); x += bigW + gap; }
        }
    }

    private float colon(Canvas c, float x, float big) {
        float s = big * 0.09f;
        c.drawRect(x, big * 0.28f, x + s, big * 0.28f + s, on);
        c.drawRect(x, big * 0.66f, x + s, big * 0.66f + s, on);
        return x + s + big * 0.1f;
    }

    private void digit(Canvas c, int d, float x, float y, float h) {
        String lit = DIGITS[d];
        float k = h / 100f, t = 11 * k;
        for (int i = 0; i < 7; i++) {
            float[] s = SEGS[i];
            float x1 = x + s[0] * k, y1 = y + s[1] * k, x2 = x + s[2] * k, y2 = y + s[3] * k;
            path.reset();
            if (y1 == y2) {
                path.moveTo(x1, y1);
                path.lineTo(x1 + t / 2, y1 - t / 2);
                path.lineTo(x2 - t / 2, y1 - t / 2);
                path.lineTo(x2, y1);
                path.lineTo(x2 - t / 2, y1 + t / 2);
                path.lineTo(x1 + t / 2, y1 + t / 2);
            } else {
                path.moveTo(x1, y1);
                path.lineTo(x1 + t / 2, y1 + t / 2);
                path.lineTo(x1 + t / 2, y2 - t / 2);
                path.lineTo(x1, y2);
                path.lineTo(x1 - t / 2, y2 - t / 2);
                path.lineTo(x1 - t / 2, y1 + t / 2);
            }
            path.close();
            c.drawPath(path, lit.indexOf((char) ('a' + i)) >= 0 ? on : off);
        }
    }
}
