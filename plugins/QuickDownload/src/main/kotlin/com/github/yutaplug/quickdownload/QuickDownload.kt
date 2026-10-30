package com.github.yutaplug.quickdownload

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.EmbedType
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import java.lang.ref.WeakReference

/** Adds a download button on inline image, video and GIF media, like the one on file attachments. */
@AliucordPlugin
@Suppress("unused")
class QuickDownload : Plugin() {
    private val buttonId = View.generateViewId()
    private val buttons = mutableListOf<WeakReference<ImageView>>()

    init {
        settingsTab = SettingsTab(QuickDownloadSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
    }

    override fun start(context: Context) {
        patcher.patch(
            WidgetChatListAdapterItemAttachment::class.java,
            "configureUI",
            arrayOf(WidgetChatListAdapterItemAttachment.Model::class.java),
            Hook { call ->
                val holder = call.thisObject as WidgetChatListAdapterItemAttachment
                val model = call.args[0] as WidgetChatListAdapterItemAttachment.Model
                val attachment = model.attachmentEntry.attachment
                val media = holder.itemView.findViewById<InlineMediaView>(
                    Utils.getResId("chat_list_item_attachment_inline_media", "id"),
                ) ?: return@Hook
                val type = attachment.e()
                val enabled = if (attachment.a().endsWith(".gif", ignoreCase = true)) {
                    settings.getBool(KEY_GIFS, true)
                } else {
                    settings.getBool(KEY_MEDIA, true)
                }
                val visible = enabled &&
                    !model.isSpoilerHidden &&
                    (type == MessageAttachmentType.IMAGE || type == MessageAttachmentType.VIDEO)
                configureButton(media, visible, holder.adapter, attachment.f(), attachment.a())
            },
        )

        // Tenor/Giphy GIFs are "gifv" embeds rendered in the embed's own InlineMediaView.
        patcher.patch(
            WidgetChatListAdapterItemEmbed::class.java,
            "configureUI",
            arrayOf(WidgetChatListAdapterItemEmbed.Model::class.java),
            Hook { call ->
                val holder = call.thisObject as WidgetChatListAdapterItemEmbed
                val model = call.args[0] as WidgetChatListAdapterItemEmbed.Model
                val embed = model.embedEntry.embed
                val media = holder.itemView.findViewById<InlineMediaView>(
                    Utils.getResId("embed_inline_media", "id"),
                ) ?: return@Hook
                val url = embed.m()?.run { c() ?: b() } ?: embed.h()?.run { c() ?: b() }
                val visible = url != null &&
                    embed.k() == EmbedType.GIFV &&
                    settings.getBool(KEY_GIFS, true) &&
                    !model.isSpoilerHidden
                val fileName = url?.let { Uri.parse(it).lastPathSegment } ?: "gif.mp4"
                configureButton(media, visible, holder.adapter, url, fileName)
            },
        )
    }

    private fun configureButton(
        media: InlineMediaView,
        visible: Boolean,
        adapter: WidgetChatListAdapter,
        url: String?,
        fileName: String,
    ) {
        if (!visible || url == null || media.visibility != View.VISIBLE) {
            media.findViewById<View>(buttonId)?.visibility = View.GONE
            return
        }

        val button = media.findViewById<ImageView>(buttonId) ?: createButton(media)
        button.visibility = View.VISIBLE
        button.isEnabled = true
        button.alpha = ENABLED_ALPHA
        button.bringToFront()
        button.setOnClickListener {
            val started = adapter.eventHandler.onQuickDownloadClicked(Uri.parse(url), fileName)
            button.isEnabled = !started
            button.alpha = DISABLED_ALPHA
        }
    }

    // Matches InlineMediaView's own volume toggle (inline_media_view.xml), placed in the opposite corner.
    private fun createButton(media: InlineMediaView): ImageView {
        val context = media.context
        val size = DimenUtils.dpToPx(32)
        val margin = DimenUtils.dpToPx(8)
        return ImageView(context).apply {
            id = buttonId
            setBackgroundResource(Utils.getResId("drawable_circle_primary_900", "drawable"))
            setImageResource(Utils.getResId("ic_file_download_white_24dp", "drawable"))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(margin, margin, margin, margin)
            contentDescription = context.getString(Utils.getResId("download", "string"))
            media.addView(
                this,
                FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                    bottomMargin = margin
                    marginEnd = margin
                },
            )
            buttons.removeAll { it.get() == null }
            buttons.add(WeakReference(this))
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        buttons.forEach { reference ->
            val button = reference.get() ?: return@forEach
            (button.parent as? ViewGroup)?.removeView(button)
        }
        buttons.clear()
    }

    companion object {
        const val KEY_MEDIA = "media"
        const val KEY_GIFS = "gifs"
        private const val ENABLED_ALPHA = 0.6f
        private const val DISABLED_ALPHA = 0.3f
    }
}
