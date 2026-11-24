package com.github.yutaplug.fixes.markdown

import android.graphics.Color
import b.a.t.b.b.e
import com.aliucord.api.SettingsAPI
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.UrlNode
import java.util.regex.Matcher
import java.util.regex.Pattern
import b.a.t.b.a.a as TextNode

internal typealias MessageNode = Node<MessageRenderContext>
internal typealias MessageParser = Parser<MessageRenderContext, MessageNode, MessageParseState>
internal typealias MessageRule = Rule<MessageRenderContext, MessageNode, MessageParseState>
internal typealias MessageSpec = ParseSpec<MessageRenderContext, MessageParseState>
internal typealias AnyParser = Parser<MessageRenderContext, in MessageNode, MessageParseState>

/**
 * Parses messages with Discord's own inline rules plus block rules for current Markdown.
 *
 * Block rules only match at the start of a line, which simpleast decides from the previous match. Discord's
 * text rule can swallow a newline together with the next character (`"\n1"`), which hid `1.` list items after
 * text, so [NewlineRule] always takes newlines on their own first.
 *
 * Each parse creates the rules that parse recursively: a rule's matcher is read again after its parse returns,
 * so a nested parse must not reuse it.
 */
internal class MarkdownParser(private val settings: SettingsAPI, private val games: GameProfileResolver) {
    enum class Scope {
        /** Whole messages, forum posts and embed fields: every block. */
        MESSAGE,

        /** The content of one list item: headings, subtext and code blocks, but no new lists or quotes. */
        ITEM,

        /** Heading text and embed titles: inline only. */
        INLINE,

        /** Masked link labels: inline only, without links. */
        LABEL,

        /** Reply previews: inline only, without a leading heading marker. */
        REPLY,
    }

    fun parse(
        source: CharSequence,
        state: MessageParseState = MessageParseState.`access$getInitialState$cp`(),
        scope: Scope = Scope.MESSAGE,
    ): MutableList<MessageNode> {
        val blocks = scope == Scope.MESSAGE || scope == Scope.ITEM
        val parser = MessageParser(false)
        val rules = Rules.INSTANCE
        parser.addRule(rules.createSoftHyphenRule())
        parser.addRule(EscapeRule())
        if (scope == Scope.REPLY) parser.addRule(ReplyMarkerRule())
        if (scope == Scope.MESSAGE && !state.isInQuote) parser.addRule(QuoteRule())
        if (blocks) {
            parser.addRule(HeadingRule())
            parser.addRule(SubtextRule())
        }
        if (scope == Scope.MESSAGE) parser.addRule(ListItemRule())
        if (blocks) parser.addRule(codeBlockRule.get()!!)
        parser.addRule(rules.createInlineCodeRule())
        parser.addRule(rules.createSpoilerRule())
        parser.addRule(HexColorRule())
        if (scope != Scope.LABEL) {
            parser.addRule(MaskedLinkRule())
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
        parser.addRule(ShortDateTimestampRule())
        parser.addRule(rules.createTimestampRule())
        parser.addRule(NewlineRule())
        parser.addRules(e.a<MessageRenderContext, MessageParseState>(false, false))
        parser.addRule(rules.createTextReplacementRule())
        val nodes = parser.parse(source.toString().replace("\r\n", "\n"), state)
        if (scope == Scope.MESSAGE) groupLists(nodes)
        return nodes
    }

    /**
     * Joins consecutive list items into lists the way Discord does: a list keeps the type of its first item,
     * so `-` items after `1.` keep counting, and a deeper indent starts a list nested in the item above.
     */
    private fun groupLists(nodes: List<MessageNode>) {
        val stack = ArrayList<Pair<Int, ListRun>>()
        for (node in nodes) {
            if (node !is ListItemNode) {
                stack.clear()
                continue
            }
            while (stack.isNotEmpty() && stack.last().first > node.level) stack.removeAt(stack.size - 1)
            val top = stack.lastOrNull()
            val run = if (top != null && top.first == node.level) {
                top.second
            } else {
                ListRun(top?.second, node.number != null, node.number ?: 1).also { stack.add(node.level to it) }
            }
            node.run = run
            if (run.ordered) {
                val label = "${run.next++}."
                node.label = label
                run.labels.add(label)
            }
        }
    }

    /** Consecutive `> ` lines, or `>>> ` and everything after it. A space after the arrow is required. */
    private inner class QuoteRule : Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(QUOTE) {
        override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec {
            val body = match.group(1) ?: QUOTE_PREFIX.matcher(match.group(2).orEmpty()).replaceAll("")
            val node = QuoteNode()
            parse(body, state.newBlockQuoteState(true)).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    private inner class ListItemRule : Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(LIST) {
        override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec {
            val width = match.group(1).orEmpty().fold(0) { width, character -> width + if (character == '\t') 4 else 1 }
            val marker = match.group(2).orEmpty()
            val number = if (marker.endsWith(".")) marker.dropLast(1).toIntOrNull() else null
            val node = ListItemNode(settings, (1 + width / 2).coerceAtMost(8), number, match.group(4) == "\n")
            parse(match.group(3).orEmpty(), state, Scope.ITEM).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    private inner class HeadingRule : Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(HEADING) {
        override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec {
            val node = HeadingNode(settings, match.group(1)!!.length, match.group(3) == "\n")
            parse(match.group(2).orEmpty(), state, Scope.INLINE).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    private inner class SubtextRule : Rule.BlockRule<MessageRenderContext, MessageNode, MessageParseState>(SUBTEXT) {
        override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec {
            val node = HeadingNode(settings, 0, match.group(2) == "\n")
            parse(match.group(1).orEmpty(), state, Scope.INLINE).forEach(node::addChild)
            return MessageSpec(node, state)
        }
    }

    /** Discord's masked link rule, with the label parsed so mentions, emoji and spoilers in it still work. */
    private inner class MaskedLinkRule : MessageRule(NEVER) {
        private val native = Rules.INSTANCE.createMaskedLinkRule<MessageRenderContext, MessageParseState>()

        override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? =
            native.match(source, previous, state)

        override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec {
            val spec = native.parse(match, parser, state)
            val node = spec.a as UrlNode<MessageRenderContext>
            parse(match.group(1).orEmpty(), state, Scope.LABEL).forEach(node::addChild)
            return spec
        }
    }

    private companion object {
        val NEVER: Pattern = Pattern.compile("(?!x)x")
        val QUOTE: Pattern = Pattern.compile(
            "^(?: *>>> +([\\s\\S]*)| *>(?!>>) +([^\\n]*(?:\\n *>(?!>>) +[^\\n]*)*\\n?))",
        )
        val QUOTE_PREFIX: Pattern = Pattern.compile("(?m)^ *> ?")
        val LIST: Pattern = Pattern.compile("^([ \\t]*)([*-]|\\d{1,9}\\.) +([^\\n]*?\\S)[ \\t]*(\\n|$)")
        val HEADING: Pattern = Pattern.compile("^ *(#{1,3}) +([^\\n]*?\\S)[ \\t]*(\\n|$)")
        val SUBTEXT: Pattern = Pattern.compile("^ *-# +([^\\n]*?\\S)[ \\t]*(\\n|$)")

        /** Building Discord's code rule compiles every highlighter, so each thread keeps one. */
        val codeBlockRule = object : ThreadLocal<MessageRule>() {
            override fun initialValue(): MessageRule = CodeBlockRule()
        }
    }
}

/** A newline is always its own token, so block rules see the start of every line. */
private class NewlineRule : MessageRule(Pattern.compile("^\\n")) {
    override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec =
        MessageSpec(TextNode<MessageRenderContext>("\n"), state)
}

/**
 * Discord's fenced code rule, as a block even on one line (```` ```code``` ````) like current Discord. Its
 * pattern also takes the newline after the closing fence, so the block doesn't leave an empty line below it.
 */
private class CodeBlockRule : MessageRule(PATTERN) {
    private val native = Rules.INSTANCE.createCodeBlockRule<MessageRenderContext, MessageParseState>()

    @Suppress("UNCHECKED_CAST")
    override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec {
        // Discord's rule only reads groups 1–3, which this pattern keeps.
        val spec = native.parse(match, parser, state)
        val code = spec.a.children?.firstOrNull { it is CodeNode<*> } ?: return spec
        return MessageSpec(CodeBlockNode(code as CodeNode<MessageRenderContext>), state)
    }

    private companion object {
        val PATTERN: Pattern =
            Pattern.compile("^```(?:([\\w+\\-.]+?)?(\\s*\\n))?([^\\n].*?)\\n*```(?:[ \\t]*\\n)?", Pattern.DOTALL)
    }
}

/** Reply previews show a heading's text without its `#` or `-#`, like current Discord. */
private class ReplyMarkerRule : MessageRule(Pattern.compile("^ *(?:#{1,3}|-#) +(?=\\S)")) {
    override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? =
        if (previous == null) super.match(source, previous, state) else null

    override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec =
        MessageSpec(TextNode<MessageRenderContext>(""), state)
}

private class EscapeRule : MessageRule(Pattern.compile("^\\\\([^0-9A-Za-z\\s])")) {
    override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec =
        MessageSpec(TextNode<MessageRenderContext>(match.group(1).orEmpty()), state)
}

/** Whole hex tokens only, never the start of a longer value or a word. */
private class HexColorRule : MessageRule(Pattern.compile("^#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?![0-9a-fA-F])")) {
    override fun match(source: CharSequence, previous: String?, state: MessageParseState): Matcher? =
        if (previous?.lastOrNull()?.isLetterOrDigit() == true) null else super.match(source, previous, state)

    override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec =
        MessageSpec(HexColorNode(match.group(), Color.parseColor(match.group())), state)
}

/** Follows the live emoji provider used by NewEmojis and TextEmoji instead of its cached pattern. */
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

    override fun parse(match: Matcher, parser: AnyParser, state: MessageParseState): MessageSpec =
        native.parse(match, parser, state)
}
