package com.github.yutaplug.playembeds

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.TextView

/**
 * Caption text with a background box behind each line, as wide as that line
 * (like YouTube's), instead of one rectangle as wide as the longest line.
 * The view's padding is the room the boxes extend past the text.
 */
internal class CaptionView(context: Context, color: Int) : TextView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null && layout.lineCount > 0) {
            val left = totalPaddingLeft.toFloat()
            val top = totalPaddingTop.toFloat()
            val last = layout.lineCount - 1
            for (line in 0..last) {
                val start = layout.getLineLeft(line)
                val end = layout.getLineRight(line)
                if (end <= start) continue
                box.set(
                    left + start - paddingLeft,
                    top + layout.getLineTop(line) - if (line == 0) paddingTop else 0,
                    left + end + paddingRight,
                    top + layout.getLineBottom(line) + if (line == last) paddingBottom else 0,
                )
                canvas.drawRect(box, paint)
            }
        }
        super.onDraw(canvas)
    }
}
