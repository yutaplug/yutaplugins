package com.github.yutaplug.markdownfix;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.entities.Plugin.SettingsTab;
import com.aliucord.api.SettingsAPI;
import com.aliucord.patcher.PreHook;
import com.discord.simpleast.core.node.Node;
import com.discord.simpleast.core.parser.ParseSpec;
import com.discord.simpleast.core.parser.Parser;
import com.discord.simpleast.core.parser.Rule;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.spans.ClickableSpan;
import com.discord.utilities.spans.BulletSpan;
import com.discord.utilities.spans.QuoteSpan;
import com.discord.utilities.spans.VerticalPaddingSpan;
import com.discord.utilities.textprocessing.AstRenderer;
import com.discord.utilities.textprocessing.DiscordParser;
import com.discord.utilities.textprocessing.MessageParseState;
import com.discord.utilities.textprocessing.MessagePreprocessor;
import com.discord.utilities.textprocessing.MessageRenderContext;
import com.discord.utilities.textprocessing.Rules;
import com.discord.utilities.textprocessing.node.BasicRenderContext;
import com.discord.utilities.textprocessing.node.BlockQuoteNode;
import com.discord.utilities.textprocessing.node.EditedMessageNode;
import com.discord.utilities.textprocessing.node.UrlNode;
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode;
import com.discord.utilities.string.StringUtilsKt;
import com.facebook.drawee.span.DraweeSpanStringBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import b.a.t.b.b.e;
import kotlin.Unit;

/** Enables Discord's newer block-level Markdown rules in chat and forum messages. */
@AliucordPlugin
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MarkdownFix extends Plugin {
    static final String HEADER_1_SCALE = "header1Scale";
    static final String HEADER_2_SCALE = "header2Scale";
    static final String HEADER_3_SCALE = "header3Scale";
    static final String SUBTEXT_SCALE = "subtextScale";
    static final String COMPACT_BULLETS = "compactBullets";
    static final String CUSTOM_BULLET_COLOR = "customBulletColor";
    static final String BULLET_COLOR = "bulletColor";

    static final float DEFAULT_HEADER_1_SCALE = 1.35f;
    static final float DEFAULT_HEADER_2_SCALE = 1.20f;
    static final float DEFAULT_HEADER_3_SCALE = 1.10f;
    static final float DEFAULT_SUBTEXT_SCALE = 0.75f;
    static final String DEFAULT_BULLET_COLOR = "#5865F2";

    private static final Pattern SUBTEXT_PATTERN =
            Pattern.compile("^[ \\t]*-#[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^[ \\t]*(#{1,3})[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern ESCAPE_PATTERN =
            Pattern.compile("^\\\\([^0-9A-Za-z\\s])");
    private static final Pattern LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*)([\\n|$])?");
    private static final Pattern FORUM_LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+([^\\r\\n]*?)[ \\t]*(\\r?\\n|$)");
    private static final Pattern BLOCK_LIST_BODY_PATTERN =
            Pattern.compile("^(?:#{1,3}[ \\t]+|-#[ \\t]+).*");
    private static final Pattern BLOCK_QUOTE_PATTERN =
            Pattern.compile("^(?: *>>> +(.*)| *>(?!>>) +([^\\n]*\\n?))", Pattern.DOTALL);
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> forumParser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> embedTitlesParser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> embedValuesParser;

    @Override
    public void start(Context context) throws Throwable {
        settingsTab = new SettingsTab(MarkdownFixSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings);

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

        try {
            installEmbedParserHook();
        } catch (Throwable error) {
            // Embed parsers are private Discord implementation details; keep the
            // normal message and forum fixes available if they move in a future build.
            logger.error("MarkdownFix could not hook embed Markdown", error);
        }

        try {
            installRichLinkHook();
        } catch (Throwable error) {
            // The URL renderer is an implementation detail of the Discord build.
            logger.error("MarkdownFix could not hook Markdown hyperlinks", error);
        }

        try {
            installModernSpacingHooks();
        } catch (Throwable error) {
            // These block renderer methods are implementation details of the Discord build.
            logger.error("MarkdownFix could not hook modern Markdown block renderers", error);
        }

    }

    @SuppressWarnings("unchecked")
    private void installEmbedParserHook() throws Throwable {
        Class<?> embedClass = Class.forName(
                "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed");
        Field titlesField = embedClass.getDeclaredField("UI_THREAD_TITLES_PARSER");
        Field valuesField = embedClass.getDeclaredField("UI_THREAD_VALUES_PARSER");
        titlesField.setAccessible(true);
        valuesField.setAccessible(true);
        embedTitlesParser = (Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>)
                titlesField.get(null);
        embedValuesParser = (Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>)
                valuesField.get(null);

        Method parse = Parser.class.getDeclaredMethod(
                "parse", CharSequence.class, Object.class, List.class);
        patcher.patch(parse, new PreHook(frame -> {
            boolean isEmbedParser = frame.thisObject == embedTitlesParser
                    || frame.thisObject == embedValuesParser;
            if (!isEmbedParser) return;

            try {
                CharSequence content = (CharSequence) frame.args[0];
                MessageParseState state = (MessageParseState) frame.args[1];
                List<Node<MessageRenderContext>> ast = getParser().parse(content, state);
                frame.setResult(ast);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not parse embed Markdown", error);
            }
        }));
    }

    private void installRichLinkHook() throws Throwable {
        Field maskField = UrlNode.class.getDeclaredField("mask");
        maskField.setAccessible(true);
        Method typedRender = UrlNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, UrlNode.RenderContext.class);
        Method bridgeRender = UrlNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, Object.class);
        patchRichLinkRender(typedRender, maskField);
        patchRichLinkRender(bridgeRender, maskField);
    }

    private void patchRichLinkRender(Method render, Field maskField) {
        patcher.patch(render, new PreHook(frame -> {
            if (!(frame.thisObject instanceof UrlNode)
                    || !(frame.args[1] instanceof MessageRenderContext)) return;

            try {
                String label = (String) maskField.get(frame.thisObject);
                if (label == null) return;

                renderRichMaskedLink(
                        (UrlNode<?>) frame.thisObject,
                        (SpannableStringBuilder) frame.args[0],
                        (MessageRenderContext) frame.args[1],
                        label
                );
                frame.setResult(null);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not render a Markdown hyperlink", error);
            }
        }));
    }

    private void renderRichMaskedLink(
            UrlNode<?> node,
            SpannableStringBuilder builder,
            MessageRenderContext context,
            String label) {
        String safeUrl = node.getUrl();
        try {
            safeUrl = StringUtilsKt.toPunyCodeASCIIUrl(node.getUrl());
        } catch (Throwable ignored) {
            // Keep the original URL if punycode conversion is unavailable.
        }
        final String linkUrl = safeUrl;

        int start = builder.length();
        try {
            List<Node<MessageRenderContext>> labelAst = getParser().parse(
                    label,
                    MessageParseState.Companion.getInitialState()
            );
            for (Node<MessageRenderContext> child : labelAst) child.render(builder, context);
        } catch (Throwable ignored) {
            builder.append(label);
        }

        if (builder.length() > start) {
            ClickableSpan clickable = new ClickableSpan(
                    Integer.valueOf(ColorCompat.getThemedColor(
                            context.getContext(), context.getLinkColorAttrResId())),
                    false,
                    view -> {
                        context.getOnLongPressUrl().invoke(linkUrl);
                        return Unit.a;
                    },
                    view -> {
                        context.getOnClickUrl().invoke(view.getContext(), linkUrl, label);
                        return Unit.a;
                    }
            );
            builder.setSpan(clickable, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void installModernSpacingHooks() throws Throwable {
        Method quoteRender = BlockQuoteNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, BasicRenderContext.class);
        Method quoteBridgeRender = BlockQuoteNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, Object.class);
        patchModernQuoteRender(quoteRender);
        patchModernQuoteRender(quoteBridgeRender);
    }

    private void patchModernQuoteRender(Method render) {
        patcher.patch(render, new PreHook(frame -> {
            if (!(frame.thisObject instanceof BlockQuoteNode)
                    || !(frame.args[0] instanceof SpannableStringBuilder)
                    || !(frame.args[1] instanceof BasicRenderContext)) return;

            try {
                renderModernBlockQuote(
                        (BlockQuoteNode<?>) frame.thisObject,
                        (SpannableStringBuilder) frame.args[0],
                        (BasicRenderContext) frame.args[1]
                );
                frame.setResult(null);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not render a compact block quote", error);
            }
        }));
    }

    private static void renderModernBlockQuote(
            BlockQuoteNode<?> node,
            SpannableStringBuilder builder,
            BasicRenderContext renderContext) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }

        // EmojiNode expects Discord's DraweeSpanStringBuilder when it renders
        // custom emoji spans; a plain SpannableStringBuilder causes a cast crash.
        DraweeSpanStringBuilder content = new DraweeSpanStringBuilder();
        Iterable<? extends Node> children = node.getChildren();
        if (children != null) {
            for (Node child : children) child.render(content, renderContext);
        }
        while (content.length() > 0 && content.charAt(content.length() - 1) == '\n') {
            content.delete(content.length() - 1, content.length());
        }
        if (content.length() == 0) content.append(' ');

        Context context = renderContext.getContext();
        int quoteColor = defaultQuoteColor(context);
        int lineStart = 0;
        while (lineStart < content.length()) {
            int lineEnd = lineStart;
            while (lineEnd < content.length() && content.charAt(lineEnd) != '\n') {
                lineEnd++;
            }

            int quoteStart = builder.length();
            if (lineEnd == lineStart) {
                builder.append(' ');
            } else {
                builder.append(content, lineStart, lineEnd);
            }
            builder.setSpan(
                    new QuoteSpan(quoteColor, dp(context, 2), dp(context, 6)),
                    quoteStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            if (lineEnd < content.length()) {
                builder.append('\n');
                lineStart = lineEnd + 1;
            } else {
                lineStart = lineEnd;
            }
        }

        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            int boundaryStart = builder.length();
            builder.append('\n');
            builder.setSpan(
                    new AbsoluteSizeSpan(dp(context, 4)),
                    boundaryStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
    }

    private static int defaultQuoteColor(Context context) {
        return themedColor(context, "theme_chat_block_quote_divider", Color.rgb(79, 84, 92));
    }

    private static final class ModernBlockQuoteRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private ModernBlockQuoteRule() {
            super(BLOCK_QUOTE_PATTERN);
        }

        @Override
        public Matcher match(CharSequence source, String previousMatch, MessageParseState state) {
            if (state.isInQuote()) return null;
            return super.match(source, previousMatch, state);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            int group = matcher.group(1) != null ? 1 : 2;
            ModernBlockQuoteNode node = new ModernBlockQuoteNode();
            return new ParseSpec<>(node, state.newBlockQuoteState(true),
                    matcher.start(group), matcher.end(group));
        }
    }

    private static final class ModernBlockQuoteNode extends Node<MessageRenderContext> {
        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext renderContext) {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, renderContext);
            }
            if (builder.length() == start) builder.append(' ');

            Context context = renderContext.getContext();
            builder.setSpan(
                    new QuoteSpan(
                            defaultQuoteColor(context),
                            dp(context, 2),
                            dp(context, 5)
                    ),
                    start,
                    builder.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE
            );
            if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
        }
    }

    private static int themedColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        return id == 0 ? fallback : ColorCompat.getThemedColor(context, id);
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getParser() {
        if (parser == null) parser = createParser(settings);
        return parser;
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getForumParser() {
        if (forumParser == null) forumParser = createForumParser(settings);
        return forumParser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> createParser(
            SettingsAPI settings) {
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
        parser.addRule(new HeaderRule(settings));
        parser.addRule(new SubtextRule(settings));
        parser.addRule(new ListRule(settings));
        parser.addRules(e.a(false, false));
        parser.addRule(rules.createTextReplacementRule());
        return parser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>
            createForumParser(SettingsAPI settings) {
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
        parser.addRule(new HeaderRule(settings));
        parser.addRule(new ForumListRule(settings));
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
        private final SettingsAPI settings;

        private HeaderRule(SettingsAPI settings) {
            super(HEADER_PATTERN);
            this.settings = settings;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(
                    new HeaderNode(matcher.group(1).length(), settings),
                    state,
                    matcher.start(2),
                    matcher.end(2)
            );
        }
    }

    private static final class HeaderNode extends Node<MessageRenderContext> {
        private final int level;
        private final SettingsAPI settings;

        private HeaderNode(int level, SettingsAPI settings) {
            this.level = level;
            this.settings = settings;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
            }
            int end = builder.length();
            if (end <= start) return;

            float size = level == 1
                    ? readScale(settings, HEADER_1_SCALE, DEFAULT_HEADER_1_SCALE)
                    : level == 2
                    ? readScale(settings, HEADER_2_SCALE, DEFAULT_HEADER_2_SCALE)
                    : readScale(settings, HEADER_3_SCALE, DEFAULT_HEADER_3_SCALE);
            builder.setSpan(new RelativeSizeSpan(size), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final class SubtextRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;

        private SubtextRule(SettingsAPI settings) {
            super(SUBTEXT_PATTERN);
            this.settings = settings;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(
                    new SubtextNode(settings),
                    state,
                    matcher.start(1),
                    matcher.end(1)
            );
        }
    }

    private static final class SubtextNode extends Node<MessageRenderContext> {
        private final SettingsAPI settings;

        private SubtextNode(SettingsAPI settings) {
            this.settings = settings;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
            }
            int end = builder.length();
            if (end <= start) return;

            builder.setSpan(new RelativeSizeSpan(readScale(settings, SUBTEXT_SCALE, DEFAULT_SUBTEXT_SCALE)), start, end,
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
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;

        private ListRule(SettingsAPI settings) {
            super(LIST_PATTERN);
            this.settings = settings;
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
            ConfigurableBulletNode<MessageRenderContext> node =
                    new ConfigurableBulletNode<>(nestedLevel, includesNewline, settings);

            String body = matcher.group(2);
            // Parse every item body with a fresh parser. This prevents the child
            // parser's last match from blocking the next consecutive list item,
            // while the BlockRule keeps hyphens in ordinary inline text intact.
            Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> bodyParser =
                    createParser(settings);
            for (Node<MessageRenderContext> child : bodyParser.parse(body, state)) {
                node.addChild(child);
            }
            return new ParseSpec<>(node, state);
        }
    }

    private static final class ForumListRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;

        private ForumListRule(SettingsAPI settings) {
            super(FORUM_LIST_PATTERN);
            this.settings = settings;
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
            ConfigurableBulletNode<MessageRenderContext> node =
                    new ConfigurableBulletNode<>(nestedLevel, includesNewline, settings);

            String body = matcher.group(2);
            Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> bodyParser =
                    createParser(settings);
            for (Node<MessageRenderContext> child : bodyParser.parse(body, state)) {
                node.addChild(child);
            }
            return new ParseSpec<>(node, state);
        }
    }

    private static final class ConfigurableBulletNode<T extends BasicRenderContext> extends Node<T> {
        private final int nestedLevel;
        private final boolean includesNewline;
        private final SettingsAPI settings;

        private ConfigurableBulletNode(int nestedLevel, boolean includesNewline, SettingsAPI settings) {
            super(null, 1, null);
            this.nestedLevel = nestedLevel;
            this.includesNewline = includesNewline;
            this.settings = settings;
        }

        @Override
        public void render(SpannableStringBuilder builder, T renderContext) {
            Context context = renderContext.getContext();
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<T> child : getChildren()) child.render(builder, renderContext);
            }

            boolean compact = settings.getBool(COMPACT_BULLETS, false);
            int gap = compact
                    ? dp(context, 6)
                    : dimension(context, "markdown_bullet_gap", 4);
            int indentation = compact
                    ? dp(context, 4) * nestedLevel
                    : gap * nestedLevel;
            int radius = compact ? Math.max(1, dp(context, 2)) : 8;
            float strokeWidth = compact ? Math.max(1, dp(context, 1)) : 4.0f;
            int verticalPadding = compact
                    ? 0
                    : dimension(context, "markdown_bullet_vertical_padding", 2);
            Paint.Style style = nestedLevel > 1 ? Paint.Style.STROKE : Paint.Style.FILL;

            ArrayList<Object> spans = new ArrayList<>(3);
            spans.add(new VerticalPaddingSpan(verticalPadding, verticalPadding));
            spans.add(new LeadingMarginSpan.Standard(indentation));
            spans.add(new BulletSpan(gap, bulletColor(context), radius, strokeWidth, style));
            for (Object span : spans) {
                builder.setSpan(span, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (includesNewline) builder.append("\n");
        }

        private int bulletColor(Context context) {
            if (settings.getBool(CUSTOM_BULLET_COLOR, false)) {
                try {
                    return Color.parseColor(settings.getString(BULLET_COLOR, DEFAULT_BULLET_COLOR));
                } catch (Throwable ignored) {
                    // Fall back to Discord's themed bullet color for invalid values.
                }
            }

            int primaryColor = Utils.getResId("primary_400", "attr");
            return primaryColor == 0
                    ? Color.LTGRAY
                    : ColorCompat.getThemedColor(context, primaryColor);
        }
    }

    static float readScale(SettingsAPI settings, String key, float fallback) {
        try {
            float value = Float.parseFloat(settings.getString(key, ""));
            return value >= 0.1f && value <= 3.0f ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int dimension(Context context, String name, int fallbackDp) {
        int id = Utils.getResId(name, "dimen");
        return id == 0
                ? dp(context, fallbackDp)
                : context.getResources().getDimensionPixelSize(id);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        parser = null;
        forumParser = null;
        embedTitlesParser = null;
        embedValuesParser = null;
    }
}
