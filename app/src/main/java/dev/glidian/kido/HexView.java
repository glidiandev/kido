package dev.glidian.kido;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.Locale;

/**
 * A pointy-top hexagon tile for the honeycomb: channel code, app name and a brand-colored dot. When the app
 * has a themed emblem, the emblem sits in the middle in the app's color and the name moves down under it.
 */
final class HexView extends View {
    final Kido.Look look;
    private final String code;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint codePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outer = new Path(), inner = new Path();
    private final Drawable emblem;

    HexView(Context c, Kido.Look look, String code) {
        super(c);
        this.look = look;
        this.code = code;
        setFocusable(true);
        setClickable(true);
        codePaint.setTypeface(Kido.mono);
        codePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setTypeface(Kido.mincho);
        namePaint.setTextAlign(Paint.Align.CENTER);
        dot.setColor(look.glow);
        emblem = look.emblem != 0 ? c.getDrawable(look.emblem).mutate() : null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        hex(outer, 0, 0, w, h);
        float inset = Kido.px(4);
        hex(inner, inset, inset, w - inset, h - inset);
    }

    private static void hex(Path p, float l, float t, float r, float b) {
        float w = r - l, h = b - t;
        p.reset();
        p.moveTo(l + w / 2, t);
        p.lineTo(r, t + h * 0.25f);
        p.lineTo(r, t + h * 0.75f);
        p.lineTo(l + w / 2, b);
        p.lineTo(l, t + h * 0.75f);
        p.lineTo(l, t + h * 0.25f);
        p.close();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        animate().scaleX(gainFocus ? 1.1f : 1f).scaleY(gainFocus ? 1.1f : 1f).setDuration(220).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean f = isFocused();
        rim.setColor(f ? Kido.FOCUS_RIM : Kido.withAlpha(Kido.AMBER, 0.42f));
        fill.setColor(f ? Kido.ORANGE : 0xFF0B0905);
        c.drawPath(outer, rim);
        c.drawPath(inner, fill);

        float w = getWidth(), h = getHeight();
        codePaint.setColor(f ? 0xFF1A0D00 : Kido.DIM);
        codePaint.setTextSize(Kido.px(22));
        c.drawText(code, w / 2, h * (emblem != null ? 0.25f : 0.36f), codePaint);

        if (emblem != null) {
            // Fit the emblem in a box under the code, keeping its shape.
            float boxW = w * 0.5f, boxH = h * 0.27f, top = h * 0.29f;
            float iw = Math.max(1, emblem.getIntrinsicWidth()), ih = Math.max(1, emblem.getIntrinsicHeight());
            float k = Math.min(boxW / iw, boxH / ih);
            int ew = Math.round(iw * k), eh = Math.round(ih * k);
            int left = Math.round((w - ew) / 2), y = Math.round(top + (boxH - eh) / 2);
            emblem.setBounds(left, y, left + ew, y + eh);
            emblem.setTint(f ? Kido.DARK_TEXT : look.glow);
            emblem.draw(c);
        }

        // Fit the name: one line, shrinking long names rather than wrapping.
        String name = look.name.toUpperCase(Locale.ROOT);
        float size = Kido.px(emblem != null ? 26 : 34);
        namePaint.setTextSize(size);
        float max = w * 0.78f;
        float width = namePaint.measureText(name);
        if (width > max) namePaint.setTextSize(size * max / width);
        namePaint.setColor(f ? Kido.DARK_TEXT : Kido.CREAM);
        c.drawText(name, w / 2, h * (emblem != null ? 0.665f : 0.57f), namePaint);

        if (f) {
            dot.setColor(0xFF120800);
            dot.clearShadowLayer();
        } else {
            dot.setColor(look.glow);
            dot.setShadowLayer(Kido.px(10), 0, 0, look.glow);
        }
        float dw = Kido.px(46), dh = Kido.px(7);
        float dy = h * (emblem != null ? 0.715f : 0.66f);
        c.drawRect(w / 2 - dw / 2, dy, w / 2 + dw / 2, dy + dh, dot);
    }
}
