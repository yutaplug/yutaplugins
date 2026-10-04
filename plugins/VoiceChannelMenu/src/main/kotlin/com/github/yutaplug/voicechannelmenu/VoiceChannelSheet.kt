package com.github.yutaplug.voicechannelmenu

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.DrawableCompat
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.voice.settings.WidgetVoiceChannelSettings

/**
 * Discord's own channel action sheet (`widget_channels_list_item_actions`) with only the rows that
 * apply to a voice channel: Copy Link, Copy ID and, for managers, Edit Channel.
 */
class VoiceChannelSheet : BottomSheet() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val channelId = arguments?.getLong(ARG_CHANNEL) ?: 0L
        val channel = StoreStream.getChannels().getChannel(channelId)
        if (channel == null) {
            dismissAllowingStateLoss()
            return
        }
        val canManage = arguments?.getBoolean(ARG_MANAGE) == true
        val layout = Utils.getResId("widget_channels_list_item_actions", "layout")
        val root = LayoutInflater.from(context).inflate(layout, linearLayout, false)
        linearLayout.addView(root)

        fun <T : View> find(name: String) = root.findViewById<T>(Utils.getResId(name, "id"))
        listOf(
            "dm_action_profile",
            "text_action_mark_as_read",
            "text_action_mute",
            "text_action_thread_browser",
            "action_channel_notifications",
            "action_invite",
            "developer_divider",
        ).forEach { find<View>(it)?.visibility = View.GONE }

        StoreStream.getGuilds().getGuild(channel.i())?.let { guild ->
            find<ImageView>("channels_list_item_text_actions_icon")?.let { IconUtils.setIcon(it, guild) }
        }
        find<TextView>("channels_list_item_text_actions_title")?.text = ChannelUtils.d(channel, context, false)

        val link = "https://discord.com/channels/${channel.i()}/${channel.k()}"
        val copyId = find<TextView>("action_copy_id")
        copyId?.apply {
            visibility = View.VISIBLE
            setOnClickListener { copy(context, "Channel ID", channel.k().toString()) }
        }
        clone(context, root)?.let { row ->
            row.text = string(context, "copy_link", "Copy Link")
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(context, "ic_link_white_24dp"), null, null, null)
            row.setOnClickListener { copy(context, "Channel link", link) }
            val parent = copyId?.parent as? ViewGroup ?: return@let
            parent.addView(row, parent.indexOfChild(copyId))
        }
        find<TextView>("action_channel_settings")?.apply {
            visibility = if (canManage) View.VISIBLE else View.GONE
            text = string(context, "edit_channel", "Edit Channel")
            setOnClickListener {
                dismissAllowingStateLoss()
                // Kotlin resolves WidgetVoiceChannelSettings.Companion to the nested class, not the static field.
                (WidgetVoiceChannelSettings::class.java.getField("Companion").get(null) as WidgetVoiceChannelSettings.Companion)
                    .launch(channel.k(), context)
            }
        }
    }

    private fun copy(context: Context, label: String, value: String) {
        Utils.setClipboard(label, value)
        Utils.showToast(string(context, "copied_text", "Copied to clipboard"))
        dismissAllowingStateLoss()
    }

    /** A fresh native sheet row, so its text appearance, padding and touch feedback match Discord's. */
    private fun clone(context: Context, root: View): TextView? {
        val template = LayoutInflater
            .from(context)
            .inflate(Utils.getResId("widget_channels_list_item_actions", "layout"), root as? ViewGroup, false)
        val row = template.findViewById<TextView>(Utils.getResId("text_action_mark_as_read", "id")) ?: return null
        (row.parent as? ViewGroup)?.removeView(row)
        row.id = View.generateViewId()
        row.visibility = View.VISIBLE
        return row
    }

    private fun icon(context: Context, name: String) = Utils.getResId(name, "drawable").takeIf { it != 0 }?.let {
        DrawableCompat.getDrawable(
            context,
            it,
            ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr")),
        )
    }

    private fun string(context: Context, name: String, fallback: String) =
        Utils.getResId(name, "string").takeIf { it != 0 }?.let(context::getString) ?: fallback

    companion object {
        private const val ARG_CHANNEL = "channel_id"
        private const val ARG_MANAGE = "can_manage"
        private const val TAG = "VoiceChannelMenu"

        fun show(manager: FragmentManager, channelId: Long, canManage: Boolean) {
            if (manager.findFragmentByTag(TAG) != null) return
            VoiceChannelSheet()
                .apply {
                    arguments = Bundle().apply {
                        putLong(ARG_CHANNEL, channelId)
                        putBoolean(ARG_MANAGE, canManage)
                    }
                }.show(manager, TAG)
        }
    }
}
