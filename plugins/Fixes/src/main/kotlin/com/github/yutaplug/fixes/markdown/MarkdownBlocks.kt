package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import com.aliucord.api.SettingsAPI
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.node.BasicRenderContext
import kotlin.math.max
import kotlin.math.min

/** Mark blocks during rendering, then combine their margins once the complete paragraph is known. */
internal class QuoteMarker

internal class BulletMarker(val level: Int)

internal object MarkdownBlocks {
    fun <T : BasicRenderContext> renderQuote(node: Node<T>, builder: SpannableStringBuilder, context: T) {
        ensureLineStart(builder)
        val start = builder.length
        node.children?.forEach { it.render(builder, context) }
        if (builder.length == start) builder.append('\u200B')
        val end = contentEnd(builder, start)
        builder.setSpan(QuoteMarker(), start, max(start + 1, end), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ensureLineStart(builder)
    }

    fun ensureLineStart(builder: SpannableStringBuilder) {
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
    }

    fun contentEnd(builder: SpannableStringBuilder, start: Int): Int =
        if (builder.length > start && builder.last() == '\n') builder.length - 1 else builder.length

    fun apply(builder: SpannableStringBuilder, context: Context, settings: SettingsAPI) {
        val quotes = builder.getSpans(0, builder.length, QuoteMarker::class.java).map {
            Range(it, builder.getSpanStart(it), builder.getSpanEnd(it))
        }
        val bullets = builder.getSpans(0, builder.length, BulletMarker::class.java).map {
            Range(it, builder.getSpanStart(it), builder.getSpanEnd(it))
        }
        if (quotes.isEmpty() && bullets.isEmpty()) return

        var start = 0
        while (start < builder.length) {
            val newline = builder.indexOf('\n', start)
            val end = if (newline < 0) builder.length else newline + 1
            val inQuote = quotes.any { it.overlaps(start, end) }
            val listItems = bullets.filter { it.overlaps(start, end) }
            if (inQuote || listItems.isNotEmpty()) {
                val level = listItems.sumOf { it.marker.level }.coerceAtMost(8)
                val drawBullet = listItems.any { it.start == start }
                builder.setSpan(
                    BlockMarginSpan(context, settings, inQuote, level, drawBullet),
                    start,
                    end,
                    // Code blocks add their own padding. Draw the quote at the paragraph edge first.
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (1 shl Spanned.SPAN_PRIORITY_SHIFT),
                )
            }
            start = end
        }
        quotes.forEach { builder.removeSpan(it.marker) }
        bullets.forEach { builder.removeSpan(it.marker) }
    }

    private data class Range<T>(
        val marker: T,
        val start: Int,
        val end: Int,
    ) {
        fun overlaps(lineStart: Int, lineEnd: Int): Boolean = start < lineEnd && end > lineStart
    }
}

/** A single margin always draws the quote stripe before the bullet, regardless of AST nesting. */
internal class BlockMarginSpan(
    context: Context,
    private val settings: SettingsAPI,
    private val quoted: Boolean,
    private val level: Int,
    private val hasBullet: Boolean,
) : LeadingMarginSpan {
    private val stripeWidth = MarkdownAppearance.dp(context, 3).coerceAtLeast(1)
    private val quoteMargin = if (quoted) stripeWidth + MarkdownAppearance.dp(context, 8) else 0
    private val radius = MarkdownAppearance.dp(context, 2).coerceAtLeast(1).toFloat()
    private val bulletInset = MarkdownAppearance.dp(context, 2)
    private val bulletGap = MarkdownAppearance.dp(context, 6)
    private val nestedIndent = MarkdownAppearance.dp(context, 12) * (level - 1).coerceAtLeast(0)
    private val quoteColor = MarkdownAppearance.themedColor(
        context,
        "theme_chat_block_quote_divider",
        Color.rgb(79, 84, 92),
    )
    private val defaultBulletColor = MarkdownAppearance.themedColor(context, "primary_400", Color.LTGRAY)
    private val strokeWidth = MarkdownAppearance.dp(context, 1).coerceAtLeast(1).toFloat()

    override fun getLeadingMargin(first: Boolean): Int =
        quoteMargin + if (level > 0) nestedIndent + bulletInset + (radius * 2).toInt() + bulletGap else 0

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
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth
        try {
            if (quoted) {
                paint.color = quoteColor
                paint.style = Paint.Style.FILL
                val stripeEnd = x + dir * stripeWidth
                canvas.drawRect(
                    min(x, stripeEnd).toFloat(),
                    top.toFloat(),
                    max(x, stripeEnd).toFloat(),
                    bottom.toFloat(),
                    paint,
                )
            }
            if (hasBullet && (text as? Spanned)?.getSpanStart(this) == start) {
                paint.color = MarkdownAppearance.bulletColor(settings, defaultBulletColor)
                paint.style = if (level > 1) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = strokeWidth
                val center = x + dir * (quoteMargin + nestedIndent + bulletInset + radius)
                // drawCircle respects each span's radius on both software and hardware canvases.
                canvas.drawCircle(center, (top + bottom) / 2f, radius, paint)
            }
        } finally {
            paint.color = oldColor
            paint.style = oldStyle
            paint.strokeWidth = oldStroke
        }
    }
}
