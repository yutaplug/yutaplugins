package com.github.yutaplug.superreactions

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * The "super reaction" picker icon: Discord's reaction smiley with a sparkle at
 * its top-right corner, like the official client. 126.21 has no such asset.
 */
internal class SuperReactIcon(private val smiley: Drawable) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sparkle = Path()
    private var color = 0

    fun setColor(color: Int) {
        this.color = color
        paint.color = color
        smiley.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        // Smiley in the lower-left 80%, sparkle centred in the top-right corner.
        val face = (size * 0.8f).toInt()
        smiley.setBounds(bounds.left, bounds.top + (size - face).toInt(), bounds.left + face, bounds.top + size.toInt())
        val cx = left + size * 0.82f
        val cy = top + size * 0.18f
        val outer = size * 0.18f
        val inner = outer * 0.28f
        sparkle.reset()
        sparkle.moveTo(cx, cy - outer)
        sparkle.quadTo(cx + inner, cy - inner, cx + outer, cy)
        sparkle.quadTo(cx + inner, cy + inner, cx, cy + outer)
        sparkle.quadTo(cx - inner, cy + inner, cx - outer, cy)
        sparkle.quadTo(cx - inner, cy - inner, cx, cy - outer)
        sparkle.close()
    }

    override fun draw(canvas: Canvas) {
        smiley.draw(canvas)
        canvas.drawPath(sparkle, paint)
    }

    override fun getIntrinsicWidth() = smiley.intrinsicWidth
    override fun getIntrinsicHeight() = smiley.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        smiley.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        smiley.colorFilter = filter ?: PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
