package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream
import com.lytefast.flexinput.R

/**
 * The logged-messages dashboard. Each screen is one of: the overview, a list of places or people, or the messages
 * themselves, always narrowed by a [LogQuery] that the screens before it built up.
 */
class LogViewer private constructor(
    private val screen: Screen?,
    private var query: LogQuery,
    private val heading: String,
) : SettingsPage() {
    constructor() : this(null, LogQuery(), "Dashboard")

    internal enum class Screen { HOME, SERVERS, CHANNELS, USERS, MESSAGES }

    private lateinit var ui: Ui
    private lateinit var body: LinearLayout
    private var alive = false
    private var shown = 0

    private val log get() = BetterMessageLogger.instance?.log

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        alive = true
        ui = Ui(requireContext())
        linearLayout.setBackgroundColor(ui.pageColor)
        setActionBarTitle(heading)
        if (screen == null) {
            ui.note(linearLayout, "Open the dashboard again from the plugin settings.")
            return
        }
        build()
    }

    override fun onDestroyView() {
        alive = false
        super.onDestroyView()
    }

    private fun build() {
        linearLayout.removeAllViews()
        setActionBarSubtitle(FILTERS[query.status])
        if (screen == Screen.HOME || screen == Screen.MESSAGES) {
            ui.search(linearLayout, "Search messages", query.search) { text ->
                if (screen == Screen.MESSAGES) {
                    query = query.copy(search = text)
                    fill()
                } else if (text != null) {
                    go(Screen.MESSAGES, query.copy(search = text), "Search: $text")
                }
            }
        }
        ui.value(linearLayout, "Show", FILTERS[query.status]) {
            SelectDialog().apply {
                title = "Show"
                items = FILTERS
                onResultListener = { choice ->
                    query = query.copy(status = choice)
                    build()
                }
            }.show(parentFragmentManager, "BetterMessageLoggerFilter")
        }
        body = ui.column(linearLayout)
        fill()
    }

    private fun fill() {
        body.removeAllViews()
        shown = 0
        val db = log?.database
        if (db == null || log?.databaseReady != true) {
            ui.note(body, "The log is still opening. Come back in a moment.")
            return
        }
        when (screen) {
            Screen.HOME -> home(db)
            Screen.MESSAGES -> messages(db)
            else -> groups(db)
        }
    }

    // region Overview and groups

    private fun home(db: LogDatabase) {
        ui.divider(body)
        ui.header(body, "Browse")
        val everything = ui.entry(body, "Everything", "Every logged message, newest first", "") {
            go(Screen.MESSAGES, query, "Everything")
        }
        val dms = ui.entry(body, "Direct messages", "Grouped by conversation", "") {
            go(Screen.CHANNELS, query.copy(dmsOnly = true), "Direct messages")
        }
        val people = ui.entry(body, "People", "Grouped by who wrote them", "") {
            go(Screen.USERS, query, "People")
        }
        db.countAsync(query) { onMain { everything.text = it.getOrNull()?.toString() ?: "" } }
        db.countAsync(query.copy(dmsOnly = true)) { onMain { dms.text = it.getOrNull()?.toString() ?: "" } }
        db.groupsAsync(query, LogDatabase.GROUP_AUTHOR) { onMain { people.text = it.getOrNull()?.size?.toString() ?: "" } }
        ui.divider(body)
        ui.header(body, "Servers")
        val servers = ui.column(body)
        db.groupsAsync(query, LogDatabase.GROUP_GUILD) { result -> onMain { listGroups(servers, result.getOrNull(), Screen.SERVERS) } }
    }

    private fun groups(db: LogDatabase) {
        if (screen == Screen.CHANNELS && query.guildId != null) {
            ui.entry(body, "Whole server", "Every channel together", "") { go(Screen.MESSAGES, query, heading) }
        }
        ui.divider(body)
        ui.header(
            body,
            when {
                screen == Screen.USERS -> "People"
                query.dmsOnly -> "Conversations"
                screen == Screen.CHANNELS -> "Channels"
                else -> "Servers"
            },
        )
        val list = ui.column(body)
        val by = when (screen) {
            Screen.USERS -> LogDatabase.GROUP_AUTHOR
            Screen.CHANNELS -> LogDatabase.GROUP_CHANNEL
            else -> LogDatabase.GROUP_GUILD
        }
        db.groupsAsync(query, by) { result -> onMain { listGroups(list, result.getOrNull(), screen!!) } }
    }

    private fun listGroups(parent: LinearLayout, groups: List<LogGroup>?, kind: Screen) {
        if (groups == null) {
            ui.note(parent, "The log couldn't be read.")
            return
        }
        if (groups.isEmpty()) ui.note(parent, "Nothing logged here.")
        for (group in groups) {
            val name = when (kind) {
                Screen.USERS -> StoreStream.getUsers().users[group.id]?.username ?: group.name ?: "User ${group.id}"
                Screen.CHANNELS -> StoreStream.getChannels().getChannel(group.id)?.let { ChannelUtils.d(it, ui.context, true) }
                    ?: group.name ?: "Channel ${group.id}"
                else -> StoreStream.getGuilds().getGuild(group.id)?.name ?: group.name ?: "Server ${group.id}"
            }
            val detail = listOfNotNull(
                group.deleted.takeIf { it > 0 }?.let { "$it deleted" },
                group.edited.takeIf { it > 0 }?.let { "$it edited" },
            ).joinToString(" · ")
            ui.entry(parent, name, detail, group.count.toString()) {
                when (kind) {
                    Screen.SERVERS -> go(Screen.CHANNELS, query.copy(guildId = group.id), name)
                    Screen.USERS -> go(Screen.MESSAGES, query.copy(authorId = group.id), name)
                    else -> go(Screen.MESSAGES, query.copy(channelId = group.id), name)
                }
            }
        }
    }

    // endregion

    // region Messages

    private fun messages(db: LogDatabase) {
        ui.divider(body)
        val list = ui.column(body)
        page(db, list)
    }

    private fun page(db: LogDatabase, list: LinearLayout) {
        db.queryAsync(query, shown, PAGE_SIZE) { result ->
            onMain {
                val entries = result.getOrNull()
                if (entries == null) {
                    ui.note(list, "The log couldn't be read.")
                    return@onMain
                }
                if (shown == 0 && entries.isEmpty()) {
                    ui.note(list, if (query.search != null) "No logged messages match." else "Nothing logged here.")
                }
                entries.forEach { list.addView(card(it), ui.matchWidth()) }
                shown += entries.size
                if (entries.size == PAGE_SIZE) {
                    lateinit var more: View
                    more = ui.action(list, "Show more", "ic_history_white_24dp") {
                        list.removeView(more)
                        page(db, list)
                    }
                }
            }
        }
    }

    /** One message: who and where, the text, and what happened to it. */
    private fun card(entry: LogEntry): View {
        val record = entry.record
        val context = ui.context
        val card = ui.column()
        card.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
        ui.clickable(card) { options(entry) }
        val who = SpannableStringBuilder(StoreStream.getUsers().users[record.authorId]?.username ?: record.authorName)
        who.setSpan(StyleSpan(Typeface.BOLD), 0, who.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        who.setSpan(ForegroundColorSpan(ui.headerColor), 0, who.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (query.channelId == null) who.append("  ").append(log?.describePlace(record.channelId, record.guildId).orEmpty())
        card.addView(
            ui.text(who, R.i.UiKit_TextView_Subtext_SingleLine).apply { ellipsize = TextUtils.TruncateAt.END },
            ui.matchWidth(),
        )
        val text = when {
            record.content.isNotEmpty() -> record.content
            entry.hasMedia -> "Media only"
            else -> "No text"
        }
        card.addView(
            ui.text(text, R.i.UiKit_TextView, if (record.content.isEmpty()) ui.mutedColor else ui.textColor).apply {
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ui.dp(2), 0, ui.dp(2))
            },
            ui.matchWidth(),
        )
        val status = SpannableStringBuilder("Sent ").append(Dates.format(context, record.timestamp))
        if (record.deleted) {
            val start = status.length
            status.append(" · deleted")
            record.deletedTimestamp?.let { status.append(" ").append(Dates.format(context, it)) }
            val color = BetterMessageLogger.instance?.settings?.let(Prefs::labelColor) ?: ui.dangerColor
            status.setSpan(ForegroundColorSpan(color), start, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (entry.editCount > 0) status.append(" · edited ${entry.editCount}×")
        card.addView(ui.text(status, R.i.UiKit_TextView_Subtext), ui.matchWidth())
        return card
    }

    private fun options(entry: LogEntry) {
        val record = entry.record
        val choices = ArrayList<Pair<String, () -> Unit>>()
        choices.add(
            "Go to message" to {
                StoreStream.getMessagesLoader().jumpToMessage(record.channelId, record.id)
                // Discord's own route back to chat, as its pinned messages screen uses.
                b.a.d.j.b(requireActivity(), true, null)
            },
        )
        if (entry.editCount > 0) choices.add("Edit history" to { HistoryPage.open(requireContext(), record.id) })
        if (record.content.isNotEmpty()) {
            choices.add(
                "Copy text" to {
                    Utils.setClipboard("Message", record.content)
                    Utils.showToast("Copied")
                },
            )
        }
        choices.add(
            "Remove from log" to {
                val confirm = ConfirmDialog()
                confirm.setTitle("Remove from the log?")
                    .setDescription("The message stays on Discord; only the logger forgets it.")
                    .setIsDangerous(true)
                    .setOnOkListener {
                        confirm.dismiss()
                        log?.delete(record.id)
                        // Queued after the removal on the database thread.
                        fill()
                    }.show(parentFragmentManager, "BetterMessageLoggerRemove")
            },
        )
        SelectDialog().apply {
            title = record.authorName
            items = choices.map { it.first }.toTypedArray()
            onResultListener = { choices[it].second() }
        }.show(parentFragmentManager, "BetterMessageLoggerMessage")
    }

    // endregion

    private fun go(next: Screen, nextQuery: LogQuery, title: String) =
        Utils.openPageWithProxy(requireContext(), LogViewer(next, nextQuery, title))

    private fun onMain(work: () -> Unit) {
        Utils.mainThread.post {
            if (!alive) return@post
            try {
                work()
            } catch (error: Exception) {
                BetterMessageLogger.instance?.logger?.error("Could not show logged messages", error)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
        private val FILTERS = arrayOf("Deleted and edited", "Deleted only", "Edited only")

        internal fun openHome(context: Context) =
            Utils.openPageWithProxy(context, LogViewer(Screen.HOME, LogQuery(), "Dashboard"))

        /** What was logged in one server (by channel) or one channel. */
        internal fun openPlace(context: Context, query: LogQuery, title: String) = Utils.openPageWithProxy(
            context,
            LogViewer(if (query.channelId == null && query.authorId == null) Screen.CHANNELS else Screen.MESSAGES, query, title),
        )
    }
}
