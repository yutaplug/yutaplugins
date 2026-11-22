package com.github.yutaplug.fixes.markdown

import SpoilerSpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.string.StringUtilsKt
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.UrlNode

internal object MarkdownLinks {
    fun render(
        node: UrlNode<MessageRenderContext>,
        builder: SpannableStringBuilder,
        context: MessageRenderContext,
        label: String,
        children: Collection<MessageNode>,
    ) {
        val start = builder.length
        children.forEach { it.render(builder, context) }
        val end = builder.length
        if (end <= start) return
        val url = try {
            StringUtilsKt.toPunyCodeASCIIUrl(node.url)
        } catch (_: Exception) {
            node.url
        }
        val color = ColorCompat.getThemedColor(context.context, context.linkColorAttrResId)
        val protected = mutableListOf<Pair<Int, Int>>()
        // Link colors must never paint over a hidden spoiler, including in read-only previews.
        builder.getSpans(start, end, SpoilerSpan::class.java).filter { !it.l }.forEach {
            protected.add(builder.getSpanStart(it) to builder.getSpanEnd(it))
        }
        builder.getSpans(start, end, ClickableSpan::class.java).forEach {
            protected.add(builder.getSpanStart(it) to builder.getSpanEnd(it))
        }

        fun link(from: Int, to: Int) {
            if (to <= from) return
            builder.setSpan(
                ClickableSpan(
                    color,
                    false,
                    {
                        context.onLongPressUrl?.invoke(url)
                        kotlin.Unit.a
                    },
                    { view ->
                        context.onClickUrl?.invoke(view.context, url, label)
                        kotlin.Unit.a
                    },
                ),
                from,
                to,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        var cursor = start
        for ((from, to) in protected.sortedBy { it.first }) {
            link(cursor, from.coerceIn(start, end))
            cursor = maxOf(cursor, to.coerceIn(start, end))
        }
        link(cursor, end)
    }
}
