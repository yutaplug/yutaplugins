package com.github.yutaplug.customtypingindicator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.models.member.GuildMember
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.user.usersheet.WidgetUserSheet
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Adds avatars and tap-to-open profiles to the names in the typing bar's text. */
internal object TypingNames {
    private const val AVATAR_SIZE = 64
    private val logger = Logger("CustomTypingIndicator")
    private val avatars = ConcurrentHashMap<String, Bitmap>()
    private val loading: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Called with every typing bar text view to redraw once an avatar finishes loading. */
    var onAvatarLoaded: () -> Unit = {}

    fun clearCache() {
        avatars.clear()
        loading.clear()
    }

    fun decorate(text: TextView, userIds: Collection<Long>, avatarsOn: Boolean, clickableOn: Boolean) {
        val original = text.text
        if (!avatarsOn && !clickableOn || original.isNullOrEmpty() || userIds.isEmpty()) {
            if (!clickableOn) text.setOnTouchListener(null)
            return
        }
        val channel = StoreStream.getChannelsSelected().selectedChannel
        val guildId = channel?.i() ?: 0L
        val channelId = channel?.k()
        val users = StoreStream.getUsers().users
        val members = if (guildId != 0L) StoreStream.getGuilds().members[guildId] else null

        val builder = SpannableStringBuilder(original)
        var cursor = 0
        var changed = false
        for (id in userIds) {
            val user = users[id] ?: continue
            val name: String = GuildMember.Companion!!.getNickOrUsername(members?.get(id), user) ?: continue
            if (name.isEmpty()) continue
            var start = builder.toString().indexOf(name, cursor)
            if (start < 0) start = builder.toString().indexOf(name)
            if (start < 0) continue
            var end = start + name.length
            if (clickableOn) {
                builder.setSpan(ProfileSpan(id, channelId, guildId), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (avatarsOn) {
                val url = IconUtils.getForUser(user, false, AVATAR_SIZE)
                if (url != null) {
                    load(text.context, url)
                    builder.insert(start, "￼")
                    builder.setSpan(AvatarSpan(url), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    end++
                }
            }
            cursor = end
            changed = true
        }
        if (!changed) return
        text.text = builder
        if (clickableOn) text.setOnTouchListener(ProfileTouch) else text.setOnTouchListener(null)
    }

    fun reset(text: TextView) {
        text.setOnTouchListener(null)
    }

    private fun load(context: Context, url: String) {
        if (avatars.containsKey(url) || !loading.add(url)) return
        val assets = context.applicationContext.assets
        Utils.threadPool.execute {
            val bitmap = try {
                if (url.startsWith(ASSET_PREFIX)) {
                    assets.open(url.substring(ASSET_PREFIX.length)).use { BitmapFactory.decodeStream(it) }
                } else {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }
            } catch (error: Throwable) {
                logger.error("Failed to load avatar $url", error)
                null
            }
            if (bitmap != null) avatars[url] = bitmap
            loading.remove(url)
            if (bitmap != null) Utils.mainThread.post { onAvatarLoaded() }
        }
    }

    private const val ASSET_PREFIX = "asset://asset/"

    /** A round avatar the height of the text, with a small gap before the name. */
    private class AvatarSpan(private val url: String) : ReplacementSpan() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()

        private fun size(paint: Paint) = (paint.textSize * 1.25f).toInt()

        private fun gap(paint: Paint) = (paint.textSize * 0.3f).toInt()

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                paint.getFontMetricsInt(fm)
            }
            return size(paint) + gap(paint)
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
            val bitmap = avatars[url] ?: return
            val size = size(paint).toFloat()
            val metrics = paint.fontMetrics
            // Center the avatar on the text's line box rather than the whole row.
            val lineCenter = y + (metrics.ascent + metrics.descent) / 2f
            val left = x
            val topEdge = lineCenter - size / 2f
            val scale = size / minOf(bitmap.width, bitmap.height)
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(left, topEdge)
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(matrix)
            this.paint.shader = shader
            canvas.drawCircle(left + size / 2f, topEdge + size / 2f, size / 2f, this.paint)
        }
    }

    private class ProfileSpan(private val userId: Long, private val channelId: Long?, private val guildId: Long) :
        ClickableSpan() {
        override fun onClick(widget: View) {
            WidgetUserSheet.show(
                userId,
                channelId,
                Utils.appActivity.supportFragmentManager,
                guildId.takeIf { it != 0L },
            )
        }

        // Keep Discord's bold name styling; no link color or underline.
        override fun updateDrawState(ds: TextPaint) {}
    }

    /** Taps the profile span under the finger, without LinkMovementMethod's scrolling of single-line text. */
    private object ProfileTouch : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val text = view as? TextView ?: return false
            val spanned = text.text as? Spanned ?: return false
            val offset = text.getOffsetForPosition(event.x, event.y)
            val span = spanned.getSpans(offset, offset, ProfileSpan::class.java).firstOrNull()
                ?: if (offset > 0) spanned.getSpans(offset - 1, offset - 1, ProfileSpan::class.java).firstOrNull() else null
            if (span == null) return false
            if (event.action == MotionEvent.ACTION_UP) span.onClick(text)
            return true
        }
    }
}
