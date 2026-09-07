package com.github.ushie;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

public class MirroredDrawable extends Drawable {
    private final Drawable base;

    public MirroredDrawable(Drawable base) {
        this.base = base;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        base.setBounds(bounds);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        int cx = b.centerX();
        int cy = b.centerY();
        canvas.save();
        canvas.scale(-1f, 1f, cx, cy);
        base.draw(canvas);
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() { return base.getIntrinsicWidth(); }

    @Override
    public int getIntrinsicHeight() { return base.getIntrinsicHeight(); }

    @Override
    public void setAlpha(int alpha) { base.setAlpha(alpha); }

    @Override
    public void setColorFilter(ColorFilter colorFilter) { base.setColorFilter(colorFilter); }

    @Override
    public void setTint(int tintColor) { base.setTint(tintColor); }

    @Override
    public void setTintList(ColorStateList tint) { base.setTintList(tint); }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) { base.setTintMode(tintMode); }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = base.setState(state);
        if (changed) invalidateSelf();
        return changed;
    }

    @Override
    public boolean isStateful() { return base.isStateful(); }

    @Override
    public int getOpacity() {
        return base.getOpacity() == PixelFormat.UNKNOWN ? PixelFormat.TRANSLUCENT : base.getOpacity();
    }
}
