package com.github.yutaplug.fixes.messagewidth

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemSticker
import com.discord.widgets.chat.list.adapter.WidgetChatListItem
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import com.github.yutaplug.fixes.Fix

/** Ported from zt64's MessageWidthFix: https://github.com/zt64/aliucord-plugins */
internal class MessageWidthFix :
    Fix(
        "MessageWidthFix",
        "Message width",
        "Lets embeds and stickers use the full chat width, so you can long-press beside them like other messages.",
        author = "zt64",
        // Chat rows that were already laid out keep the wider layout until Discord recreates them.
        requiresRestart = true,
    ) {
    override fun start(context: Context) {
        val embedCardId = Utils.getResId("chat_list_item_embed_container_card", "id")
        val stickerId = Utils.getResId("chat_list_adapter_item_sticker", "id")
        val guidelineId = Utils.getResId("uikit_guideline_chat", "dimen")

        // Subclasses call super.onConfigure first, so this runs before each item binds its content.
        patcher.patch(
            WidgetChatListItem::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { param ->
                when (val item = param.thisObject) {
                    is WidgetChatListAdapterItemEmbed -> {
                        val itemView = item.itemView
                        (itemView.layoutParams as? RecyclerView.LayoutParams)?.width =
                            RecyclerView.LayoutParams.MATCH_PARENT
                        val card = itemView.findViewById<View>(embedCardId) ?: return@Hook
                        (card.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                            width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                            horizontalBias = 0f
                            constrainedWidth = true
                        }
                    }

                    is WidgetChatListAdapterItemSticker -> {
                        val itemView = item.itemView
                        val params = itemView.layoutParams as? RecyclerView.LayoutParams ?: return@Hook
                        params.width = RecyclerView.LayoutParams.MATCH_PARENT
                        // Indent the sticker instead of the whole row.
                        params.marginStart = 0
                        val sticker = itemView.findViewById<View>(stickerId)
                        if (sticker != null && guidelineId != 0) {
                            (sticker.layoutParams as? FrameLayout.LayoutParams)?.marginStart =
                                itemView.resources.getDimensionPixelSize(guidelineId)
                        }

                        val entry = param.args[1] as? StickerEntry ?: return@Hook
                        val events = item.adapter.eventHandler
                        itemView.setOnLongClickListener {
                            events.onMessageLongClicked(entry.message, "", entry.message.hasThread())
                            true
                        }
                        // Keeps tap handling (such as TapTap) working on the wider row.
                        itemView.setOnClickListener {
                            events.onMessageClicked(entry.message, entry.message.hasThread())
                        }
                    }
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
