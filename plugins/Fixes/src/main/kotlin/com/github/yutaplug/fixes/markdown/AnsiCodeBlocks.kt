package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.discord.simpleast.code.CodeNode
import com.discord.utilities.textprocessing.node.BasicRenderContext
import java.util.regex.Pattern

internal object AnsiCodeBlocks {
    private val escape = Pattern.compile("\u001B\\[([0-9;]*)m")
    private val palettes = arrayOf(
        intArrayOf(
            0xFF000000.toInt(),
            0xFFE75858.toInt(),
            0xFF399B5D.toInt(),
            0xFFC07600.toInt(),
            0xFF3789EA.toInt(),
            0xFFE444BB.toInt(),
            0xFF0098A3.toInt(),
            0xFFB6B7BC.toInt(),
        ),
        intArrayOf(
            0xFF000000.toInt(),
            0xFFEC6361.toInt(),
            0xFF45A366.toInt(),
            0xFFCE8100.toInt(),
            0xFF4591EC.toInt(),
            0xFFF549C9.toInt(),
            0xFF049FAA.toInt(),
            0xFFB6B7BC.toInt(),
        ),
        intArrayOf(
            0xFF000000.toInt(),
            0xFFDE464A.toInt(),
            0xFF1B8D4D.toInt(),
            0xFFA56100.toInt(),
            0xFF1A7CE6.toInt(),
            0xFFD53FAE.toInt(),
            0xFF008995.toInt(),
            0xFFB6B7BC.toInt(),
        ),
        intArrayOf(
            0xFF000000.toInt(),
            0xFFD22D39.toInt(),
            0xFF008043.toInt(),
            0xFFA56100.toInt(),
            0xFF006DD4.toInt(),
            0xFFBC3699.toInt(),
            0xFF007C87.toInt(),
            0xFFB6B7BC.toInt(),
        ),
    )

    fun render(node: CodeNode<BasicRenderContext>, builder: SpannableStringBuilder, context: BasicRenderContext) {
        val start = builder.length
        val raw = node.content
        var style = Style()
        val segments = mutableListOf<Segment>()
        val plain = StringBuilder(raw.length)
        val matcher = escape.matcher(raw)
        val palette = palette(context.context)
        var cursor = 0

        fun append(end: Int) {
            if (end <= cursor) return
            val segmentStart = plain.length
            plain.append(raw, cursor, end)
            segments.add(Segment(segmentStart, plain.length, style.copy()))
        }

        while (matcher.find()) {
            append(matcher.start())
            for (rawCode in matcher.group(1).orEmpty().split(';')) {
                val code = if (rawCode.isEmpty()) 0 else rawCode.toIntOrNull() ?: continue
                when (code) {
                    0 -> style = Style()
                    1 -> style.bold = true
                    4 -> style.underline = true
                    22 -> style.bold = false
                    24 -> style.underline = false
                    in 30..37 -> style.foreground = palette[code - 30]
                    39 -> style.foreground = null
                    in 40..47 -> style.background = palette[code - 40]
                    49 -> style.background = null
                }
            }
            cursor = matcher.end()
        }
        append(raw.length)
        builder.append(plain)
        if (builder.length == start) return
        node.b.get(context).forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        for (segment in segments) {
            val from = start + segment.start
            val to = start + segment.end
            segment.style.foreground?.let {
                builder.setSpan(
                    ForegroundColorSpan(it),
                    from,
                    to,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            segment.style.background?.let {
                builder.setSpan(
                    BackgroundColorSpan(it),
                    from,
                    to,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (segment.style.bold) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    from,
                    to,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (segment.style.underline) builder.setSpan(UnderlineSpan(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun palette(context: Context): IntArray {
        val color = MarkdownAppearance.themedColor(context, "theme_chat_code", Color.rgb(47, 49, 54))
        val luminance = (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000
        return palettes[
            when {
                luminance > 150 -> 0
                luminance < 28 -> 3
                luminance < 47 -> 2
                else -> 1
            },
        ]
    }

    private data class Style(
        var foreground: Int? = null,
        var background: Int? = null,
        var bold: Boolean = false,
        var underline: Boolean = false,
    )

    private data class Segment(
        val start: Int,
        val end: Int,
        val style: Style,
    )
}
