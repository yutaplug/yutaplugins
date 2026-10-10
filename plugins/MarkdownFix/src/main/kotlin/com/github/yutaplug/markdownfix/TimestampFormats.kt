package com.github.yutaplug.markdownfix

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import java.text.DateFormat
import java.util.Date
import java.util.regex.Matcher
import java.util.regex.Pattern

/** `<t:unix:s>` and `<t:unix:S>` short date with short or medium time, which the native rule doesn't know. */
internal class ShortDateTimestampRule : MessageRule(Pattern.compile("^<t:(-?\\d{1,17}):([sS])>")) {
    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec {
        val date = Date(match.group(1)!!.toLong() * 1000)
        val timeStyle = if (match.group(2) == "S") DateFormat.MEDIUM else DateFormat.SHORT
        return MessageSpec(
            TimestampNode(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, timeStyle).format(date),
                DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(date),
            ),
            state,
        )
    }
}

/** Renders like Discord's own TimestampNode: accent background, tap shows the full date. */
private class TimestampNode(private val formatted: String, private val full: String) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val start = builder.length
        builder.append(formatted)
        val end = builder.length
        builder.setSpan(
            ClickableSpan(
                null,
                false,
                null,
                {
                    context.onTimestampClicked(full)
                    kotlin.Unit.a
                },
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val background =
            MarkdownAppearance.themedColor(context.context, "colorBackgroundModifierAccent", Color.TRANSPARENT)
        builder.setSpan(BackgroundColorSpan(background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
