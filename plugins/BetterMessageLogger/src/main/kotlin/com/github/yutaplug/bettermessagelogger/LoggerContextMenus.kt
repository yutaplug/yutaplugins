package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.api.PatcherAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.patcher.Hook
import com.aliucord.Utils
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelUtils
import com.discord.utilities.drawable.DrawableCompat
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions
import com.discord.widgets.guilds.contextmenu.GuildContextMenuViewModel
import com.discord.widgets.guilds.contextmenu.WidgetGuildContextMenu

/** Adds a "Message Logger" entry to server, channel and DM long-press menus for quick filtering. */
internal class LoggerContextMenus(
    private val settings: SettingsAPI,
    private val changed: () -> Unit,
    private val reportError: (String, Throwable) -> Unit,
) {
    fun patch(patcher: PatcherAPI) {
        patcher.patchRequired(
            WidgetChannelsListItemChannelActions::class.java,
            "configureUI",
            arrayOf(WidgetChannelsListItemChannelActions.Model::class.java),
            Hook { frame ->
                val model = frame.args[0] as? WidgetChannelsListItemChannelActions.Model ?: return@Hook
                safely { configureChannelSheet(frame.thisObject as WidgetChannelsListItemChannelActions, model.channel) }
            },
        )
        patcher.patchRequired(
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
        if (channel.D() !in SUPPORTED_CHANNEL_TYPES) {
            existing?.let(parent::removeView)
            return
        }
        val row = existing ?: clone(parent.context, "widget_channels_list_item_actions", "text_action_mark_as_read")?.also {
            val divider = parent.findViewById<View>(Utils.getResId("developer_divider", "id"))
            val index = divider?.let(parent::indexOfChild) ?: -1
            parent.addView(it, if (index >= 0) index else parent.childCount)
        } ?: return
        row.setOnClickListener {
            val context = sheet.requireActivity()
            sheet.dismiss()
            showChannel(context, channel)
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
            val activity = menu.requireActivity()
            // Kotlin resolves WidgetGuildContextMenu.Companion to the nested class, not Java's static field.
            (WidgetGuildContextMenu::class.java.getField("Companion").get(null) as WidgetGuildContextMenu.Companion)
                .hide(activity, true)
            LoggingFilterDialog.show(
                activity,
                guildName,
                listOf(
                    LoggingFilterDialog.Entry(
                        "This server",
                        "server",
                        guildId,
                        IdLists.BLOCKED_SERVERS,
                        IdLists.ALLOWED_SERVERS,
                    ),
                ),
                settings,
                changed,
            )
        }
    }

    private fun showChannel(context: Context, channel: Channel) {
        val title = ChannelUtils.d(channel, context, true)
        val entries = if (ChannelUtils.B(channel)) {
            listOf(
                LoggingFilterDialog.Entry(
                    "This DM",
                    "DM",
                    channel.k(),
                    IdLists.BLOCKED_DMS,
                    IdLists.ALLOWED_DMS,
                ),
            )
        } else {
            listOf(
                LoggingFilterDialog.Entry(
                    "This channel",
                    "channel",
                    channel.k(),
                    IdLists.BLOCKED_CHANNELS,
                    IdLists.ALLOWED_CHANNELS,
                ),
                LoggingFilterDialog.Entry(
                    "This server",
                    "server",
                    channel.i(),
                    IdLists.BLOCKED_SERVERS,
                    IdLists.ALLOWED_SERVERS,
                ),
            )
        }
        LoggingFilterDialog.show(context, title, entries, settings, changed)
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
        row.text = "Message Logger"
        row.visibility = View.VISIBLE
        val icon = Utils.getResId("ic_history_white_24dp", "drawable")
        if (icon != 0) {
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                DrawableCompat.getDrawable(context, icon, LoggerUi(context).icon),
                null,
                null,
                null,
            )
        }
        return row
    }

    private inline fun safely(work: () -> Unit) {
        try {
            work()
        } catch (error: Exception) {
            reportError("add logger menu entry", error)
        }
    }

    companion object {
        private const val TAG = "BetterMessageLogger.Filters"

        // Text, DM, group DM, announcement and forum channels. Categories and voice channels never log.
        private val SUPPORTED_CHANNEL_TYPES = setOf(0, 1, 3, 5, 15)
    }
}

internal object LoggingFilterDialog {
    class Entry(val heading: String, val noun: String, val id: Long, val blockKey: String, val allowKey: String)

    fun show(context: Context, title: String, entries: List<Entry>, settings: SettingsAPI, changed: () -> Unit) {
        val ui = LoggerUi(context)
        val content = ui.dialogContent(0)
        entries.forEach { entry ->
            if (entries.size > 1) ui.header(content, entry.heading)
            content.addView(
                ui.switch(
                    "Don't log",
                    "Never log messages from this ${entry.noun}.",
                    IdLists.contains(settings, entry.blockKey, entry.id),
                ) {
                    IdLists.set(settings, entry.blockKey, entry.id, it)
                    changed()
                },
            )
            content.addView(
                ui.switch(
                    "Add to allow list",
                    "When allow lists have entries, only those are logged.",
                    IdLists.contains(settings, entry.allowKey, entry.id),
                ) {
                    IdLists.set(settings, entry.allowKey, entry.id, it)
                    changed()
                },
            )
        }
        val dialog = ui.dialog(title, ui.scroll(content, 0.6f)).setPositiveButton("Done", null).create()
        ui.style(dialog)
        dialog.show()
    }
}
