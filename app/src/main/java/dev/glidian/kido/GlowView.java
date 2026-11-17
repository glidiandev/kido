package dev.glidian.kido;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A soft colored glow: a shape with a blurred halo, inset so the halo fits inside the view. It renders
 * into its own hardware layer, so the blur is computed once and moving or fading the view afterwards
 * costs no more than any other view. Used behind focused tiles and the status block.
 *
 * <p>A shadow blur spreads roughly twice its radius, so drawing the core inset by exactly the blur radius
 * leaves the tail clipped by the view's rectangle: that shows up as a straight edge along the side of the
 * glow. Two ways out, both per instance: {@link #setPad} moves the core further in when the view can grow
 * (keeps a wide glow, and the caller makes the view bigger by the same amount), and {@link #setSpread}
 * shortens the blur when it cannot (slightly tighter glow, no layout change).
 */
final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius;
    private final boolean oval;
    private int color;
    private boolean on = true;
    /** Inset of the core inside the view; defaults to the blur radius, the current layout. */
    private float pad;
    /** How much of {@link #radius} the blur uses. 1 = full blur; 0.5 fits inside a pad of one radius. */
    private float spread = 1f;

    GlowView(Context c, float radius, boolean oval) {
        super(c);
        this.radius = radius;
        this.oval = oval;
        this.pad = radius;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Moves the drawn core in from the view edge, leaving the halo room to fade out. */
    void setPad(float p) {
        if (this.pad == p) return;
        this.pad = p;
        invalidate();
    }

    /** Scales the blur down so it finishes inside the current pad, at the cost of a tighter glow. */
    void setSpread(float s) {
        if (this.spread == s) return;
        this.spread = s;
        invalidate();
    }

    void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        invalidate();
    }

    void setGlowing(boolean on) {
        if (this.on == on) return;
        this.on = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        paint.setColor(color);
        if (on) paint.setShadowLayer(radius * spread, 0, 0, color);
        else paint.clearShadowLayer();
        r.set(pad, pad, getWidth() - pad, getHeight() - pad);
        if (oval) c.drawOval(r, paint);
        else c.drawRect(r, paint);
    }
}
