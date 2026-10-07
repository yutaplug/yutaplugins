package com.github.yutaplug.fallbackfont

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint

/** Draws one emoji as text, centred and sized to its bounds, in place of a Twemoji image. */
internal class EmojiTextDrawable(private val emoji: String, typeface: Typeface?) : Drawable() {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        if (typeface != null) this.typeface = typeface
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        // Emoji glyphs are slightly wider and taller than the text size, so leave a little room.
        paint.textSize = minOf(bounds.width(), bounds.height()) * 0.8f
        val metrics = paint.fontMetrics
        val baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, bounds.exactCenterX(), baseline, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
