package com.github.yutaplug.markdownfix;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.PreHook;
import com.discord.simpleast.core.node.Node;
import com.discord.simpleast.core.parser.ParseSpec;
import com.discord.simpleast.core.parser.Parser;
import com.discord.simpleast.core.parser.Rule;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.spans.ClickableSpan;
import com.discord.utilities.textprocessing.AstRenderer;
import com.discord.utilities.textprocessing.DiscordParser;
import com.discord.utilities.textprocessing.MessageParseState;
import com.discord.utilities.textprocessing.MessagePreprocessor;
import com.discord.utilities.textprocessing.MessageRenderContext;
import com.discord.utilities.textprocessing.Rules;
import com.discord.utilities.textprocessing.node.BulletListNode;
import com.discord.utilities.textprocessing.node.EditedMessageNode;
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode;
import com.facebook.drawee.span.DraweeSpanStringBuilder;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import b.a.t.b.b.e;

/** Enables Discord's newer block-level Markdown rules in chat and forum messages. */
@AliucordPlugin
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MarkdownFix extends Plugin {
    private static final Pattern SUBTEXT_PATTERN =
            Pattern.compile("^\\s*-#[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^\\s*(#{1,3})[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern ESCAPE_PATTERN =
            Pattern.compile("^\\\\([^0-9A-Za-z\\s])");
    private static final Pattern LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*)([\\n|$])?");
    private static final Pattern FORUM_LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+([^\\r\\n]*?)[ \\t]*(\\r?\\n|$)");
    private static final Pattern BLOCK_LIST_BODY_PATTERN =
            Pattern.compile("^(?:#{1,3}[ \\t]+|-#[ \\t]+).*");

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> forumParser;

    @Override
    public void start(Context context) throws Throwable {
        Method parseChannelMessage = DiscordParser.class.getDeclaredMethod(
                "parseChannelMessage",
                Context.class,
                String.class,
                MessageRenderContext.class,
                MessagePreprocessor.class,
                DiscordParser.ParserOptions.class,
                boolean.class
        );

        patcher.patch(parseChannelMessage, new PreHook(frame -> {
            try {
                Context messageContext = (Context) frame.args[0];
                String content = (String) frame.args[1];
                MessageRenderContext renderContext = (MessageRenderContext) frame.args[2];
                MessagePreprocessor preprocessor = (MessagePreprocessor) frame.args[3];
                boolean appendEditedLabel = Boolean.TRUE.equals(frame.args[5]);
                boolean isForumPost =
                        frame.args[4] == DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE;

                List<Node<MessageRenderContext>> ast = (isForumPost ? getForumParser() : getParser()).parse(
                        content == null ? "" : content,
                        MessageParseState.Companion.getInitialState()
                );
                preprocessor.process(ast);
                if (appendEditedLabel) ast.add(new EditedMessageNode(messageContext));
                ast.add(new ZeroSpaceWidthNode());

                DraweeSpanStringBuilder rendered = AstRenderer.render(ast, renderContext);
                frame.setResult(rendered);
            } catch (Throwable error) {
                // Keep Discord's original parser as a safe fallback on an unexpected client change.
                logger.error("MarkdownFix could not render a message", error);
            }
        }));
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getParser() {
        if (parser == null) parser = createParser();
        return parser;
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getForumParser() {
        if (forumParser == null) forumParser = createForumParser();
        return forumParser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> createParser() {
        Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser =
                new Parser<>(false);
        Rules rules = Rules.INSTANCE;

        // Keep the same rule order as DiscordParser. Block rules must be before the
        // catch-all text rule, otherwise the text rule consumes the entire message.
        parser.addRule(rules.createSoftHyphenRule());
        parser.addRule(new EscapeRule());
        parser.addRule(rules.createBlockQuoteRule());
        parser.addRule(rules.createCodeBlockRule());
        parser.addRule(rules.createInlineCodeRule());
        parser.addRule(rules.createSpoilerRule());
        parser.addRule(rules.createMaskedLinkRule());
        parser.addRule(rules.createUrlNoEmbedRule());
        parser.addRule(rules.createUrlRule());
        parser.addRule(rules.createCustomEmojiRule());
        parser.addRule(rules.createNamedEmojiRule());
        parser.addRule(rules.createUnescapeEmoticonRule());
        parser.addRule(rules.createChannelMentionRule());
        parser.addRule(rules.createRoleMentionRule());
        parser.addRule(rules.createUserMentionRule());
        parser.addRule(rules.createUnicodeEmojiRule());
        parser.addRule(rules.createTimestampRule());
        parser.addRule(new HeaderRule());
        parser.addRule(new SubtextRule());
        parser.addRule(new ListRule());
        parser.addRules(e.a(false, false));
        parser.addRule(rules.createTextReplacementRule());
        return parser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>
            createForumParser() {
        Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser =
                new Parser<>(false);
        Rules rules = Rules.INSTANCE;

        // This matches DiscordParser's FORUM_POST_FIRST_MESSAGE rule order. The
        // list rule is corrected so bold text is not mistaken for a list item.
        parser.addRule(rules.createSoftHyphenRule());
        parser.addRule(new EscapeRule());
        parser.addRule(rules.createCodeBlockRule());
        parser.addRule(rules.createInlineCodeRule());
        parser.addRule(rules.createSpoilerRule());
        parser.addRule(rules.createUrlNoEmbedRule());
        parser.addRule(rules.createUrlRule());
        parser.addRule(rules.createCustomEmojiRule());
        parser.addRule(rules.createNamedEmojiRule());
        parser.addRule(rules.createUnescapeEmoticonRule());
        parser.addRule(rules.createChannelMentionRule());
        parser.addRule(rules.createRoleMentionRule());
        parser.addRule(rules.createUserMentionRule());
        parser.addRule(rules.createUnicodeEmojiRule());
        parser.addRule(rules.createTimestampRule());
        parser.addRule(rules.createHeaderItemRule());
        parser.addRule(new ForumListRule());
        parser.addRules(e.a(false, false));
        parser.addRule(rules.createTextReplacementRule());
        return parser;
    }

    private static final class EscapeRule
            extends Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private EscapeRule() {
            super(ESCAPE_PATTERN);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(new TextNode(matcher.group(1)), state);
        }
    }

    private static final class TextNode extends Node<MessageRenderContext> {
        private final String text;

        private TextNode(String text) {
            this.text = text;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            builder.append(text);
        }
    }

    private static final class HeaderRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private HeaderRule() {
            super(HEADER_PATTERN);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(
                    new HeaderNode(matcher.group(1).length()),
                    state,
                    matcher.start(2),
                    matcher.end(2)
            );
        }
    }

    private static final class HeaderNode extends Node<MessageRenderContext> {
        private final int level;

        private HeaderNode(int level) {
            this.level = level;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
            }
            int end = builder.length();
            if (end <= start) return;

            float size = level == 1 ? 1.35f : level == 2 ? 1.20f : 1.10f;
            builder.setSpan(new RelativeSizeSpan(size), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final class SubtextRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private SubtextRule() {
            super(SUBTEXT_PATTERN);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(
                    new SubtextNode(),
                    state,
                    matcher.start(1),
                    matcher.end(1)
            );
        }
    }

    private static final class SubtextNode extends Node<MessageRenderContext> {
        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
            }
            int end = builder.length();
            if (end <= start) return;

            builder.setSpan(new RelativeSizeSpan(0.75f), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            applyMutedColorExceptLinks(builder, start, end, mutedTextColor(context));
        }

        private static int mutedTextColor(MessageRenderContext context) {
            try {
                int attr = context.getContext().getResources().getIdentifier(
                        "colorTextMuted", "attr", context.getContext().getPackageName());
                return attr == 0 ? Color.GRAY : ColorCompat.getThemedColor(context.getContext(), attr);
            } catch (Throwable ignored) {
                return Color.GRAY;
            }
        }

        private static void applyMutedColorExceptLinks(
                SpannableStringBuilder builder, int start, int end, int color) {
            ClickableSpan[] links = builder.getSpans(start, end, ClickableSpan.class);
            Arrays.sort(links, (left, right) ->
                    Integer.compare(builder.getSpanStart(left), builder.getSpanStart(right)));

            int cursor = start;
            for (ClickableSpan link : links) {
                int linkStart = Math.max(start, builder.getSpanStart(link));
                int linkEnd = Math.min(end, builder.getSpanEnd(link));
                if (linkStart < cursor || linkEnd <= linkStart) continue;
                if (cursor < linkStart) {
                    builder.setSpan(new ForegroundColorSpan(color), cursor, linkStart,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                cursor = linkEnd;
            }
            if (cursor < end) {
                builder.setSpan(new ForegroundColorSpan(color), cursor, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static final class ListRule
            extends Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private ListRule() {
            super(LIST_PATTERN);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            String indentation = matcher.group(1);
            int indentationWidth = indentation == null ? 0 : indentation.length();
            int nestedLevel = indentationWidth == 0 ? 1 : Math.min(4, 1 + (indentationWidth + 1) / 2);
            String newline = matcher.group(3);
            boolean includesNewline = newline != null && !newline.isEmpty();
            BulletListNode<MessageRenderContext> node =
                    new BulletListNode<>(nestedLevel, includesNewline);

            String body = matcher.group(2);
            if (BLOCK_LIST_BODY_PATTERN.matcher(body).matches()) {
                // A list item's body is parsed before the outer parser advances past
                // the list marker. Parse block syntax with a fresh parser so the
                // block-only header and subtext rules see a true line start.
                Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> bodyParser =
                        createParser();
                for (Node<MessageRenderContext> child : bodyParser.parse(body, state)) {
                    node.addChild(child);
                }
                return new ParseSpec<>(node, state);
            }

            return new ParseSpec<>(
                    node,
                    state,
                    matcher.start(2),
                    matcher.end(2)
            );
        }
    }

    private static final class ForumListRule
            extends Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private ForumListRule() {
            super(FORUM_LIST_PATTERN);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            String indentation = matcher.group(1);
            int nestedLevel = indentation == null || indentation.isEmpty() ? 1 : 2;
            String lineEnding = matcher.group(3);
            boolean includesNewline = lineEnding != null && lineEnding.indexOf('\n') >= 0;
            BulletListNode<MessageRenderContext> node =
                    new BulletListNode<>(nestedLevel, includesNewline);

            return new ParseSpec<>(node, state, matcher.start(2), matcher.end(2));
        }
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        parser = null;
        forumParser = null;
    }
}
