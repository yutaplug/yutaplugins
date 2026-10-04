package com.github.yutaplug.markdownfix

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import java.util.regex.Matcher
import java.util.regex.Pattern

/** `</name:id>` and `</name sub:id>` command mentions, adapted from Wing's MoreHighlight. */
internal class SlashCommandMentionRule :
    MessageRule(Pattern.compile("^</([^\\s:<>][^:<>\\n]{0,99}):([0-9]{1,20})>")) {
    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec = MessageSpec(SlashCommandMentionNode(match.group(1)!!.trim()), state)
}

private class SlashCommandMentionNode(private val command: String) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val color = ColorCompat.getThemedColor(context.context, context.linkColorAttrResId)
        val start = builder.length
        builder.append("/").append(command)
        val end = builder.length
        builder.setSpan(
            ClickableSpan(
                color,
                false,
                null,
                {
                    // Like newer clients, tapping fills the chat box so the command can be run from autocomplete.
                    StoreStream.getChat().replaceChatText("/$command ")
                    kotlin.Unit.a
                },
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            BackgroundColorSpan(Color.argb(BACKGROUND_ALPHA, Color.red(color), Color.green(color), Color.blue(color))),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private companion object {
        const val BACKGROUND_ALPHA = 25
    }
}
