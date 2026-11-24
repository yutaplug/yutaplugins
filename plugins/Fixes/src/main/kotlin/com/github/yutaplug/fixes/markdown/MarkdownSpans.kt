package com.github.yutaplug.fixes.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.UpdateAppearance
import com.aliucord.api.SettingsAPI
import kotlin.math.max
import kotlin.math.min

/**
 * Flags for a block span nested [depth] blocks deep. Android adds up leading margins in span priority
 * order, so outer blocks must have a higher priority to draw first (quote stripe, then list marker, then text).
 * Priority also survives span reordering, e.g. when a game mention's text is replaced.
 */
internal fun blockFlags(depth: Int): Int =
    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or ((200 - depth).coerceIn(1, 255) shl Spanned.SPAN_PRIORITY_SHIFT)

/**
 * Draws the part of a rounded box that belongs to one line. Middle lines get straight edges and only the
 * first and last lines are rounded, so the box is right however many of its lines are drawn.
 */
private fun drawLinePart(
    canvas: Canvas,
    paint: Paint,
    rect: RectF,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    first: Boolean,
    last: Boolean,
    radius: Float,
) {
    val inset = if (paint.style == Paint.Style.STROKE) paint.strokeWidth / 2f else 0f
    val extend = radius * 2 + paint.strokeWidth
    rect.set(
        left + inset,
        if (first) top + inset else top - extend,
        right - inset,
        if (last) bottom - inset else bottom + extend,
    )
    canvas.save()
    canvas.clipRect(left - extend, top, right + extend, bottom)
    canvas.drawRoundRect(rect, radius, radius, paint)
    canvas.restore()
}

private fun Spanned.coversLineStart(span: Any, start: Int) = getSpanStart(span) >= start

private fun Spanned.coversLineEnd(span: Any, end: Int) = getSpanEnd(span) <= end

/** The quote stripe, with Discord's own geometry and color. */
internal class QuoteStripeSpan(
    private val color: Int,
    private val stripeWidth: Int,
    private val gapWidth: Int,
) : LeadingMarginSpan {
    private val rect = RectF()

    override fun getLeadingMargin(first: Boolean): Int = stripeWidth + gapWidth

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        val spanned = text as? Spanned ?: return
        val oldColor = paint.color
        val oldStyle = paint.style
        paint.color = color
        paint.style = Paint.Style.FILL
        val edge = x + dir * stripeWidth
        drawLinePart(
            canvas,
            paint,
            rect,
            min(x, edge).toFloat(),
            top.toFloat(),
            max(x, edge).toFloat(),
            bottom.toFloat(),
            spanned.coversLineStart(this, start),
            spanned.coversLineEnd(this, end),
            stripeWidth / 2f,
        )
        paint.color = oldColor
        paint.style = oldStyle
    }
}

/** Reserves a list item's indent and draws its bullet or number on the item's first line. */
internal class ListMarkerSpan(
    private val item: ListItemNode,
    private val settings: SettingsAPI,
    private val themeBulletColor: Int,
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = item.run.indent + item.run.markerWidth

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        if ((text as? Spanned)?.getSpanStart(this) != start) return
        val run = item.run
        val markerStart = run.indent + run.inset
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth
        val oldAlign = paint.textAlign
        try {
            val label = item.label
            if (label != null) {
                // The message's own paint, right-aligned in the number column like current Discord.
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.LEFT
                val width = paint.measureText(label)
                val left = if (dir > 0) {
                    x + markerStart + run.labelColumn - width
                } else {
                    x - markerStart - run.labelColumn.toFloat()
                }
                canvas.drawText(label, left, baseline.toFloat(), paint)
            } else {
                paint.color = MarkdownAppearance.bullet.color(settings) ?: themeBulletColor
                // Filled at the top level, hollow when nested, like current Discord.
                paint.style = if (run.depth > 0) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = run.bulletStroke
                val radius = run.bulletRadius
                val center = x + dir * (markerStart + radius)
                val metrics = paint.fontMetrics
                canvas.drawCircle(center, baseline + (metrics.ascent + metrics.descent) / 2f, radius, paint)
            }
        } finally {
            paint.color = oldColor
            paint.style = oldStyle
            paint.strokeWidth = oldStroke
            paint.textAlign = oldAlign
        }
    }
}

/**
 * A code block's box. Unlike Discord's span, each line draws its own part, so the box still appears when
 * other text (such as the zero-width suffix) shares its last line or only some of its lines are on screen.
 */
internal class CodeBlockSpan(
    private val fill: Int,
    private val border: Int,
    private val borderWidth: Float,
    private val radius: Float,
    /** The margin of the quotes and list items around the block, which line backgrounds don't get. */
    private val indent: Int,
) : LineBackgroundSpan {
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawBackground(
        canvas: Canvas,
        ignored: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val spanned = text as? Spanned ?: return
        val first = spanned.coversLineStart(this, start)
        val last = spanned.coversLineEnd(this, end)
        val boxLeft = (left + indent).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = fill
        drawLinePart(
            canvas,
            paint,
            rect,
            boxLeft,
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat(),
            first,
            last,
            radius,
        )
        paint.style = Paint.Style.STROKE
        paint.color = border
        paint.strokeWidth = borderWidth
        drawLinePart(
            canvas,
            paint,
            rect,
            boxLeft,
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat(),
            first,
            last,
            radius,
        )
    }
}

/** Reads the scale when measuring, so visible messages follow the settings without being parsed again. */
internal class MarkdownSizeSpan(private val settings: SettingsAPI, private val size: MarkdownAppearance.TextSize) :
    RelativeSizeSpan(1f) {
    override fun updateDrawState(paint: TextPaint) {
        paint.textSize *= MarkdownAppearance.scale(settings, size)
    }

    override fun updateMeasureState(paint: TextPaint) = updateDrawState(paint)
}

/** A heading's custom color, read when drawing; keeps the theme color while no custom color is set. */
internal class HeadingColorSpan(
    private val settings: SettingsAPI,
    private val color: MarkdownAppearance.ColorSetting,
) : CharacterStyle(),
    UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) {
        color.color(settings)?.let { paint.color = it }
    }
}
