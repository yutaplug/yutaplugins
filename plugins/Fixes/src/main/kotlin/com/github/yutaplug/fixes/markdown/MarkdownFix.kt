package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.text.SpannableStringBuilder
import android.widget.TextView
import com.aliucord.patcher.PreHook
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.drawee.span.SimpleDraweeSpanTextView
import com.github.yutaplug.fixes.Fix
import com.github.yutaplug.fixes.markdown.MarkdownParser.Scope
import java.io.File
import java.util.WeakHashMap
import com.discord.utilities.view.text.SimpleDraweeSpanTextView as DiscordDraweeSpanTextView

internal class MarkdownFix :
    Fix(
        "MarkdownFix",
        "Markdown",
        "Adds current Discord Markdown such as #, ##, ### headings, -# subtext, bulleted and numbered lists and " +
            "[masked links](https://example.com), and fixes code blocks and broken forum posts.",
    ) {
    private var games: GameProfileResolver? = null

    // Chat messages use Discord's own SimpleDraweeSpanTextView, other surfaces Fresco's; track both.
    private val textViews = WeakHashMap<TextView, Boolean>()

    override fun start(context: Context) {
        val resolver = GameProfileResolver(File(context.cacheDir, "MarkdownFix-games")) { refreshAppearance() }
        games = resolver
        GameProfileSheet.pluginResources = resources
        val parser = MarkdownParser(settings, resolver)
        try {
            installMessageParsing(parser)
            installEmbedParsing(parser)
            installRichLinks(parser)
            installAnsiRendering()
            installViewTracking(resolver)
        } catch (error: Throwable) {
            stop(context)
            throw error
        }
    }

    /** Messages, replies and forum posts all go through [DiscordParser.parseChannelMessage]. */
    private fun installMessageParsing(parser: MarkdownParser) {
        patcher.patch(
            DiscordParser::class.java.getDeclaredMethod(
                "parseChannelMessage",
                Context::class.java,
                String::class.java,
                MessageRenderContext::class.java,
                MessagePreprocessor::class.java,
                DiscordParser.ParserOptions::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            PreHook { frame ->
                try {
                    val reply = frame.args[4] == DiscordParser.ParserOptions.REPLY
                    val ast = parser.parse(
                        frame.args[1] as? String ?: "",
                        scope = if (reply) Scope.REPLY else Scope.MESSAGE,
                    )
                    (frame.args[3] as MessagePreprocessor).process(ast)
                    val renderContext = frame.args[2] as MessageRenderContext
                    // Rendered before the suffixes so Discord trims the last block's line break first;
                    // otherwise the suffix would add an empty line after quotes and lists.
                    val builder = AstRenderer.render(ast, renderContext)
                    if (frame.args[5] == true) {
                        // Like desktop, "(edited)" goes below a code block instead of inside its box.
                        if (endsWithCodeBlock(builder)) builder.append('\n')
                        EditedMessageNode<MessageRenderContext>(frame.args[0] as Context).render(builder, renderContext)
                    }
                    ZeroSpaceWidthNode<MessageRenderContext>().render(builder, renderContext)
                    frame.result = builder
                } catch (error: Exception) {
                    logger.error("Could not render Markdown; using Discord's parser", error)
                }
            },
        )
    }

    private fun endsWithCodeBlock(builder: SpannableStringBuilder): Boolean =
        builder.getSpans(maxOf(0, builder.length - 1), builder.length, CodeBlockSpan::class.java).any {
            builder.getSpanEnd(it) == builder.length
        }

    /** Embed titles and field values use their own parsers. */
    private fun installEmbedParsing(parser: MarkdownParser) {
        val embed = Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed")

        fun field(name: String) = embed.getDeclaredField(name).apply { isAccessible = true }.get(null)
        val titles = field("UI_THREAD_TITLES_PARSER")
        val values = field("UI_THREAD_VALUES_PARSER")
        patcher.patch(
            Parser::class.java.getDeclaredMethod("parse", CharSequence::class.java, Any::class.java, List::class.java),
            PreHook { frame ->
                val scope = when (frame.thisObject) {
                    titles -> Scope.INLINE
                    values -> Scope.MESSAGE
                    else -> return@PreHook
                }
                try {
                    frame.result =
                        parser.parse(frame.args[0] as CharSequence, frame.args[1] as MessageParseState, scope)
                } catch (error: Exception) {
                    logger.error("Could not parse embed Markdown; using Discord's parser", error)
                }
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun installRichLinks(parser: MarkdownParser) {
        val mask = UrlNode::class.java.getDeclaredField("mask").apply { isAccessible = true }
        for (contextType in listOf(UrlNode.RenderContext::class.java, Any::class.java)) {
            patcher.patch(
                UrlNode::class.java.getDeclaredMethod("render", SpannableStringBuilder::class.java, contextType),
                PreHook { frame ->
                    val context = frame.args[1] as? MessageRenderContext ?: return@PreHook
                    val node = frame.thisObject as UrlNode<MessageRenderContext>
                    val label = mask.get(node) as? String ?: return@PreHook
                    val children = node.children ?: parser.parse(label, scope = Scope.LABEL)
                    MarkdownLinks.render(node, frame.args[0] as SpannableStringBuilder, context, label, children)
                    frame.result = null
                },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun installAnsiRendering() {
        patcher.patch(
            CodeNode::class.java.getDeclaredMethod("render", SpannableStringBuilder::class.java, Any::class.java),
            PreHook { frame ->
                val node = frame.thisObject as CodeNode<BasicRenderContext>
                val context = frame.args[1] as? BasicRenderContext ?: return@PreHook
                if (!node.a.equals("ansi", ignoreCase = true)) return@PreHook
                AnsiCodeBlocks.render(node, frame.args[0] as SpannableStringBuilder, context)
                frame.result = null
            },
        )
    }

    /** Remembers which views show Markdown, so game mentions and appearance changes can update them. */
    private fun installViewTracking(resolver: GameProfileResolver) {
        for (viewClass in listOf(SimpleDraweeSpanTextView::class.java, DiscordDraweeSpanTextView::class.java)) {
            patcher.patch(
                viewClass.getDeclaredMethod("setDraweeSpanStringBuilder", DraweeSpanStringBuilder::class.java),
                PreHook { frame ->
                    val view = frame.thisObject as TextView
                    val builder = frame.args[0] as? DraweeSpanStringBuilder
                    if (builder == null) {
                        textViews.remove(view)
                    } else {
                        resolver.update(builder)
                        textViews[view] = true
                    }
                },
            )
        }
    }

    internal fun refreshAppearance() {
        // Rebinding spans clears TextView's measurement cache and keeps the current spoiler state.
        for (view in textViews.keys.toList()) {
            val builder = when (view) {
                is SimpleDraweeSpanTextView -> view.j

                // A plain setText() detaches the builder, so a null field means the view moved on.
                is DiscordDraweeSpanTextView -> discordBuilder.get(view) as? DraweeSpanStringBuilder

                else -> null
            } ?: continue
            games?.update(builder)
            when (view) {
                is SimpleDraweeSpanTextView -> view.setDraweeSpanStringBuilder(builder)
                is DiscordDraweeSpanTextView -> view.setDraweeSpanStringBuilder(builder)
            }
        }
    }

    private val discordBuilder = DiscordDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder").apply {
        isAccessible = true
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        games?.stop()
        games = null
        GameProfileSheet.pluginResources = null
        textViews.clear()
    }
}
