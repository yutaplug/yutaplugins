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
import de.robv.android.xposed.XC_MethodHook;

/** Enables the newer block-level Markdown rules in ordinary chat messages. */
@AliucordPlugin
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MarkdownFix extends Plugin {
    private static final Pattern SUBTEXT_PATTERN =
            Pattern.compile("^-#[ \\t]+(.*)(?=\\n|$)");
    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^\\s*(#{1,3})[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*)([\\n|$])?");
    private static final Pattern BLOCK_LIST_BODY_PATTERN =
            Pattern.compile("^(?:#{1,3}[ \\t]+|-#[ \\t]+).*");
    private static final Pattern ESCAPE_PATTERN =
            Pattern.compile("^\\\\([^0-9A-Za-z\\s])");

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> defaultParser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> replyParser;

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

        patcher.patch(parseChannelMessage, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Context messageContext = (Context) param.args[0];
                    String content = (String) param.args[1];
                    MessageRenderContext renderContext = (MessageRenderContext) param.args[2];
                    MessagePreprocessor preprocessor = (MessagePreprocessor) param.args[3];
                    DiscordParser.ParserOptions options = (DiscordParser.ParserOptions) param.args[4];
                    boolean appendEditedLabel = Boolean.TRUE.equals(param.args[5]);

                    Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser =
                            getParser(options);
                    List<Node<MessageRenderContext>> ast = parser.parse(
                            content == null ? "" : content,
                            MessageParseState.Companion.getInitialState()
                    );
                    preprocessor.process(ast);
                    if (appendEditedLabel) ast.add(new EditedMessageNode(messageContext));
                    ast.add(new ZeroSpaceWidthNode());
                    DraweeSpanStringBuilder rendered = AstRenderer.render(ast, renderContext);
                    param.setResult(rendered);
                } catch (Throwable ignored) {
                    // Leave the original Discord implementation in control if a client
                    // build changes one of the parser classes or method signatures.
                }
            }
        });
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getParser(
            DiscordParser.ParserOptions options) {
        boolean reply = options == DiscordParser.ParserOptions.REPLY;
        boolean forumPost = options == DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE;
        if (reply || forumPost) {
            if (replyParser == null) {
                replyParser = createParser(false, false);
            }
            return replyParser;
        }
        if (defaultParser == null) {
            // Masked links are deliberately enabled here: they are part of the newer
            // message Markdown syntax and use Discord's normal URL click handling.
            defaultParser = createParser(true, true);
        }
        return defaultParser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> createParser(
            boolean includeBlockQuotes, boolean includeMaskedLinks) {
        Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser =
                new Parser<>(false);
        Rules rules = Rules.INSTANCE;

        parser.addRule(rules.createSoftHyphenRule());
        parser.addRule(new EscapeRule());
        if (includeBlockQuotes) parser.addRule(rules.createBlockQuoteRule());
        parser.addRule(rules.createCodeBlockRule());
        parser.addRule(rules.createInlineCodeRule());
        parser.addRule(rules.createSpoilerRule());
        if (includeMaskedLinks) parser.addRule(rules.createMaskedLinkRule());
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
        // Discord's native list pattern accepts zero whitespace after the marker,
        // which makes a line beginning with **bold** look like a bullet item.
        parser.addRule(new ListRule());
        parser.addRules(e.a(false, false));
        parser.addRule(rules.createTextReplacementRule());
        return parser;
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
            SubtextNode node = new SubtextNode();
            return new ParseSpec<>(node, state, matcher.start(1), matcher.end(1));
        }
    }

    private static final class SubtextNode extends Node<MessageRenderContext> {
        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            renderChildren(builder, context);
            int end = builder.length();
            if (end <= start) return;

            builder.setSpan(new RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int mutedColor;
            try {
                int mutedAttribute = context.getContext().getResources().getIdentifier(
                        "colorTextMuted", "attr", context.getContext().getPackageName());
                mutedColor = mutedAttribute == 0
                        ? Color.GRAY
                        : ColorCompat.getThemedColor(context.getContext(), mutedAttribute);
            } catch (Throwable ignored) {
                mutedColor = Color.GRAY;
            }
            applyMutedColorExceptLinks(builder, start, end, mutedColor);
        }

        private void applyMutedColorExceptLinks(
                SpannableStringBuilder builder, int start, int end, int mutedColor) {
            ClickableSpan[] links = builder.getSpans(start, end, ClickableSpan.class);
            Arrays.sort(links, (left, right) ->
                    Integer.compare(builder.getSpanStart(left), builder.getSpanStart(right)));

            int cursor = start;
            for (ClickableSpan link : links) {
                int linkStart = Math.max(start, builder.getSpanStart(link));
                int linkEnd = Math.min(end, builder.getSpanEnd(link));
                if (linkStart < cursor || linkEnd <= linkStart) continue;

                if (cursor < linkStart) {
                    builder.setSpan(new ForegroundColorSpan(mutedColor), cursor, linkStart,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                cursor = linkEnd;
            }
            if (cursor < end) {
                builder.setSpan(new ForegroundColorSpan(mutedColor), cursor, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        private void renderChildren(SpannableStringBuilder builder, MessageRenderContext context) {
            if (getChildren() == null) return;
            for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
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

            float size = level == 1 ? 1.25f : level == 2 ? 1.10f : 1.0f;
            builder.setSpan(new RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
            int nestedLevel = matcher.group(1).isEmpty() ? 1 : 2;
            String newline = matcher.group(3);
            boolean includesNewline = newline != null && !newline.isEmpty();
            BulletListNode<MessageRenderContext> node =
                    new BulletListNode<>(nestedLevel, includesNewline);

            String body = matcher.group(2);
            if (BLOCK_LIST_BODY_PATTERN.matcher(body).matches()) {
                // Header and subtext rules are block rules. At the end of a list
                // item the outer list marker is not always followed by a newline,
                // so parse this block body with a fresh parser and attach it to the
                // native BulletListNode explicitly.
                Parser rawParser = createParser(true, true);
                for (Object child : rawParser.parse(body, state)) {
                    node.addChild((Node<MessageRenderContext>) child);
                }
                return new ParseSpec<>(node, state);
            }
            return new ParseSpec<>(node, state, matcher.start(2), matcher.end(2));
        }
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

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        defaultParser = null;
        replyParser = null;
    }
}
