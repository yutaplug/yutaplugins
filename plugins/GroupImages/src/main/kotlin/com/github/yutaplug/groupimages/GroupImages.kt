package com.github.yutaplug.groupimages

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.media.WidgetMedia

/** Shows a message's images and videos as one grid, and lets the fullscreen viewer switch between them. */
@AliucordPlugin
@Suppress("unused")
class GroupImages : Plugin() {
    private val gridId = View.generateViewId()

    init {
        settingsTab = SettingsTab(GroupImagesSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
    }

    override fun start(context: Context) {
        // Keep only the first grouped attachment's chat row; it draws the whole grid.
        val createEmbedEntries = Class.forName("com.discord.widgets.chat.list.entries.ChatListEntry\$Companion")
            .declaredMethods.first { it.name == "createEmbedEntries" }
        patcher.patch(
            createEmbedEntries,
            Hook { call ->
                val entries = call.result as? List<*> ?: return@Hook
                val message = call.args[0] as? Message ?: return@Hook
                val group = groupedMedia(message)
                if (group.size < 2) return@Hook
                call.result = entries.filter { entry ->
                    entry !is AttachmentEntry || entry.attachment === group[0] || group.none { it === entry.attachment }
                }
            },
        )

        patcher.patch(
            WidgetChatListAdapterItemAttachment::class.java,
            "configureUI",
            arrayOf(WidgetChatListAdapterItemAttachment.Model::class.java),
            Hook { call ->
                val holder = call.thisObject as WidgetChatListAdapterItemAttachment
                val model = call.args[0] as WidgetChatListAdapterItemAttachment.Model
                configureGrid(holder, model.attachmentEntry)
            },
        )

        for (method in listOf("onPause", "onResume")) {
            patcher.patch(
                WidgetChatListAdapterItemAttachment::class.java,
                method,
                arrayOf(),
                Hook { call ->
                    val holder = call.thisObject as WidgetChatListAdapterItemAttachment
                    val grid = holder.itemView.findViewById<ViewGroup>(gridId) ?: return@Hook
                    forEachTile(grid) { if (method == "onPause") it.onPause() else it.onResume() }
                },
            )
        }

        MediaGallery.patch(patcher) { settings.getBool(KEY_ARROWS, true) }

        // Uninstall after every plugin has started: unloading during Aliucord's start loop
        // would modify the plugin map while it is iterated.
        val conflicts = ConflictingPlugins.installed()
        if (conflicts.isNotEmpty()) {
            Utils.mainThread.post {
                val removed = ConflictingPlugins.uninstall(conflicts, logger)
                if (removed.isNotEmpty()) {
                    Utils.showToast("GroupImages replaced and uninstalled: ${removed.joinToString(", ")}", true)
                }
            }
        }
    }

    private fun configureGrid(holder: WidgetChatListAdapterItemAttachment, entry: AttachmentEntry) {
        val root = holder.itemView as? ConstraintLayout ?: return
        val group = groupedMedia(entry.message)
        val existing = root.findViewById<CardView>(gridId)
        if (group.size < 2 || entry.attachment !== group[0]) {
            existing?.visibility = View.GONE
            return
        }

        root.findViewById<View>(Utils.getResId("chat_list_item_attachment_inline_media", "id"))?.visibility =
            View.GONE
        root.findViewById<View>(Utils.getResId("chat_list_item_attachment_card", "id"))?.visibility = View.GONE

        val card = existing ?: createGrid(root)
        card.visibility = View.VISIBLE
        val tiles = card.getChildAt(0) as FrameLayout

        val width = EmbedResourceUtils.INSTANCE.computeMaximumImageWidthPx(root.context)
        val (rects, height) = layoutTiles(group.size, width, DimenUtils.dpToPx(GAP_DP))
        val params = card.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            card.layoutParams = params
        }

        while (tiles.childCount > group.size) tiles.removeViewAt(tiles.childCount - 1)
        group.forEachIndexed { index, attachment ->
            val tile = tiles.getChildAt(index) as? InlineMediaView ?: createTile(tiles)
            val rect = rects[index]
            val lp = tile.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = rect[0]
            lp.topMargin = rect[1]
            tile.layoutParams = lp
            tile.updateUIWithAttachment(attachment, rect[2], rect[3], entry.autoPlayGifs)
            tile.setOnClickListener { MediaGallery.open(it.context, group, index) }
            tile.setOnLongClickListener {
                root.performLongClick()
                true
            }
        }
    }

    private fun createGrid(root: ConstraintLayout): CardView {
        val context = root.context
        val card = CardView(context).apply {
            id = gridId
            radius = DimenUtils.dpToPx(8).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
            addView(FrameLayout(context), FrameLayout.LayoutParams(-1, -1))
        }
        val params = ConstraintLayout.LayoutParams(0, 0).apply {
            startToEnd = Utils.getResId("uikit_chat_guideline", "id")
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            horizontalBias = 0f
            bottomMargin = context.resources.getDimensionPixelSize(
                Utils.getResId("chat_cell_vertical_spacing_padding", "dimen"),
            )
        }
        root.addView(card, params)
        return card
    }

    private fun createTile(tiles: FrameLayout): InlineMediaView {
        val tile = InlineMediaView(tiles.context).apply {
            radius = 0f
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
        }
        tiles.addView(tile, FrameLayout.LayoutParams(0, 0))
        return tile
    }

    private fun forEachTile(grid: ViewGroup, action: (InlineMediaView) -> Unit) {
        val tiles = grid.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until tiles.childCount) (tiles.getChildAt(i) as? InlineMediaView)?.let(action)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    companion object {
        private const val GAP_DP = 4
        const val KEY_ARROWS = "arrows"

        /** The attachments shown together in the grid: inline images and videos that aren't spoilers. */
        fun groupedMedia(message: Message): List<MessageAttachment> {
            if (!StoreStream.getUserSettings().getIsAttachmentMediaInline()) return emptyList()
            val media = message.attachments?.filter {
                (it.e() == MessageAttachmentType.IMAGE || it.e() == MessageAttachmentType.VIDEO) &&
                    !it.h() && (it.g() ?: 0) > 0 && (it.b() ?: 0) > 0
            } ?: return emptyList()
            return if (media.size >= 2) media else emptyList()
        }

        /**
         * Mosaic like newer Discord: 2 side by side, 3 as one big + two stacked, 4 as 2x2, and
         * otherwise rows of three with a leftover row of one or two on top.
         * Returns each tile as [x, y, width, height] and the total height.
         */
        fun layoutTiles(count: Int, width: Int, gap: Int): Pair<List<IntArray>, Int> {
            if (count == 3) {
                val big = (width - gap) * 2 / 3
                val small = width - gap - big
                val half = (big - gap) / 2
                return listOf(
                    intArrayOf(0, 0, big, big),
                    intArrayOf(big + gap, 0, small, half),
                    intArrayOf(big + gap, half + gap, small, big - half - gap),
                ) to big
            }
            val rows = mutableListOf<Int>()
            when (count) {
                2 -> rows.add(2)
                4 -> rows.addAll(listOf(2, 2))
                else -> {
                    if (count % 3 != 0) rows.add(count % 3)
                    repeat(count / 3) { rows.add(3) }
                }
            }
            val rects = mutableListOf<IntArray>()
            var y = 0
            for (perRow in rows) {
                val tileWidth = (width - (perRow - 1) * gap) / perRow
                val rowHeight = if (perRow == 1) width / 2 else tileWidth
                for (i in 0 until perRow) {
                    val x = i * (tileWidth + gap)
                    val w = if (i == perRow - 1) width - x else tileWidth
                    rects.add(intArrayOf(x, y, w, rowHeight))
                }
                y += rowHeight + gap
            }
            return rects to y - gap
        }
    }
}
