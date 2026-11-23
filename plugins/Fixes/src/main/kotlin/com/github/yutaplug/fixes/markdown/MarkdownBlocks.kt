package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.node.BasicRenderContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Mark blocks during rendering, then combine their margins once the complete paragraph is known. */
internal class QuoteMarker

/** [number] is the number written in the source for `1.` items, or null for `-` and `*` items. */
internal class BulletMarker(val level: Int, val number: Int?)

/** One list at one nesting depth. Like Discord, its first item decides whether it is numbered. */
internal class ListRun(
    val parent: ListRun?,
    val depth: Int,
    val ordered: Boolean,
    var next: Int,
) {
    var labelColumn = 0
    var indent = 0
    var markerWidth = 0
}

internal object MarkdownBlocks {
    fun <T : BasicRenderContext> renderQuote(node: Node<T>, builder: SpannableStringBuilder, context: T) {
        ensureLineStart(builder)
        val start = builder.length
        node.children?.forEach { it.render(builder, context) }
        if (builder.length == start) builder.append('​')
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
        val items = resolveLists(builder, context, bullets, quotes)

        var start = 0
        while (start < builder.length) {
            val newline = builder.indexOf('\n', start)
            val end = if (newline < 0) builder.length else newline + 1
            val inQuote = quotes.any { it.overlaps(start, end) }
            // A list item nested inside another one on the same line wins over its parent.
            val item = items.filter { it.range.overlaps(start, end) }.maxByOrNull { it.run.depth }
            if (inQuote || item != null) {
                builder.setSpan(
                    BlockMarginSpan(
                        context,
                        settings,
                        inQuote,
                        item?.run,
                        item != null && item.range.start == start,
                        item?.label,
                    ),
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

    /**
     * Groups consecutive items into lists the way Discord does: a list keeps the type of its first item,
     * so `-` items after `1.` continue the numbering, while a deeper indent starts its own list.
     */
    private fun resolveLists(
        builder: SpannableStringBuilder,
        context: Context,
        bullets: List<Range<BulletMarker>>,
        quotes: List<Range<QuoteMarker>>,
    ): List<ListItem> {
        if (bullets.isEmpty()) return emptyList()
        fun inQuote(position: Int) = quotes.any { position >= it.start && position < it.end }

        val items = ArrayList<ListItem>()
        val runs = ArrayList<ListRun>()
        val stack = ArrayList<ListRun>()
        var previous: Range<BulletMarker>? = null
        // Document order; an item nested on its parent's line comes right after the parent.
        val ordered = bullets.sortedWith(
            Comparator { a, b -> if (a.start != b.start) a.start - b.start else b.end - a.end },
        )
        for (item in ordered) {
            val depth = bullets
                .filter { it.start <= item.start && it.end >= item.end }
                .sumOf { it.marker.level }
                .coerceAtMost(8)
            val last = previous
            if (last != null &&
                (inQuote(last.start) != inQuote(item.start) || !onlySpace(builder, last.end, item.start))
            ) {
                stack.clear()
            }
            while (stack.isNotEmpty() && stack.last().depth > depth) stack.removeAt(stack.size - 1)
            val top = stack.lastOrNull()
            val run = if (top != null && top.depth == depth) {
                top
            } else {
                val number = item.marker.number
                ListRun(top, depth, number != null, number ?: 0).also {
                    stack.add(it)
                    runs.add(it)
                }
            }
            val label = if (run.ordered) "${run.next++}." else null
            items.add(ListItem(item, run, label))
            previous = item
        }

        val paint = labelPaint(context)
        val inset = MarkdownAppearance.dp(context, 2)
        val gap = MarkdownAppearance.dp(context, 6)
        val bulletSize = MarkdownAppearance.dp(context, 2).coerceAtLeast(1) * 2
        for (run in runs) {
            if (run.ordered) {
                val widest = items.filter { it.run === run }.maxOfOrNull { paint.measureText(it.label ?: "") } ?: 0f
                // Leave a little room in case Discord's font is wider than the estimate.
                run.labelColumn = ceil(widest * 1.05f).toInt()
            }
            run.markerWidth = inset + (if (run.ordered) run.labelColumn else bulletSize) + gap
            // Nested lists line up with the text of the item they belong to.
            run.indent = run.parent?.let { it.indent + it.markerWidth } ?: 0
        }
        return items
    }

    private fun onlySpace(builder: SpannableStringBuilder, start: Int, end: Int): Boolean {
        for (index in start until end) {
            if (!Character.isWhitespace(builder[index])) return false
        }
        return true
    }

    @Volatile private var chatFont: Typeface? = null

    /** Estimates Discord's chat text, only to size the number column; numbers are drawn with the real paint. */
    private fun labelPaint(context: Context) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13.75f, context.resources.displayMetrics)
        val font = chatFont ?: try {
            val id = Utils.getResId("whitney_medium", "font")
            (if (id == 0) null else ResourcesCompat.getFont(context, id))?.also { chatFont = it }
        } catch (_: Throwable) {
            null
        }
        if (font != null) typeface = font
    }

    internal class ListItem(val range: Range<BulletMarker>, val run: ListRun, val label: String?)

    internal data class Range<T>(
        val marker: T,
        val start: Int,
        val end: Int,
    ) {
        fun overlaps(lineStart: Int, lineEnd: Int): Boolean = start < lineEnd && end > lineStart
    }
}

/** A single margin always draws the quote stripe before the list marker, regardless of AST nesting. */
internal class BlockMarginSpan(
    context: Context,
    private val settings: SettingsAPI,
    private val quoted: Boolean,
    private val list: ListRun?,
    private val hasMarker: Boolean,
    private val label: String?,
) : LeadingMarginSpan {
    private val stripeWidth = MarkdownAppearance.dp(context, 3).coerceAtLeast(1)
    private val quoteMargin = if (quoted) stripeWidth + MarkdownAppearance.dp(context, 8) else 0
    private val radius = MarkdownAppearance.dp(context, 2).coerceAtLeast(1).toFloat()
    private val markerInset = MarkdownAppearance.dp(context, 2)
    private val quoteColor = MarkdownAppearance.themedColor(
        context,
        "theme_chat_block_quote_divider",
        Color.rgb(79, 84, 92),
    )
    private val defaultBulletColor = MarkdownAppearance.themedColor(context, "primary_400", Color.LTGRAY)
    private val strokeWidth = MarkdownAppearance.dp(context, 1).coerceAtLeast(1).toFloat()

    override fun getLeadingMargin(first: Boolean): Int =
        quoteMargin + if (list != null) list.indent + list.markerWidth else 0

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
        val oldAlign = paint.textAlign
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
            if (list == null || !hasMarker || (text as? Spanned)?.getSpanStart(this) != start) return
            val markerStart = quoteMargin + list.indent + markerInset
            if (label != null) {
                // Numbers use the message's own text paint and line up on their right edge, like Discord.
                paint.color = oldColor
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.LEFT
                val width = paint.measureText(label)
                val left = if (dir > 0) {
                    x + markerStart + list.labelColumn - width
                } else {
                    x - markerStart - list.labelColumn.toFloat()
                }
                canvas.drawText(label, left.toFloat(), baseline.toFloat(), paint)
            } else {
                paint.color = MarkdownAppearance.bulletColor(settings, defaultBulletColor)
                paint.style = if (list.depth > 1) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = strokeWidth
                val center = x + dir * (markerStart + radius)
                // drawCircle respects each span's radius on both software and hardware canvases.
                canvas.drawCircle(center, (top + bottom) / 2f, radius, paint)
            }
        } finally {
            paint.color = oldColor
            paint.style = oldStyle
            paint.strokeWidth = oldStroke
            paint.textAlign = oldAlign
        }
    }
}
