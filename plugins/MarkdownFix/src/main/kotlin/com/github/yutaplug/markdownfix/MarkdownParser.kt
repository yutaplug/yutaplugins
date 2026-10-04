package com.github.yutaplug.markdownfix

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import b.a.t.b.b.e
import com.aliucord.api.SettingsAPI
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.UrlNode
import java.util.regex.Matcher
import java.util.regex.Pattern

internal typealias MessageNode = Node<MessageRenderContext>
internal typealias MessageParser = Parser<MessageRenderContext, MessageNode, MessageParseState>
internal typealias MessageRule = Rule<MessageRenderContext, MessageNode, MessageParseState>
internal typealias MessageSpec = ParseSpec<MessageRenderContext, MessageParseState>

/** Each parse owns its rules and matchers; nested blocks cannot change their parent's last match. */
internal class MarkdownParser(private val settings: SettingsAPI, private val games: GameProfileResolver) {
    fun parse(
        source: CharSequence,
        state: MessageParseState = MessageParseState.`access$getInitialState$cp`(),
        blocks: Boolean = true,
        maskedLinks: Boolean = true,
        urls: Boolean = true,
        depth: Int = 0,
    ): MutableList<MessageNode> {
        val parser = MessageParser(false)
        val rules = Rules.INSTANCE
        parser.addRule(rules.createSoftHyphenRule())
        parser.addRule(EscapeRule())
        if (blocks && depth < MAX_BLOCK_DEPTH) parser.addRule(QuoteRule(depth))
        if (blocks) parser.addRule(rules.createCodeBlockRule())
        parser.addRule(rules.createInlineCodeRule())
        parser.addRule(rules.createSpoilerRule())
        parser.addRule(HexColorRule())
        if (maskedLinks && depth < MAX_BLOCK_DEPTH) parser.addRule(MaskedLinkRule(depth))
        if (urls) {
            parser.addRule(rules.createUrlNoEmbedRule())
            parser.addRule(rules.createUrlRule())
        }
        parser.addRule(rules.createCustomEmojiRule())
        parser.addRule(rules.createNamedEmojiRule())
        parser.addRule(rules.createUnescapeEmoticonRule())
        parser.addRule(rules.createChannelMentionRule())
        parser.addRule(rules.createRoleMentionRule())
        parser.addRule(rules.createUserMentionRule())
        parser.addRule(GameProfileMentionRule(games))
        parser.addRule(SlashCommandMentionRule())
        parser.addRule(UnicodeEmojiRule(rules.createUnicodeEmojiRule()))
        parser.addRule(rules.createTimestampRule())
        if (blocks && depth < MAX_BLOCK_DEPTH) {
            parser.addRule(TextBlockRule(HEADER, depth, false))
            parser.addRule(TextBlockRule(SUBTEXT, depth, true))
            parser.addRule(ListRule(depth))
        }
        parser.addRules(e.a<MessageRenderContext, MessageParseState>(false, false))
        parser.addRule(rules.createTextReplacementRule())
        return parser.parse(source.toString().replace("\r\n", "\n"), state)
    }

    private inner class QuoteRule(private val depth: Int) :
        Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(QUOTE) {
        override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? =
            if (state.isInQuote) null else super.match(source, previous, state)

        override fun parse(
            match: Matcher,
            parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
            state: MessageParseState,
        ): MessageSpec {
            val body = match.group(1) ?: match.group(2).orEmpty()
            val node = BlockQuoteNode<MessageRenderContext>()
            this@MarkdownParser.parse(body, state.newBlockQuoteState(true), depth = depth + 1).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    private inner class ListRule(private val depth: Int) :
        Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(LIST) {
        override fun parse(
            match: Matcher,
            parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
            state: MessageParseState,
        ): MessageSpec {
            val width = match.group(1).orEmpty().fold(0) { width, character -> width + if (character == '\t') 4 else 1 }
            val level = (1 + width / 2).coerceAtMost(8)
            val node = ListItemNode(level, match.group(3) == "\n")
            this@MarkdownParser.parse(match.group(2).orEmpty(), state, depth = depth + 1).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    private inner class TextBlockRule(
        pattern: Pattern,
        private val depth: Int,
        private val subtext: Boolean,
    ) : Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(pattern) {
        override fun parse(
            match: Matcher,
            parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
            state: MessageParseState,
        ): MessageSpec {
            val sizeIndex = if (subtext) 3 else match.group(1)!!.length - 1
            val size = MarkdownAppearance.sizes[sizeIndex]
            val bodyGroup = if (subtext) 1 else 2
            val node = TextBlockNode(settings, size, subtext, match.group(bodyGroup + 1) == "\n")
            this@MarkdownParser
                .parse(
                    match.group(bodyGroup).orEmpty(),
                    state,
                    blocks = false,
                    depth = depth + 1,
                ).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    private inner class MaskedLinkRule(private val depth: Int) : MessageRule(NEVER) {
        private val native = Rules.INSTANCE.createMaskedLinkRule<MessageRenderContext, MessageParseState>()

        override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? =
            native.match(source, previous, state)

        override fun parse(
            match: Matcher,
            parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
            state: MessageParseState,
        ): MessageSpec {
            val spec = native.parse(match, parser, state)
            val node = spec.a as UrlNode<MessageRenderContext>
            // Labels belong in the AST so spoilers, mentions, and emoji receive normal preprocessing.
            this@MarkdownParser
                .parse(
                    match.group(1).orEmpty(),
                    state,
                    blocks = false,
                    maskedLinks = false,
                    urls = false,
                    depth = depth + 1,
                ).forEach(node::addChild)
            return spec
        }
    }

    private companion object {
        const val MAX_BLOCK_DEPTH = 32
        val NEVER: Pattern = Pattern.compile("(?!x)x")
        val QUOTE: Pattern = Pattern.compile(
            "^(?:[ \\t]*>>>[ \\t]+([\\s\\S]*)|[ \\t]*>(?!>>)(?:[ \\t]+|(?=\\n|$))([^\\n]*(?:\\n|$)))",
        )
        val LIST: Pattern = Pattern.compile("^([ \\t]*)[*-][ \\t]+([^\\n]*?)[ \\t]*(\\n|$)")
        val HEADER: Pattern = Pattern.compile("^[ \\t]*(#{1,3})[ \\t]+([^\\n]*?)[ \\t]*(\\n|$)")
        val SUBTEXT: Pattern = Pattern.compile("^[ \\t]*-#[ \\t]+([^\\n]*?)[ \\t]*(\\n|$)")
    }
}

private class EscapeRule : MessageRule(Pattern.compile("^\\\\([^0-9A-Za-z\\s])")) {
    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec = MessageSpec(b.a.t.b.a.a<MessageRenderContext>(match.group(1).orEmpty()), state)
}

/** Match complete hex tokens without coloring a prefix of a longer value. */
private class HexColorRule : MessageRule(Pattern.compile("^#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?![0-9a-fA-F])")) {
    override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? =
        if (previous?.lastOrNull()?.isLetterOrDigit() == true) null else super.match(source, previous, state)

    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec = MessageSpec(HexColorNode(match.group(), Color.parseColor(match.group())), state)
}

private class HexColorNode(private val text: String, private val color: Int) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val start = builder.length
        builder.append(text)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        builder.setSpan(ForegroundColorSpan(color), start, builder.length, flags)
        builder.setSpan(
            android.text.style.BackgroundColorSpan(
                Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)),
            ),
            start,
            builder.length,
            flags,
        )
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, flags)
    }
}

private class ListItemNode(private val level: Int, private val newline: Boolean) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        MarkdownBlocks.ensureLineStart(builder)
        val start = builder.length
        children?.forEach { it.render(builder, context) }
        if (builder.length == start) builder.append('\u200B')
        builder.setSpan(
            BulletMarker(level),
            start,
            MarkdownBlocks.contentEnd(builder, start).coerceAtLeast(start + 1),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (newline) MarkdownBlocks.ensureLineStart(builder)
    }
}

private class TextBlockNode(
    private val settings: SettingsAPI,
    private val size: MarkdownAppearance.TextSize,
    private val subtext: Boolean,
    private val newline: Boolean,
) : MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        MarkdownBlocks.ensureLineStart(builder)
        val start = builder.length
        children?.forEach { it.render(builder, context) }
        val end = builder.length
        if (end > start) {
            builder.setSpan(MarkdownSizeSpan(settings, size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (subtext) {
                val muted = MarkdownAppearance.themedColor(context.context, "colorTextMuted", Color.GRAY)
                applyMutedColor(builder, start, end, muted)
            } else {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (newline) MarkdownBlocks.ensureLineStart(builder)
    }

    private fun applyMutedColor(builder: SpannableStringBuilder, start: Int, end: Int, color: Int) {
        var cursor = start
        val links = builder.getSpans(start, end, ClickableSpan::class.java).sortedBy(builder::getSpanStart)
        for (link in links) {
            val linkStart = builder.getSpanStart(link).coerceIn(start, end)
            val linkEnd = builder.getSpanEnd(link).coerceIn(start, end)
            if (linkStart > cursor) {
                builder.setSpan(ForegroundColorSpan(color), cursor, linkStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = maxOf(cursor, linkEnd)
        }
        if (cursor < end) builder.setSpan(ForegroundColorSpan(color), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Follow the live emoji provider used by NewEmojis and TextEmoji instead of its cached regex. */
private class UnicodeEmojiRule(private val native: MessageRule) : MessageRule(Pattern.compile("(?!x)x")) {
    private var providerPattern: Pattern? = null
    private var anchoredPattern: Pattern? = null

    override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? {
        val current = Rules.`access$getEmojiDataProvider$p`(Rules.INSTANCE).unicodeEmojisPattern
        if (current?.pattern() == "\$a") return null
        val nativeMatch = native.match(source, previous, state)
        if (current != null) {
            if (current !== providerPattern) {
                providerPattern = current
                anchoredPattern = Pattern.compile("^(?:${current.pattern()})", current.flags())
            }
            val liveMatch = anchoredPattern!!.matcher(source)
            if (liveMatch.find() && (nativeMatch == null || liveMatch.end() > nativeMatch.end())) return liveMatch
        }
        return nativeMatch
    }

    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec = native.parse(match, parser, state)
}
