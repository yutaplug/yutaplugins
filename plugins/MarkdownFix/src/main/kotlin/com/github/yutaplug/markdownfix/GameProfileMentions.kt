package com.github.yutaplug.markdownfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import com.aliucord.Utils
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import rx.subscriptions.CompositeSubscription
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class GameProfileMentionRule(private val resolver: GameProfileResolver) :
    MessageRule(Pattern.compile("^<@\\$([0-9]{1,20})>")) {
    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec = MessageSpec(GameProfileMentionNode(match.group(1)!!, resolver), state)
}

private class GameProfileMentionNode(private val id: String, private val resolver: GameProfileResolver) :
    MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val start = builder.length
        val game = resolver.game(id)
        val background =
            MarkdownAppearance.themedColor(context.context, "theme_chat_mention_background", Color.TRANSPARENT)
        builder.append(GameProfileResolver.content(id, game, background))
        resolver.style(builder, context.context, id, start, builder.length)
        if (game == null || resolver.iconPending(id)) {
            // Span anchors survive emoji insertion, quote merging, and replacement of other mentions.
            builder.setSpan(GameMention(id, background), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (game == null) resolver.fetch(id)
        }
    }
}

private class GameMention(val id: String, val background: Int)

internal class ResolvedGame(val name: String, val icon: Bitmap?)

/** Draws a game's icon in place of the mention's "@", over the mention background ReplacementSpans don't get. */
private class GameIconSpan(private val icon: Bitmap, private val background: Int) : ReplacementSpan() {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint().apply { color = background }
    private val rect = RectF()
    private val clip = Path()

    private fun size(paint: Paint) = paint.textSize * 1.1f

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return (size(paint) + paint.textSize * 0.25f).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        if (Color.alpha(background) != 0) {
            canvas.drawRect(
                x,
                top.toFloat(),
                x + getSize(paint, text, start, end, null),
                bottom.toFloat(),
                backgroundPaint,
            )
        }
        val size = size(paint)
        val center = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        rect.set(x + paint.textSize * 0.1f, center - size / 2, x + paint.textSize * 0.1f + size, center + size / 2)
        clip.reset()
        clip.addRoundRect(rect, size * 0.25f, size * 0.25f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(icon, null, rect, bitmapPaint)
        canvas.restore()
    }
}

/**
 * Resolves game names and icons for mentions. The name is shown as soon as it is known and the icon is
 * added when it arrives; both are cached on disk so mentions render immediately after an app restart.
 */
internal class GameProfileResolver(private val cacheDir: File, private val onResolved: () -> Unit) {
    private val games = ConcurrentHashMap<String, ResolvedGame>()
    private val pendingIcons = ConcurrentHashMap.newKeySet<String>()
    private val requests = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val subscriptions = CompositeSubscription()

    @Volatile private var active = true

    fun game(id: String): ResolvedGame? = games[id]

    fun iconPending(id: String) = id in pendingIcons

    fun fetch(id: String) {
        val applicationId = id.toLongOrNull()?.takeIf { it > 0 } ?: return
        val lastFailure = failures[id]
        if (!active ||
            games.containsKey(id) ||
            (lastFailure != null && SystemClock.elapsedRealtime() - lastFailure < RETRY_DELAY) ||
            !requests.add(id)
        ) {
            return
        }
        Utils.threadPool.execute {
            if (!active) return@execute
            if (loadCached(id, applicationId)) return@execute
            try {
                val subscription = RestAPI.getApi().getApplications(applicationId).W(
                    { applications ->
                        val application = applications?.firstOrNull { it.g() == applicationId }
                        val name = application?.h()?.trim()?.takeIf { it.isNotEmpty() }
                        if (name == null) {
                            fail(id)
                        } else {
                            val iconHash = application.f()?.takeIf { it.isNotEmpty() }
                            writeName(id, name, iconHash)
                            resolveName(id, applicationId, name, iconHash)
                        }
                    },
                    { fail(id) },
                )
                subscriptions.a(subscription)
            } catch (_: Exception) {
                fail(id)
            }
        }
    }

    private fun loadCached(id: String, applicationId: Long): Boolean {
        val nameFile = File(cacheDir, "$id.name")
        if (!nameFile.exists() || System.currentTimeMillis() - nameFile.lastModified() > CACHE_TTL) return false
        val text = try {
            nameFile.readText()
        } catch (_: Exception) {
            return false
        }
        val newline = text.indexOf('\n')
        val name =
            (if (newline < 0) text else text.substring(0, newline)).trim().takeIf { it.isNotEmpty() } ?: return false
        val iconHash = if (newline < 0) null else text.substring(newline + 1).trim().takeIf { it.isNotEmpty() }
        val icon = File(cacheDir, "$id.png").takeIf { iconHash != null && it.exists() }?.let {
            try {
                BitmapFactory.decodeFile(it.path)
            } catch (_: Exception) {
                null
            }
        }
        if (icon != null || iconHash == null) {
            publish(id, ResolvedGame(name, icon), iconPending = false)
            requests.remove(id)
        } else {
            // The name is cached but the icon isn't (an earlier download failed); fetch only the icon.
            resolveName(id, applicationId, name, iconHash)
        }
        return true
    }

    private fun resolveName(id: String, applicationId: Long, name: String, iconHash: String?) {
        publish(id, ResolvedGame(name, null), iconPending = iconHash != null)
        requests.remove(id)
        if (iconHash == null) return
        Utils.threadPool.execute {
            val icon = downloadIcon(id, applicationId, iconHash)
            if (icon == null) {
                pendingIcons.remove(id)
            } else {
                publish(id, ResolvedGame(name, icon), iconPending = false)
            }
        }
    }

    private fun downloadIcon(id: String, applicationId: Long, hash: String): Bitmap? = try {
        val url = IconUtils.getApplicationIcon(applicationId, hash, ICON_SIZE)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) {
                null
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                BitmapFactory
                    .decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                    )?.also { writeFile(File(cacheDir, "$id.png"), bytes) }
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun writeName(id: String, name: String, iconHash: String?) =
        writeFile(File(cacheDir, "$id.name"), "$name\n${iconHash.orEmpty()}".toByteArray())

    private fun writeFile(file: File, bytes: ByteArray) {
        try {
            cacheDir.mkdirs()
            val temp = File(cacheDir, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: Exception) {
        }
    }

    private fun publish(id: String, game: ResolvedGame, iconPending: Boolean) {
        if (!active) return
        if (iconPending) pendingIcons.add(id) else pendingIcons.remove(id)
        games[id] = game
        Utils.mainThread.post { if (active) onResolved() }
    }

    private fun fail(id: String) {
        failures[id] = SystemClock.elapsedRealtime()
        requests.remove(id)
    }

    fun update(builder: SpannableStringBuilder): Boolean {
        var changed = false
        val mentions = builder
            .getSpans(
                0,
                builder.length,
                GameMention::class.java,
            ).sortedByDescending(builder::getSpanStart)
        for (mention in mentions) {
            val game = games[mention.id] ?: continue
            val start = builder.getSpanStart(mention)
            val end = builder.getSpanEnd(mention)
            if (start < 0 || end <= start) continue
            val coveringSpans = builder
                .getSpans(start, end, Any::class.java)
                .filter {
                    it !== mention && builder.getSpanStart(it) <= start && builder.getSpanEnd(it) >= end
                }.map { SpanRange(it, builder.getSpanStart(it), builder.getSpanEnd(it), builder.getSpanFlags(it)) }
            // Android can remove exclusive spans when their entire text is replaced. Restore their
            // order as well as their bounds so quote margins and hidden spoilers remain intact.
            coveringSpans.forEach { builder.removeSpan(it.span) }
            builder.removeSpan(mention)
            val content = content(mention.id, game, mention.background)
            builder.replace(start, end, content)
            val difference = content.length - (end - start)
            coveringSpans.forEach { builder.setSpan(it.span, it.start, it.end + difference, it.flags) }
            // The name can arrive before the icon; stay anchored so the icon is added when it does.
            if (iconPending(mention.id)) {
                builder.setSpan(mention, start, start + content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            changed = true
        }
        return changed
    }

    private data class SpanRange(
        val span: Any,
        val start: Int,
        val end: Int,
        val flags: Int,
    )

    fun style(builder: SpannableStringBuilder, context: Context, id: String, start: Int, end: Int) {
        val foreground = MarkdownAppearance.themedColor(context, "theme_chat_mention_foreground", Color.WHITE)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(foreground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            BackgroundColorSpan(
                MarkdownAppearance.themedColor(context, "theme_chat_mention_background", Color.TRANSPARENT),
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            ClickableSpan(
                foreground,
                false,
                null,
                { view ->
                    GameProfileSheet.open(view, id, games[id])
                    kotlin.Unit.a
                },
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    fun stop() {
        active = false
        subscriptions.unsubscribe()
        games.clear()
        pendingIcons.clear()
        requests.clear()
        failures.clear()
    }

    companion object {
        private const val RETRY_DELAY = 60_000L
        private const val CACHE_TTL = 7L * 24 * 60 * 60 * 1000
        private const val ICON_SIZE = 64
        private const val ICON_PLACEHOLDER = "￼"

        fun content(id: String, game: ResolvedGame?, background: Int): CharSequence {
            val icon = game?.icon ?: return "@${game?.name ?: id}"
            return SpannableStringBuilder(ICON_PLACEHOLDER + game.name).apply {
                setSpan(GameIconSpan(icon, background), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
