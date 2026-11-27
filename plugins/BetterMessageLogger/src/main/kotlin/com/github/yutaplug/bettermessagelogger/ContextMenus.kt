package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions
import com.discord.widgets.guilds.contextmenu.GuildContextMenuViewModel
import com.discord.widgets.guilds.contextmenu.WidgetGuildContextMenu

/**
 * A "Message Logger" entry in the long-press menus of servers, channels and DMs, and in the menu behind the three
 * dots of a profile.
 */
internal class ContextMenus(private val reportError: (String, Throwable) -> Unit) {
    fun patch(patcher: PatcherAPI) {
        // The profile's three-dot menu: a small dialog (obfuscated as b.a.a.d.a) with Block, Unblock and Remove Friend.
        patcher.patch(
            b.a.a.d.a::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { frame ->
                val dialog = frame.thisObject as b.a.a.d.a
                val root = frame.args[0] as? ViewGroup ?: return@Hook
                guarded {
                    val userId = dialog.arguments?.getLong("com.discord.intent.extra.EXTRA_USER_ID") ?: return@guarded
                    if (userId == 0L || root.findViewWithTag<View>(TAG) != null) return@guarded
                    val row = NativeRows.take(root.context, "user_actions_dialog", listOf("user_actions_dialog_block_item"))
                        .firstOrNull() ?: return@guarded
                    NativeRows.dress(row, TAG, "Message Logger", "ic_history_white_24dp") {
                        val activity = dialog.requireActivity()
                        dialog.dismiss()
                        val name = StoreStream.getUsers().users[userId]?.username ?: "User $userId"
                        PlacePage.open(activity, name, null, null, false, userId)
                    }
                    root.addView(row)
                }
            },
        )
        patcher.patch(
            WidgetChannelsListItemChannelActions::class.java.getDeclaredMethod(
                "configureUI",
                WidgetChannelsListItemChannelActions.Model::class.java,
            ),
            Hook { frame ->
                val sheet = frame.thisObject as WidgetChannelsListItemChannelActions
                val channel = (frame.args[0] as? WidgetChannelsListItemChannelActions.Model)?.channel ?: return@Hook
                guarded {
                    val list = sheet.view
                        ?.findViewById<View>(Utils.getResId("text_action_mark_as_read", "id"))
                        ?.parent as? ViewGroup ?: return@guarded
                    list.findViewWithTag<View>(TAG)?.let(list::removeView)
                    // Text, DM, group DM, announcement and forum channels can have messages.
                    if (channel.D() !in setOf(0, 1, 3, 5, 15)) return@guarded
                    val row = NativeRows.take(list.context, "widget_channels_list_item_actions", listOf("text_action_mark_as_read"))
                        .firstOrNull() ?: return@guarded
                    NativeRows.dress(row, TAG, "Message Logger", "ic_history_white_24dp") {
                        val context = sheet.requireActivity()
                        sheet.dismiss()
                        val dm = ChannelUtils.B(channel) || channel.i() == 0L
                        PlacePage.open(context, ChannelUtils.d(channel, context, true), channel.k(), channel.i().takeIf { !dm && it != 0L }, dm)
                    }
                    // Above the developer section (Copy ID), like Discord's own channel entries.
                    val developer = list.findViewById<View>(Utils.getResId("developer_divider", "id"))
                    list.addView(row, developer?.let(list::indexOfChild)?.takeIf { it >= 0 } ?: list.childCount)
                }
            },
        )
        patcher.patch(
            WidgetGuildContextMenu::class.java.getDeclaredMethod(
                "configureValidUI",
                GuildContextMenuViewModel.ViewState.Valid::class.java,
            ),
            Hook { frame ->
                val menu = frame.thisObject as WidgetGuildContextMenu
                val guild = (frame.args[0] as GuildContextMenuViewModel.ViewState.Valid).guild
                guarded {
                    val more = menu.view?.findViewById<View>(Utils.getResId("guild_context_menu_more_options", "id"))
                        ?: return@guarded
                    val list = more.parent as? ViewGroup ?: return@guarded
                    if (list.findViewWithTag<View>(TAG) != null) return@guarded
                    val row = NativeRows.take(list.context, "widget_guild_context_menu", listOf("guild_context_menu_notifications"))
                        .firstOrNull() ?: return@guarded
                    list.addView(row, list.indexOfChild(more))
                    NativeRows.dress(row, TAG, "Message Logger", "ic_history_white_24dp") {
                        val activity = menu.requireActivity()
                        // The menu is a floating card; Kotlin's `Companion` would be the nested class, so read the field.
                        (WidgetGuildContextMenu::class.java.getField("Companion").get(null) as WidgetGuildContextMenu.Companion)
                            .hide(activity, true)
                        PlacePage.open(activity, guild.name, null, guild.id, false)
                    }
                }
            },
        )
    }

    private inline fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (error: Exception) {
            reportError("add the Message Logger menu entry", error)
        }
    }

    companion object {
        private const val TAG = "BetterMessageLogger:place"
    }
}

/** Logging and pre-download choices for one server, channel, DM or person, and a shortcut to what was logged. */
class PlacePage(
    private val title: String?,
    private val channelId: Long?,
    private val guildId: Long?,
    private val dm: Boolean,
    private val userId: Long? = null,
) : SettingsPage() {
    constructor() : this(null, null, null, false)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val ui = Ui(requireContext())
        linearLayout.setBackgroundColor(ui.pageColor)
        setActionBarTitle(title ?: "Message Logger")
        setActionBarSubtitle("Message Logger")
        if (title == null) {
            ui.note(linearLayout, "Open this page again from the long-press menu.")
            return
        }
        val settings = BetterMessageLogger.instance?.settings ?: return
        val log = BetterMessageLogger.instance?.log
        ui.action(linearLayout, "View logged messages", "ic_history_white_24dp") {
            val query = when {
                userId != null -> LogQuery(authorId = userId)
                channelId != null -> LogQuery(channelId = channelId)
                else -> LogQuery(guildId = guildId)
            }
            LogViewer.openPlace(requireContext(), query, title)
        }

        fun section(heading: String, id: Long, logging: Pair<IdList, IdList>, prefetch: Pair<IdList, IdList>) {
            ui.divider(linearLayout)
            ui.header(linearLayout, heading)
            fun toggle(list: IdList, name: String, detail: String) =
                ui.switch(linearLayout, name, detail, list.contains(settings, id)) {
                    list.set(settings, id, it)
                    log?.filtersChanged()
                }
            toggle(logging.first, "Don't log", "Nothing from here is logged.")
            toggle(logging.second, "Allow list", "When allow lists have entries, only they are logged.")
            toggle(prefetch.first, "Always pre-download media", "Used when pre-downloading is set to selected only.")
            toggle(prefetch.second, "Never pre-download media", "Wins over every other pre-download option.")
        }
        when {
            userId != null -> section(
                "This person",
                userId,
                IdList.IGNORED_USERS to IdList.ALLOWED_USERS,
                IdList.PREFETCH_USERS to IdList.SKIP_USERS,
            )

            dm && channelId != null -> section(
                "This conversation",
                channelId,
                IdList.BLOCKED_DMS to IdList.ALLOWED_DMS,
                IdList.PREFETCH_CHANNELS to IdList.SKIP_CHANNELS,
            )

            channelId != null -> {
                section(
                    "This channel",
                    channelId,
                    IdList.BLOCKED_CHANNELS to IdList.ALLOWED_CHANNELS,
                    IdList.PREFETCH_CHANNELS to IdList.SKIP_CHANNELS,
                )
                guildId?.let {
                    val server = StoreStream.getGuilds().getGuild(it)?.name
                    section(
                        if (server == null) "This server" else "Server: $server",
                        it,
                        IdList.BLOCKED_SERVERS to IdList.ALLOWED_SERVERS,
                        IdList.PREFETCH_SERVERS to IdList.SKIP_SERVERS,
                    )
                }
            }

            guildId != null -> section(
                "This server",
                guildId,
                IdList.BLOCKED_SERVERS to IdList.ALLOWED_SERVERS,
                IdList.PREFETCH_SERVERS to IdList.SKIP_SERVERS,
            )
        }
    }

    companion object {
        fun open(context: Context, title: String, channelId: Long?, guildId: Long?, dm: Boolean, userId: Long? = null) =
            Utils.openPageWithProxy(context, PlacePage(title, channelId, guildId, dm, userId))

    }
}
