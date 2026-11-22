package com.github.yutaplug.typingnotifications

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SelectDialog
import com.aliucord.patcher.Hook
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelUtils
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.DrawableCompat
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions
import com.discord.widgets.guilds.contextmenu.GuildContextMenuViewModel
import com.discord.widgets.guilds.contextmenu.WidgetGuildContextMenu

/** Adds a "Typing Notifications" entry to server, channel and DM long-press menus. */
internal class ContextMenus(
    private val settings: SettingsAPI,
    private val reportError: (String, Throwable) -> Unit,
) {
    fun patch(patcher: PatcherAPI) {
        patcher.patch(
            WidgetChannelsListItemChannelActions::class.java,
            "configureUI",
            arrayOf(WidgetChannelsListItemChannelActions.Model::class.java),
            Hook { frame ->
                val model = frame.args[0] as? WidgetChannelsListItemChannelActions.Model ?: return@Hook
                safely { configureChannelSheet(frame.thisObject as WidgetChannelsListItemChannelActions, model.channel) }
            },
        )
        patcher.patch(
            WidgetGuildContextMenu::class.java,
            "configureValidUI",
            arrayOf(GuildContextMenuViewModel.ViewState.Valid::class.java),
            Hook { frame ->
                val state = frame.args[0] as GuildContextMenuViewModel.ViewState.Valid
                safely { configureGuildMenu(frame.thisObject as WidgetGuildContextMenu, state.guild.id, state.guild.name) }
            },
        )
    }

    private fun configureChannelSheet(sheet: WidgetChannelsListItemChannelActions, channel: Channel) {
        val root = sheet.view ?: return
        val anchor = root.findViewById<View>(Utils.getResId("text_action_mark_as_read", "id")) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        // Discord reconfigures the same sheet when the channel model changes.
        val existing = parent.findViewWithTag<TextView>(TAG)
        if (channel.D() !in TYPING_CHANNEL_TYPES) {
            existing?.let(parent::removeView)
            return
        }
        val row = existing ?: clone(parent.context, "widget_channels_list_item_actions", "text_action_mark_as_read")?.also {
            val divider = parent.findViewById<View>(Utils.getResId("developer_divider", "id"))
            val index = divider?.let(parent::indexOfChild) ?: -1
            parent.addView(it, if (index >= 0) index else parent.childCount)
        } ?: return
        row.setOnClickListener {
            safely("open the typing notifications menu") {
                val activity = sheet.requireActivity()
                val title = ChannelUtils.d(channel, activity, true)
                val kind = if (ChannelUtils.B(channel)) Filters.DM else Filters.CHANNEL
                // Show the dialog before closing the sheet, so the sheet's removal can't take it along.
                choose(activity, title, kind, channel.k())
                sheet.dismiss()
            }
        }
    }

    private fun configureGuildMenu(menu: WidgetGuildContextMenu, guildId: Long, guildName: String) {
        val root = menu.view ?: return
        val anchor = root.findViewById<View>(Utils.getResId("guild_context_menu_more_options", "id")) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        val row = parent.findViewWithTag<TextView>(TAG)
            ?: clone(parent.context, "widget_guild_context_menu", "guild_context_menu_notifications")?.also {
                parent.addView(it, parent.indexOfChild(anchor))
            } ?: return
        row.setOnClickListener {
            safely("open the typing notifications menu") {
                val activity = menu.requireActivity()
                choose(activity, guildName, Filters.SERVER, guildId)
                // Kotlin resolves WidgetGuildContextMenu.Companion to the nested class, not Java's static field.
                (WidgetGuildContextMenu::class.java.getField("Companion").get(null) as WidgetGuildContextMenu.Companion)
                    .hide(activity, true)
            }
        }
    }

    /** Lets the user put a place on the blacklist, the whitelist, or neither. */
    private fun choose(activity: FragmentActivity, title: String, kind: Filters.Kind, id: Long) {
        val current = when {
            Filters.contains(settings, kind.blockKey, id) -> 1
            Filters.contains(settings, kind.allowKey, id) -> 2
            else -> 0
        }
        val labels = arrayOf("Default", "Blacklist this ${kind.noun}", "Whitelist this ${kind.noun}")
        SelectDialog().apply {
            this.title = "Typing notifications · $title"
            items = labels.mapIndexed { index, label -> if (index == current) "$label ✓" else label }.toTypedArray()
            onResultListener = { which ->
                Filters.set(settings, kind.blockKey, id, which == 1)
                Filters.set(settings, kind.allowKey, id, which == 2)
                Utils.showToast(
                    when (which) {
                        1 -> "Typing notifications blacklisted for this ${kind.noun}"
                        2 -> "Typing notifications whitelisted for this ${kind.noun}"
                        else -> "Typing notifications reset for this ${kind.noun}"
                    },
                )
            }
        }.show(activity.supportFragmentManager, "TypingNotificationsFilter")
    }

    /** Inflates a real row from Discord's layout so text appearance, padding and touch feedback match. */
    private fun clone(context: Context, layout: String, id: String): TextView? {
        val layoutId = Utils.getResId(layout, "layout")
        val rowId = Utils.getResId(id, "id")
        if (layoutId == 0 || rowId == 0) return null
        val template = LayoutInflater.from(context).inflate(layoutId, null)
        val row = template.findViewById<TextView>(rowId) ?: return null
        (row.parent as? ViewGroup)?.removeView(row)
        row.id = View.generateViewId()
        row.tag = TAG
        row.text = "Typing Notifications"
        row.visibility = View.VISIBLE
        val icon = Utils.getResId("ic_notifications_white_24dp", "drawable")
        val tint = Utils.getResId("colorInteractiveNormal", "attr")
        if (icon != 0 && tint != 0) {
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                DrawableCompat.getDrawable(context, icon, ColorCompat.getThemedColor(context, tint)),
                null,
                null,
                null,
            )
        }
        return row
    }

    private inline fun safely(action: String = "add typing notifications menu entry", work: () -> Unit) {
        try {
            work()
        } catch (error: Throwable) {
            reportError(action, error)
            Utils.showToast("Typing Notifications: couldn't $action (see Aliucord debug logs)")
        }
    }

    private companion object {
        const val TAG = "TypingNotifications.Filters"

        // Text, DM, group DM, announcement, threads and forum channels.
        val TYPING_CHANNEL_TYPES = setOf(0, 1, 3, 5, 10, 11, 12, 15)
    }
}
