package com.github.yutaplug.onboarding

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem

/** Channel list entry shown under Events, like newer Discord's "Channels & Roles" row. */
internal object ChannelsRolesItem : ChannelListItem {
    const val TYPE = 0x0B0A

    override fun getKey() = "onboarding_channels_and_roles"

    override fun getType() = TYPE
}

/** Reuses Discord's Events row layout so the icon, font and press highlight match. */
internal class ChannelsRolesHolder(
    layout: Int,
    adapter: WidgetChannelsListAdapter,
    private val onClick: (View, Long) -> Unit,
) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(layout, adapter) {
    override fun onConfigure(position: Int, data: ChannelListItem) {
        super.onConfigure(position, data)
        val context = itemView.context
        itemView.findViewById<ImageView>(Utils.getResId("channels_list_item_guild_scheduled_event_icon", "id"))
            ?.setImageResource(Utils.getResId("ic_settings_24dp", "drawable"))
        itemView.findViewById<TextView>(Utils.getResId("channels_list_item_guild_scheduled_event_label", "id"))
            ?.text = "Channels & Roles"
        itemView.findViewById<View>(Utils.getResId("channels_list_item_guild_scheduled_event_count", "id"))
            ?.visibility = View.GONE
        val overlay = Utils.getResId("drawable_overlay_channels", "attr")
        val value = android.util.TypedValue()
        if (overlay != 0 && context.theme.resolveAttribute(overlay, value, true) && value.resourceId != 0) {
            val left = itemView.paddingLeft
            val top = itemView.paddingTop
            val right = itemView.paddingRight
            val bottom = itemView.paddingBottom
            itemView.background = ContextCompat.getDrawable(context, value.resourceId)
            itemView.setPadding(left, top, right, bottom)
        }
        itemView.setOnClickListener { view ->
            val guildId = adapter.selectedGuildId
            if (guildId != 0L) onClick(view, guildId)
        }
    }
}
