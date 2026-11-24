package com.github.yutaplug.readall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.Utils
import com.discord.app.AppBottomSheet

/** Discord's channel actions sheet, reduced to the read all rows. */
class ReadAllSheet : AppBottomSheet() {
    override fun getContentViewResId() = Utils.getResId("widget_channels_list_item_actions", "layout")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        for (name in HIDDEN_ROWS) view.findViewById<View>(Utils.getResId(name, "id"))?.visibility = View.GONE

        view.findViewById<TextView>(Utils.getResId("channels_list_item_text_actions_title", "id"))?.text =
            view.context.getString(Utils.getResId("direct_messages", "string"))
        showDmIcon(view)

        val markAsRead = view.findViewById<TextView>(Utils.getResId("text_action_mark_as_read", "id")) ?: return
        bindRow(markAsRead, Scope.SERVERS)
        // Discord's mute and threads rows become the other two scopes, with the mark as read icon.
        val icon = markAsRead.compoundDrawablesRelative[0]
        for ((name, scope) in arrayOf("text_action_mute" to Scope.DMS, "text_action_thread_browser" to Scope.ALL)) {
            val row = view.findViewById<TextView>(Utils.getResId(name, "id")) ?: continue
            val copy = icon?.constantState?.newDrawable(row.resources)?.mutate()
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(copy, null, null, null)
            bindRow(row, scope)
        }
    }

    private fun bindRow(row: TextView, scope: Scope) {
        row.visibility = View.VISIBLE
        row.text = scope.label
        row.setOnClickListener {
            dismiss()
            ReadAll.instance?.markAsRead(scope)
        }
    }

    /** Replaces the channel avatar with the guild list's DM icon. */
    private fun showDmIcon(view: View) {
        val avatar = view.findViewById<View>(Utils.getResId("channels_list_item_text_actions_icon", "id")) ?: return
        val frame = avatar.parent as? ViewGroup ?: return
        val profile = LayoutInflater.from(view.context)
            .inflate(Utils.getResId("widget_guilds_list_item_profile", "layout"), frame, false) as? ViewGroup ?: return
        val dmIcon = profile.findViewById<View>(Utils.getResId("guilds_item_profile_avatar_wrap", "id")) ?: return
        profile.removeView(dmIcon)
        frame.removeAllViews()
        frame.addView(dmIcon)
    }

    companion object {
        const val TAG = "ReadAllSheet"

        private val HIDDEN_ROWS = arrayOf(
            "dm_action_profile",
            "action_channel_settings",
            "action_channel_notifications",
            "action_invite",
            "developer_divider",
            "action_copy_id",
        )
    }
}
