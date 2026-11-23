package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.format.DateUtils
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.patcher.Hook
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.DrawableCompat
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.util.WeakHashMap

internal class MessageDecorations(
    private val settings: SettingsAPI,
    private val record: (Long) -> MessageRecord?,
    private val onBound: (Message) -> Unit,
    private val reportError: (String, Throwable) -> Unit,
) {
    private val bound = WeakHashMap<WidgetChatListAdapterItemMessage, MessageEntry>()
    private var closed = false
    private val nativeBuilderField = SimpleDraweeSpanTextView::class.java
        .getDeclaredField("mDraweeStringBuilder")
        .apply { isAccessible = true }
    private val renderContext = WidgetChatListAdapterItemMessage::class.java
        .getDeclaredMethod(
            "getMessageRenderContext",
            Context::class.java,
            MessageEntry::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
    private val preprocessor = WidgetChatListAdapterItemMessage::class.java
        .getDeclaredMethod(
            "getMessagePreprocessor",
            Long::class.javaPrimitiveType,
            Message::class.java,
            StoreMessageState.State::class.java,
        ).apply { isAccessible = true }
    private val spoilerHandler = WidgetChatListAdapterItemMessage::class.java
        .getDeclaredMethod(
            "getSpoilerClickHandler",
            Message::class.java,
        ).apply { isAccessible = true }
    private val bindingGetter = WidgetChatListActions::class.java.getDeclaredMethod("getBinding").apply {
        isAccessible =
            true
    }

    fun patch(patcher: PatcherAPI) {
        patcher.patchRequired(
            WidgetChatListAdapterItemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { frame ->
                val entry = frame.args[1] as? MessageEntry ?: return@Hook
                val item = frame.thisObject as WidgetChatListAdapterItemMessage
                synchronized(bound) { bound[item] = entry }
                onBound(entry.message)
                schedule(item, entry, false)
            },
        )
        patcher.patchRequired(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook { frame ->
                val item = frame.thisObject as WidgetChatListAdapterItemMessage
                val view = frame.args[0] as SimpleDraweeSpanTextView
                val entry = frame.args[1] as MessageEntry
                renderHistory(item, view, entry, record(entry.message.id))
            },
        )
    }

    fun refresh(removeHistory: Boolean = false) {
        val snapshot = synchronized(bound) { bound.entries.map { it.key to it.value } }
        snapshot.forEach { (item, entry) -> schedule(item, entry, removeHistory) }
    }

    private fun schedule(item: WidgetChatListAdapterItemMessage, entry: MessageEntry, history: Boolean) {
        item.itemView.post {
            if (closed || synchronized(bound) { bound[item]?.message?.id } != entry.message.id) return@post
            val view =
                item.itemView.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id")) ?: return@post
            val saved = record(entry.message.id)
            if (history && view is SimpleDraweeSpanTextView) renderHistory(item, view, entry, saved)
            applyDeletedLabel(view, saved?.takeIf { it.deleted })
        }
    }

    private fun nativeBuilder(view: TextView): DraweeSpanStringBuilder? =
        if (view is SimpleDraweeSpanTextView) nativeBuilderField.get(view) as? DraweeSpanStringBuilder else null

    private fun renderHistory(
        item: WidgetChatListAdapterItemMessage,
        view: SimpleDraweeSpanTextView,
        entry: MessageEntry,
        saved: MessageRecord?,
    ) {
        try {
            val current = nativeBuilder(view) ?: return
            var removed = false
            current.getSpans(0, current.length, InlineHistorySpan::class.java).forEach { span ->
                val start = current.getSpanStart(span)
                val end = current.getSpanEnd(span)
                current.removeSpan(span)
                if (start >= 0 && end > start && end <= current.length) {
                    current.delete(start, end)
                    removed = true
                }
            }
            if (!settings.getBool(BetterMessageLogger.INLINE_EDIT_HISTORY, false) || saved?.edits.isNullOrEmpty()) {
                if (removed) rebind(view, current)
                return
            }
            val message = entry.message
            val context = view.context
            val rendering = renderContext.invoke(
                item,
                context,
                entry,
                spoilerHandler.invoke(item, message),
            ) as MessageRenderContext
            val processing = preprocessor.invoke(
                item,
                StoreStream.getUsers().me.id,
                message,
                entry.messageState,
            ) as MessagePreprocessor
            val options =
                if (message.isWebhook) {
                    DiscordParser.ParserOptions.ALLOW_MASKED_LINKS
                } else {
                    DiscordParser.ParserOptions.DEFAULT
                }
            val builder = DraweeSpanStringBuilder()
            saved.edits.forEach { edit ->
                builder.append(
                    DiscordParser.parseChannelMessage(context, edit.content, rendering, processing, options, false),
                )
                val start = builder.length
                builder
                    .append(" (edited: ")
                    .append(
                        Timestamps.custom(edit.timestamp, context) ?: DateUtils.getRelativeDateTimeString(
                            context,
                            edit.timestamp,
                            DateUtils.DAY_IN_MILLIS,
                            DateUtils.DAY_IN_MILLIS * 2,
                            DateUtils.FORMAT_ABBREV_ALL,
                        ),
                    ).append(")\n")
                builder.setSpan(RelativeSizeSpan(0.75f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val end = builder.length
            if (end == 0) return
            builder.setSpan(ForegroundColorSpan(LoggerUi(context).muted), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(InlineHistorySpan(), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(current)
            rebind(view, builder)
        } catch (error: Exception) {
            reportError("Could not render inline edit history", error)
        }
    }

    /** " (deleted: 5 min. ago)", in the same format as the inline "(edited: …)" history. */
    private fun deletedLabel(context: Context, timestamp: Long?): String {
        timestamp ?: return " (deleted)"
        val time = Timestamps.custom(timestamp, context) ?: DateUtils.getRelativeDateTimeString(
            context,
            timestamp,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.DAY_IN_MILLIS * 2,
            DateUtils.FORMAT_ABBREV_ALL,
        )
        return " (deleted: $time)"
    }

    private fun applyDeletedLabel(view: TextView, record: MessageRecord?) {
        val deleted = record != null
        val label = if (deleted) deletedLabel(view.context, record?.deletedTimestamp) else ""
        val native = nativeBuilder(view)
        // Discord's native builder owns the emoji drawables. Never replace it with plain spans.
        if (native == null && view is SimpleDraweeSpanTextView) return
        val builder = native ?: (view.text as? SpannableStringBuilder ?: SpannableStringBuilder(view.text ?: ""))
        val labels = builder.getSpans(0, builder.length, DeletedLabelSpan::class.java)
        val colors = builder.getSpans(0, builder.length, DeletedMessageColorSpan::class.java)
        val showTag = settings.getBool(BetterMessageLogger.SHOW_DELETED_TAG, true)
        val labelColor = color(BetterMessageLogger.DELETED_LABEL_COLOR, BetterMessageLogger.DEFAULT_DELETED_LABEL_COLOR)
        val textColor =
            color(BetterMessageLogger.DELETED_MESSAGE_COLOR, BetterMessageLogger.DEFAULT_DELETED_MESSAGE_COLOR)
        if (!deleted && labels.isEmpty() && colors.isEmpty()) return
        if (deleted && labels.size == (if (showTag) 1 else 0)) {
            val end = if (showTag) builder.getSpanStart(labels[0]) else builder.length
            val validLabel = !showTag ||
                (
                    end == builder.length - label.length &&
                        end >= 0 &&
                        labels[0].color == labelColor &&
                        builder.subSequence(end, builder.length).toString() == label
                )
            if (validLabel &&
                (
                    end == 0 ||
                        (
                            colors.size == 1 &&
                                colors[0].color == textColor &&
                                builder.getSpanStart(colors[0]) == 0 &&
                                builder.getSpanEnd(colors[0]) == end
                        )
                )
            ) {
                return
            }
        }
        colors.forEach(builder::removeSpan)
        labels.forEach {
            val start = builder.getSpanStart(it)
            val end = builder.getSpanEnd(it)
            builder.removeSpan(it)
            if (start >= 0 && end >= start) builder.delete(start, end)
        }
        if (deleted) {
            if (builder.isNotEmpty()) {
                builder.setSpan(
                    DeletedMessageColorSpan(textColor),
                    0,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (showTag) {
                val start = builder.length
                builder.append(label)
                builder.setSpan(DeletedLabelSpan(labelColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (native != null && !deleted) {
            // Rebinding recycled live rows can discard PluginDownloader's long-press callbacks.
            view.requestLayout()
            view.invalidate()
        } else if (native != null) {
            rebind(view as SimpleDraweeSpanTextView, native)
        } else {
            view.setText(builder, TextView.BufferType.SPANNABLE)
        }
    }

    private fun rebind(view: SimpleDraweeSpanTextView, builder: DraweeSpanStringBuilder) {
        val autoLink = view.autoLinkMask
        view.autoLinkMask = 0
        try {
            view.setDraweeSpanStringBuilder(builder)
        } finally {
            view.autoLinkMask = autoLink
        }
    }

    private fun color(key: String, fallback: String): Int = try {
        Color.parseColor(settings.getString(key, fallback))
    } catch (_: IllegalArgumentException) {
        Color.parseColor(fallback)
    }

    fun clearActions(sheet: WidgetChatListActions) {
        try {
            val rows = actionRows(sheet) ?: return
            listOf("History", "Delete").forEach { name ->
                rows.findViewWithTag<View>("BetterMessageLogger.$name")?.let(rows::removeView)
            }
        } catch (error: Exception) {
            reportError("Could not update logger actions", error)
        }
    }

    fun addAction(sheet: WidgetChatListActions, name: String, title: String, icon: String, action: () -> Unit) {
        try {
            val rows = actionRows(sheet) ?: return
            val context = sheet.requireContext()
            val template = rows.findViewById<TextView>(Utils.getResId("dialog_chat_actions_edit", "id"))
            val style = Utils.getResId("UiKit_Settings_Item_Icon", "style")
            val view = if (style == 0) TextView(context) else TextView(context, null, 0, style)
            template?.let {
                view.setTextColor(it.textColors)
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize)
                view.typeface = it.typeface
                view.gravity = it.gravity
                view.includeFontPadding = it.includeFontPadding
                view.compoundDrawablePadding = it.compoundDrawablePadding
                view.setPaddingRelative(it.paddingStart, it.paddingTop, it.paddingEnd, it.paddingBottom)
                view.minHeight = it.minimumHeight
                view.maxLines = it.maxLines
                view.ellipsize = it.ellipsize
                view.background = it.background?.constantState?.newDrawable(view.resources)?.mutate()
                view.layoutParams =
                    (it.layoutParams as? LinearLayout.LayoutParams)?.let { params -> LinearLayout.LayoutParams(params) }
                TextViewCompat.setCompoundDrawableTintList(view, TextViewCompat.getCompoundDrawableTintList(it))
            }
            view.tag = "BetterMessageLogger.$name"
            view.text = title
            val drawable = Utils.getResId(icon, "drawable")
            if (drawable != 0) {
                DrawableCompat.setCompoundDrawablesCompat(
                    view,
                    DrawableCompat.getDrawable(context, drawable, LoggerUi(context).muted),
                    null,
                    null,
                    null,
                )
            }
            view.setOnClickListener { action() }
            val reaction = rows.findViewById<View>(Utils.getResId("dialog_chat_actions_add_reaction_emojis_list", "id"))
            val index = reaction?.let(rows::indexOfChild) ?: -1
            rows.addView(view, if (index >= 0) index + 1 else rows.childCount)
        } catch (error: Exception) {
            reportError("Could not add logger action", error)
        }
    }

    private fun actionRows(sheet: WidgetChatListActions): ViewGroup? {
        val binding = bindingGetter.invoke(sheet)
        val root = binding.javaClass.getMethod("getRoot").invoke(binding) as? ViewGroup ?: return null
        return root.getChildAt(0) as? ViewGroup
    }

    fun close() {
        closed = true
        val snapshot = synchronized(bound) { bound.keys.toList() }
        snapshot.forEach { item ->
            item.itemView.post {
                val view =
                    item.itemView.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id"))
                        ?: return@post
                val builder = nativeBuilder(view)
                if (builder != null) {
                    builder.getSpans(0, builder.length, InlineHistorySpan::class.java).forEach { span ->
                        val start = builder.getSpanStart(span)
                        val end = builder.getSpanEnd(span)
                        builder.removeSpan(span)
                        if (start >= 0 && end > start && end <= builder.length) builder.delete(start, end)
                    }
                    rebind(view as SimpleDraweeSpanTextView, builder)
                }
                applyDeletedLabel(view, null)
            }
        }
        synchronized(bound) { bound.clear() }
    }

    private class InlineHistorySpan

    private open class DeletedColorSpan(val color: Int) : CharacterStyle() {
        override fun updateDrawState(paint: TextPaint) {
            paint.color = color
        }
    }

    private class DeletedMessageColorSpan(color: Int) : DeletedColorSpan(color)

    private class DeletedLabelSpan(color: Int) : DeletedColorSpan(color) {
        override fun updateDrawState(paint: TextPaint) {
            super.updateDrawState(paint)
            paint.textSize *= 0.75f
        }
    }
}
