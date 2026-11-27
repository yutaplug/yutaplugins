package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.patcher.Hook
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.util.WeakHashMap

/**
 * Marks deleted messages (text color and a "(deleted: …)" tag) and shows earlier versions above edited ones.
 *
 * Discord rebuilds a message's text from scratch every time a row is bound, so decorating once right after
 * `onConfigure` is enough; to update a row, `onConfigure` is simply run again.
 */
internal class ChatDecorations(
    private val settings: SettingsAPI,
    private val record: (Long) -> MessageRecord?,
    private val shown: (Message) -> Unit,
    private val reportError: (String, Throwable) -> Unit,
) {
    private class Bound(val position: Int, val entry: MessageEntry, val record: MessageRecord?, val look: Int)

    private val rows = WeakHashMap<WidgetChatListAdapterItemMessage, Bound>()
    private val textId = Utils.getResId("chat_list_adapter_item_text", "id")

    @Volatile private var look = 0

    private val textField = SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder")
        .apply { isAccessible = true }
    private val renderContext = WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
        "getMessageRenderContext",
        Context::class.java,
        MessageEntry::class.java,
        Function1::class.java,
    ).apply { isAccessible = true }
    private val preprocessor = WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
        "getMessagePreprocessor",
        Long::class.javaPrimitiveType,
        Message::class.java,
        StoreMessageState.State::class.java,
    ).apply { isAccessible = true }
    private val spoilers = WidgetChatListAdapterItemMessage::class.java
        .getDeclaredMethod("getSpoilerClickHandler", Message::class.java)
        .apply { isAccessible = true }

    fun patch(patcher: PatcherAPI) {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
                "onConfigure",
                Int::class.javaPrimitiveType,
                ChatListEntry::class.java,
            ),
            Hook { frame ->
                val entry = frame.args[1] as? MessageEntry ?: return@Hook
                val row = frame.thisObject as WidgetChatListAdapterItemMessage
                shown(entry.message)
                val saved = record(entry.message.id)
                synchronized(rows) { rows[row] = Bound(frame.args[0] as Int, entry, saved, look) }
                if (saved != null) {
                    try {
                        decorate(row, entry, saved)
                    } catch (error: Exception) {
                        reportError("mark a logged message", error)
                    }
                }
            },
        )
    }

    /** Rebinds rows whose logged state changed since they were drawn; [appearance] rebinds all of them. */
    fun refresh(appearance: Boolean = false) {
        if (appearance) look++
        val snapshot = synchronized(rows) { rows.entries.map { it.key to it.value } }
        for ((row, bound) in snapshot) {
            row.itemView.post {
                val current = synchronized(rows) { rows[row] } ?: return@post
                if (current !== bound) return@post
                if (current.look == look && record(current.entry.message.id) === current.record) return@post
                row.onConfigure(current.position, current.entry)
            }
        }
    }

    /** Returns every row to Discord's own rendering. Call after unpatching. */
    fun close() {
        val snapshot = synchronized(rows) { rows.entries.map { it.key to it.value }.also { rows.clear() } }
        for ((row, bound) in snapshot) row.itemView.post { row.onConfigure(bound.position, bound.entry) }
    }

    private fun decorate(row: WidgetChatListAdapterItemMessage, entry: MessageEntry, saved: MessageRecord) {
        val view = row.itemView.findViewById<View>(textId) as? SimpleDraweeSpanTextView ?: return
        val history = saved.edits.isNotEmpty()
        if (!history && !saved.deleted) return
        val text = textField.get(view) as? DraweeSpanStringBuilder ?: DraweeSpanStringBuilder()
        val ui = Ui(view.context)
        if (saved.deleted) {
            if (text.isNotEmpty()) {
                text.setSpan(ForegroundColorSpan(Prefs.textColor(settings)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (Prefs.showDeletedTag(settings)) {
                val tag = small(
                    if (saved.deletedTimestamp == null) {
                        " (deleted)"
                    } else {
                        " (deleted: ${Dates.format(view.context, saved.deletedTimestamp)})"
                    },
                    Prefs.labelColor(settings),
                )
                text.append(tag)
            }
        }
        if (history) text.insert(0, previousVersions(row, entry, saved, ui))
        val autoLink = view.autoLinkMask
        view.autoLinkMask = 0
        try {
            view.setDraweeSpanStringBuilder(text)
        } finally {
            view.autoLinkMask = autoLink
        }
        // Discord hides the text of messages without any, like an image-only message.
        if (text.isNotEmpty()) view.visibility = View.VISIBLE
    }

    /** Earlier versions, oldest first, rendered with Discord's own markdown parser in a muted color. */
    private fun previousVersions(
        row: WidgetChatListAdapterItemMessage,
        entry: MessageEntry,
        saved: MessageRecord,
        ui: Ui,
    ): DraweeSpanStringBuilder {
        val context = ui.context
        val message = entry.message
        val rendering = renderContext.invoke(row, context, entry, spoilers.invoke(row, message)) as MessageRenderContext
        val processing = preprocessor.invoke(row, Filters.myId() ?: 0L, message, entry.messageState) as MessagePreprocessor
        val options = if (message.isWebhook) {
            DiscordParser.ParserOptions.ALLOW_MASKED_LINKS
        } else {
            DiscordParser.ParserOptions.DEFAULT
        }
        val result = DraweeSpanStringBuilder()
        for (edit in saved.edits) {
            if (edit.content.isNotEmpty()) {
                result.append(DiscordParser.parseChannelMessage(context, edit.content, rendering, processing, options, false))
                result.append(" ")
            }
            result.append(small("(edited: ${Dates.format(context, edit.timestamp, DateUtils.DAY_IN_MILLIS)})", ui.mutedColor))
            for (attachment in edit.removed) result.append("\n").append(small("Removed attachment: ${attachment.a()}", ui.mutedColor))
            result.append("\n")
        }
        result.setSpan(ForegroundColorSpan(ui.mutedColor), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return result
    }

    private fun small(value: String, color: Int) = SpannableStringBuilder(value).apply {
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(0.75f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
