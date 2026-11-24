package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.simpleast.code.CodeNode
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.spans.VerticalPaddingSpan
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.Spoilerable
import kotlin.math.ceil

/**
 * The blocks being rendered on this thread, outermost first, with the margin each one adds. Lets a nested
 * block know its depth (for span priority) and how far its content is indented (for code boxes).
 */
internal object BlockRender {
    private val margins = object : ThreadLocal<ArrayList<Int>>() {
        override fun initialValue() = ArrayList<Int>()
    }

    val depth: Int get() = margins.get()!!.size

    val indent: Int get() = margins.get()!!.sum()

    fun <T> within(margin: Int, block: () -> T): T {
        val stack = margins.get()!!
        stack.add(margin)
        try {
            return block()
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    fun ensureLineStart(builder: SpannableStringBuilder) {
        if (builder.isNotEmpty() && builder[builder.length - 1] != '\n') builder.append('\n')
    }

    fun renderChildren(node: MessageNode, builder: SpannableStringBuilder, context: MessageRenderContext) {
        node.children?.forEach { it.render(builder, context) }
    }

    /** Applies [span] to [start]..[end] except over links and mentions, which keep their own color. */
    fun setAroundLinks(builder: SpannableStringBuilder, start: Int, end: Int, span: () -> Any) {
        var cursor = start
        val links = builder.getSpans(start, end, ClickableSpan::class.java).sortedBy(builder::getSpanStart)
        for (link in links) {
            val linkStart = builder.getSpanStart(link).coerceIn(start, end)
            if (linkStart > cursor) builder.setSpan(span(), cursor, linkStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = maxOf(cursor, builder.getSpanEnd(link).coerceIn(start, end))
        }
        if (cursor < end) builder.setSpan(span(), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** `> ` and `>>> ` quotes: consecutive lines arrive as one node, so they share one stripe. */
internal class QuoteNode : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val ctx = context.context
        val stripe = MarkdownAppearance.dp(ctx, 3).coerceAtLeast(1)
        val gap = MarkdownAppearance.dp(ctx, 8)
        val depth = BlockRender.depth
        BlockRender.ensureLineStart(builder)
        val start = builder.length
        BlockRender.within(stripe + gap) { BlockRender.renderChildren(this, builder, context) }
        if (builder.length == start) builder.append('​')
        val color = MarkdownAppearance.themedColor(ctx, "theme_chat_block_quote_divider", Color.rgb(79, 84, 92))
        builder.setSpan(QuoteStripeSpan(color, stripe, gap), start, builder.length, blockFlags(depth))
    }
}

/**
 * One list at one nesting level. Like Discord, its first item decides whether it is numbered and where
 * numbering starts. Pixel sizes are measured once when the first item renders.
 */
internal class ListRun(
    val parent: ListRun?,
    val ordered: Boolean,
    var next: Int,
) {
    val depth: Int = if (parent == null) 0 else parent.depth + 1
    val labels = ArrayList<String>()

    var inset = 0
    var labelColumn = 0
    var markerWidth = 0
    var indent = 0
    var bulletRadius = 0f
    var bulletStroke = 0f
    private var measured = false

    fun measure(context: Context) {
        if (measured) return
        measured = true
        parent?.measure(context)
        inset = MarkdownAppearance.dp(context, 2)
        bulletRadius = MarkdownAppearance.dp(context, 2).coerceAtLeast(1).toFloat()
        bulletStroke = MarkdownAppearance.dp(context, 1).coerceAtLeast(1).toFloat()
        val gap = MarkdownAppearance.dp(context, 6)
        if (ordered) {
            val paint = labelPaint(context)
            // Leave some room in case the chat font is wider than this estimate; numbers use the real paint.
            labelColumn = ceil((labels.maxOfOrNull { paint.measureText(it) } ?: 0f) * 1.1f).toInt()
        }
        markerWidth = inset + (if (ordered) labelColumn else (bulletRadius * 2).toInt()) + gap
        // Nested lists line up with the text of the item they belong to.
        indent = parent?.let { it.indent + it.markerWidth } ?: 0
    }

    private companion object {
        @Volatile var chatFont: Typeface? = null

        /** Estimates Discord's chat text, only to size the number column. */
        fun labelPaint(context: Context) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics)
            val font = chatFont ?: try {
                val id = Utils.getResId("whitney_medium", "font")
                (if (id == 0) null else ResourcesCompat.getFont(context, id))?.also { chatFont = it }
            } catch (_: Throwable) {
                null
            }
            if (font != null) typeface = font
        }
    }
}

/** A `-`, `*` or `1.` line. [level] comes from its indent; the parser assigns [run] and [label]. */
internal class ListItemNode(
    private val settings: SettingsAPI,
    val level: Int,
    val number: Int?,
    private val newline: Boolean,
) : MessageNode() {
    lateinit var run: ListRun
    var label: String? = null

    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val ctx = context.context
        run.measure(ctx)
        val depth = BlockRender.depth
        BlockRender.ensureLineStart(builder)
        val start = builder.length
        BlockRender.within(run.indent + run.markerWidth) { BlockRender.renderChildren(this, builder, context) }
        if (builder.length == start) builder.append('​')
        if (newline) BlockRender.ensureLineStart(builder)
        val themeColor = MarkdownAppearance.themedColor(ctx, "primary_400", Color.LTGRAY)
        builder.setSpan(ListMarkerSpan(this, settings, themeColor), start, builder.length, blockFlags(depth))
    }
}

/** `#`, `##`, `###` headings ([level] 1–3) and `-#` subtext (level 0). */
internal class HeadingNode(
    private val settings: SettingsAPI,
    private val level: Int,
    private val newline: Boolean,
) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        BlockRender.ensureLineStart(builder)
        val start = builder.length
        BlockRender.renderChildren(this, builder, context)
        val end = builder.length
        if (end > start) {
            val size = MarkdownAppearance.sizes[if (level == 0) 3 else level - 1]
            builder.setSpan(MarkdownSizeSpan(settings, size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (level == 0) {
                val muted = MarkdownAppearance.themedColor(context.context, "colorTextMuted", Color.GRAY)
                BlockRender.setAroundLinks(builder, start, end) { ForegroundColorSpan(muted) }
            } else {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val color = MarkdownAppearance.headingColors[level - 1]
                BlockRender.setAroundLinks(builder, start, end) { HeadingColorSpan(settings, color) }
            }
        }
        if (newline) BlockRender.ensureLineStart(builder)
    }
}

/**
 * A fenced code block, drawn as a box on its own lines. Wraps the [CodeNode] from Discord's rule so syntax
 * highlighting and ANSI colors still apply; a hidden spoiler turns the box into the spoiler color.
 */
internal class CodeBlockNode(code: CodeNode<MessageRenderContext>) :
    MessageNode(),
    Spoilerable {
    private var revealed = true

    init {
        addChild(code)
    }

    override fun isRevealed() = revealed

    override fun setRevealed(revealed: Boolean) {
        this.revealed = revealed
    }

    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val ctx = context.context
        val depth = BlockRender.depth
        val indent = BlockRender.indent
        BlockRender.ensureLineStart(builder)
        val start = builder.length
        BlockRender.renderChildren(this, builder, context)
        if (builder.length == start) builder.append('​')
        // Text after the block starts on a new line; Discord's renderer trims this at the very end.
        BlockRender.ensureLineStart(builder)
        val end = builder.length
        val fill = if (revealed) {
            MarkdownAppearance.themedColor(ctx, "theme_chat_code", Color.rgb(47, 49, 54))
        } else {
            (context as? SpoilerNode.RenderContext)?.spoilerColorRes
                ?: MarkdownAppearance.themedColor(ctx, "theme_chat_spoiler_bg", Color.BLACK)
        }
        val border = MarkdownAppearance.themedColor(ctx, "theme_chat_codeblock_border", Color.TRANSPARENT)
        val box = CodeBlockSpan(
            fill,
            border,
            MarkdownAppearance.dp(ctx, 1).coerceAtLeast(1).toFloat(),
            MarkdownAppearance.dp(ctx, 4).toFloat(),
            indent,
        )
        builder.setSpan(box, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(LeadingMarginSpan.Standard(MarkdownAppearance.dp(ctx, 6)), start, end, blockFlags(depth))
        val padding = MarkdownAppearance.dp(ctx, 5)
        builder.setSpan(VerticalPaddingSpan(padding, padding), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** `#RRGGBB` and `#RRGGBBAA` shown in their own color. */
internal class HexColorNode(private val text: String, private val color: Int) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        builder.setSpan(ForegroundColorSpan(color), start, end, flags)
        builder.setSpan(
            BackgroundColorSpan(Color.argb(25, Color.red(color), Color.green(color), Color.blue(color))),
            start,
            end,
            flags,
        )
        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
    }
}
