package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.core.graphics.ColorUtils
import com.discord.simpleast.code.CodeNode
import com.discord.utilities.textprocessing.node.BasicRenderContext
import java.util.regex.Pattern

/**
 * ```diff code blocks, highlighted like Discord's web app (highlight.js's diff language): added lines green,
 * removed lines red, hunk headers (@@ -1,2 +1,2 @@) yellow and file headers (---, +++, diff --git) grey.
 */
internal object DiffCodeBlocks {
    val languages = setOf("diff", "patch")

    private val meta = Pattern.compile("^(?:@@ +-\\d+,\\d+ +\\+\\d+,\\d+ +@@|\\*\\*\\* +\\d+,\\d+ +\\*\\*\\*\\*$|--- +\\d+,\\d+ +----$)")
    private val lineComment = Pattern.compile("^(?:index|-{3}|\\*{3} |\\+{3}|diff --git|\\*{15}$)")
    private val inlineComment = Pattern.compile("Index: |={3,}")

    // Discord web's --text-code-* and --background-code-* colors: dark, light and midnight themes.
    private val dark = Palette(
        addition = hsl(136.901f, 0.53383f, 0.73922f),
        deletion = hsl(3.429f, 1f, 0.86275f),
        decorator = hsl(38.844f, 1f, 0.66078f),
        comment = hsl(240f, 0.0411f, 0.71373f),
    )
    private val light = Palette(
        addition = hsl(150.316f, 1f, 0.18627f),
        deletion = hsl(354.803f, 0.67196f, 0.37059f),
        decorator = hsl(31.765f, 1f, 0.26667f),
        comment = hsl(234f, 0.04545f, 0.43137f),
    )
    private val midnight = Palette(
        addition = hsl(139.31f, 0.361f, 0.52745f),
        deletion = hsl(1.385f, 0.87838f, 0.7098f),
        decorator = hsl(38.455f, 1f, 0.43137f),
        comment = hsl(233.333f, 0.0393f, 0.55098f),
    )
    private val additionBackground = ColorUtils.setAlphaComponent(hsl(151.128f, 1f, 0.26078f), 31)
    private val deletionBackground = ColorUtils.setAlphaComponent(hsl(355.636f, 0.64706f, 0.5f), 31)

    fun render(node: CodeNode<BasicRenderContext>, builder: SpannableStringBuilder, context: BasicRenderContext) {
        val start = builder.length
        val raw = node.content
        builder.append(raw)
        if (builder.length == start) return
        node.b.get(context).forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val palette = palette(context.context)

        fun color(from: Int, to: Int, foreground: Int, background: Int? = null) {
            if (to <= from) return
            builder.setSpan(ForegroundColorSpan(foreground), start + from, start + to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (background != null) {
                builder.setSpan(BackgroundColorSpan(background), start + from, start + to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        var lineStart = 0
        while (lineStart <= raw.length) {
            val newline = raw.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) raw.length else newline
            val line = raw.substring(lineStart, lineEnd)
            val metaMatch = meta.matcher(line)
            when {
                metaMatch.find() -> color(lineStart, lineStart + metaMatch.end(), palette.decorator)
                lineComment.matcher(line).find() -> color(lineStart, lineEnd, palette.comment)
                line.startsWith("+") || line.startsWith("!") ->
                    color(lineStart, lineEnd, palette.addition, additionBackground)
                line.startsWith("-") -> color(lineStart, lineEnd, palette.deletion, deletionBackground)
                else -> {
                    val inline = inlineComment.matcher(line)
                    if (inline.find()) color(lineStart + inline.start(), lineEnd, palette.comment)
                }
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
    }

    private fun palette(context: Context): Palette {
        val color = MarkdownAppearance.themedColor(context, "theme_chat_code", Color.rgb(47, 49, 54))
        val luminance = (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000
        return when {
            luminance > 150 -> light
            luminance < 28 -> midnight
            else -> dark
        }
    }

    private fun hsl(hue: Float, saturation: Float, lightness: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))

    private class Palette(
        val addition: Int,
        val deletion: Int,
        val decorator: Int,
        val comment: Int,
    )
}
