package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * A small color picker for pop-ups: a saturation/brightness square with a hue bar under it. Its height follows its
 * width at a fixed ratio, so it keeps its shape at any dialog width. Opacity is kept as given and isn't edited here.
 */
internal class ColorPicker(context: Context, initial: Int, private val changed: (Int) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val hsv = FloatArray(3)
    private var alpha = 255
    private val square = RectF()
    private val hueBar = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66000000
    }
    private var dragging = NONE

    var color: Int
        get() = Color.HSVToColor(alpha, hsv)
        set(value) {
            alpha = Color.alpha(value)
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    init {
        color = initial
        contentDescription = "Color picker"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Square area at 60% of the width, then a gap and the hue bar.
        val height = (width * 0.6f + GAP_DP * density + BAR_DP * density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 6 * density
        square.set(0f, 0f, width.toFloat(), width * 0.6f)
        hueBar.set(0f, square.bottom + GAP_DP * density, width.toFloat(), square.bottom + (GAP_DP + BAR_DP) * density)

        // White → hue across, transparent → black down.
        val across = LinearGradient(0f, 0f, square.right, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
        val down = LinearGradient(0f, 0f, 0f, square.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        fill.shader = ComposeShader(across, down, PorterDuff.Mode.SRC_OVER)
        canvas.drawRoundRect(square, corner, corner, fill)

        fill.shader = LinearGradient(
            hueBar.left, 0f, hueBar.right, 0f,
            IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(hueBar, corner, corner, fill)
        fill.shader = null

        val radius = 8 * density
        thumb(canvas, square.left + hsv[1] * square.width(), square.top + (1 - hsv[2]) * square.height(), radius, Color.HSVToColor(hsv))
        thumb(canvas, hueBar.left + hsv[0] / 360f * hueBar.width(), hueBar.centerY(), radius, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    }

    private fun thumb(canvas: Canvas, x: Float, y: Float, radius: Float, inside: Int) {
        fill.color = inside
        canvas.drawCircle(x, y, radius, fill)
        canvas.drawCircle(x, y, radius, ring)
        canvas.drawCircle(x, y, radius + ring.strokeWidth / 2, outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (event.y > square.bottom + GAP_DP * density / 2) HUE else SHADE
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {}
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                dragging = NONE
                return true
            }
            else -> return false
        }
        val x = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (dragging == HUE) {
            hsv[0] = x * 360f
        } else {
            hsv[1] = x
            hsv[2] = 1 - (event.y / square.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        invalidate()
        changed(color)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val GAP_DP = 14
        private const val BAR_DP = 22
        private const val NONE = 0
        private const val SHADE = 1
        private const val HUE = 2
    }
}
