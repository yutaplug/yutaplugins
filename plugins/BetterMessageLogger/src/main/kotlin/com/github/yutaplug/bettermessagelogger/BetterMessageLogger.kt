package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.util.LruCache
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.SettingsAPI
import com.aliucord.Constants
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.Utils
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreStream
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.model.WidgetChatListModelMessages.MessagesWithMetadata
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import rx.functions.Func2
import rx.Observable
import rx.subjects.BehaviorSubject
import com.discord.api.message.Message as ApiMessage

@AliucordPlugin
class BetterMessageLogger : Plugin() {
    private val lock = Any()
    private val state = MessageLogState()
    private val liveMessages = LruCache<Long, Message>(MessageLogState.MAX_TRANSIENT)
    private val boundMessages = LruCache<Long, Message>(MessageLogState.MAX_TRANSIENT)
    private val revision = BehaviorSubject.l0(0L)
    private val revisionNumber = AtomicLong()

    @Volatile private var database: MessageLoggerDatabase? = null

    @Volatile private var media: MessageMediaStore? = null
    private lateinit var decorations: MessageDecorations

    @Volatile private var running = false

    @Volatile private var filters = Filters(settings)

    @Volatile private var databaseEnabled = false

    @Volatile private var databaseReady = false

    @Volatile private var databaseErrorShown = false

    override fun start(context: Context) {
        running = true
        instance = this
        filters = Filters(settings)
        settingsTab =
            SettingsTab(BetterMessageLoggerSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
        try {
            media = MessageMediaStore(
                File(Constants.BASE_PATH, MEDIA_DIR),
                { action, error -> logger.error("Could not $action", error) },
                ::bumpRevision,
            )
            decorations =
                MessageDecorations(settings, ::record, { message ->
                    boundMessages.put(message.id, message)
                    safely("cache visible message") { remember(message) }
                }) {
                    action,
                    error,
                    ->
                    logger.error(action, error)
                }
            decorations.patch(patcher)
            patchMessages()
            patchDisplayModel()
            patchActions()
            patchMediaPreviews()
            LoggerContextMenus(settings, ::settingsChanged) { action, error ->
                logger.error("Could not $action", error)
            }.patch(patcher)
            databaseEnabled = settings.getBool("database", false)
            if (databaseEnabled ||
                File(Constants.BASE_PATH, DB_NAME).exists() ||
                File(Constants.BASE_PATH, "BetterMessageLogger").isDirectory
            ) {
                ensureDatabase()
            }
        } catch (error: Throwable) {
            stop(context)
            throw error
        }
    }

    private fun patchMessages() {
        patcher.patchRequired(
            StoreMessages::class.java,
            "handleMessageCreate",
            arrayOf(List::class.java),
            Hook { frame ->
                (frame.args[0] as? List<*>)?.filterIsInstance<ApiMessage>()?.forEach { api ->
                    safely("read created message") {
                        val message = Message(api)
                        remember(message)
                        prefetch(message)
                    }
                }
            },
        )
        // Capture the previous complete model before Discord merges a partial MESSAGE_UPDATE.
        patcher.patchRequired(
            StoreMessages::class.java,
            "handleMessageUpdate",
            arrayOf(ApiMessage::class.java),
            PreHook { frame ->
                safely("log message update") {
                    update(frame.thisObject as StoreMessages, frame.args[0] as ApiMessage)
                }
            },
        )
        patcher.patchRequired(
            StoreMessages::class.java,
            "handleMessageDelete",
            arrayOf(ModelMessageDelete::class.java),
            PreHook { frame ->
                if (!isHideMessagesCall()) {
                    safely("capture deleted message") {
                        val event = frame.args[0] as ModelMessageDelete
                        val store = frame.thisObject as StoreMessages
                        synchronized(lock) {
                            event.messageIds.orEmpty().forEach { id ->
                                val message = runCatching { store.getMessage(event.channelId, id) }.getOrNull()
                                    ?: liveMessages.get(id) ?: boundMessages.get(id)
                                if (message != null) remember(message)
                                val existing = state.records[id] ?: return@forEach
                                if (existing.channelId != event.channelId || !shouldKeep(existing)) return@forEach
                                val record = existing.copy(
                                    deleted = true,
                                    deletedTimestamp =
                                        existing.deletedTimestamp ?: System.currentTimeMillis(),
                                )
                                state.put(record)
                                state.recentDeletes.add(id)
                                persist(record)
                            }
                        }
                        decorations.refresh()
                        bumpRevision()
                    }
                }
            },
        )
        patcher.patchRequired(
            StoreMessages::class.java,
            "handleMessagesLoaded",
            arrayOf(StoreMessagesLoader.ChannelChunk::class.java),
            PreHook { frame ->
                val chunk = frame.args[0] as StoreMessagesLoader.ChannelChunk
                if (chunk.isInitial || chunk.isJump) synchronized(lock) { state.resetRange(chunk.channelId) }
            },
        )
        patcher.patchRequired(
            StoreMessages::class.java,
            "handleMessagesLoaded",
            arrayOf(StoreMessagesLoader.ChannelChunk::class.java),
            Hook { frame ->
                safely("cache loaded messages") {
                    (frame.args[0] as StoreMessagesLoader.ChannelChunk).messages.orEmpty().forEach { message ->
                        remember(message)
                        // Older history is rarely deleted; avoid downloading media while scrolling back.
                        if (System.currentTimeMillis() - snowflakeTime(message.id) < PREFETCH_LOADED_AGE) {
                            prefetch(message)
                        }
                    }
                    bumpRevision()
                }
            },
        )
    }

    private fun patchDisplayModel() {
        // Two-argument lookup supplies action sheets with deleted messages. The live channel
        // observable must stay untouched, since Discord uses it for pagination and jumps.
        patcher.patchRequired(
            StoreMessages::class.java,
            "observeMessagesForChannel",
            arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
            Hook { frame ->
                @Suppress("UNCHECKED_CAST")
                val source = frame.result as? Observable<Message?> ?: return@Hook
                val channelId = frame.args[0] as Long
                val id = frame.args[1] as Long
                frame.result = Observable.j(
                    source,
                    revision,
                    Func2<Message?, Long, Message?> { current, _ ->
                        current ?: record(id)?.takeIf { it.channelId == channelId && it.deleted }?.let(::display)
                    },
                )
            },
        )
        patcher.patchRequired(
            MessagesWithMetadata.Companion::class.java,
            "get",
            arrayOf(Channel::class.java),
            Hook { frame ->
                val channelId = (frame.args[0] as Channel).k()

                @Suppress("UNCHECKED_CAST")
                val source = frame.result as? Observable<MessagesWithMetadata> ?: return@Hook
                frame.result =
                    Observable.j(
                        source,
                        revision,
                        Func2<MessagesWithMetadata, Long, MessagesWithMetadata> { current, _ ->
                            val merged = withDeletedMessages(channelId, current.messages)
                            current.copy(
                                merged,
                                current.messageState,
                                current.messageThreads,
                                current.threadCountsAndLatestMessages,
                                current.messageReplyState,
                                current.parentChannelMessageReplyState,
                            )
                        },
                    )
            },
        )
    }

    private fun patchActions() {
        patcher.patchRequired(
            WidgetChatListActions::class.java,
            "configureUI",
            arrayOf(WidgetChatListActions.Model::class.java),
            Hook { frame ->
                val sheet = frame.thisObject as WidgetChatListActions
                val message = (frame.args[0] as WidgetChatListActions.Model).message
                // Sheets are reused by Discord; remove actions belonging to the previous message.
                decorations.clearActions(sheet)
                val saved = message?.let { record(it.id) } ?: return@Hook
                if (saved.edits.isNotEmpty()) {
                    decorations.addAction(sheet, "History", "View Edit History", "ic_history_white_24dp") {
                        record(saved.id)?.let { EditHistoryDialog.show(sheet.requireContext(), it) }
                    }
                }
                if (saved.logged) {
                    decorations.addAction(sheet, "Delete", "Delete Logged Message", "ic_delete_24dp") {
                        removeRecord(saved.id)
                        decorations.refresh(removeHistory = true)
                        bumpRevision()
                        sheet.dismiss()
                        Utils.showToast("Logged message deleted")
                    }
                }
            },
        )
    }

    private fun patchMediaPreviews() {
        patcher.patchRequired(
            EmbedResourceUtils::class.java,
            "getPreviewUrls",
            arrayOf(
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ),
            Hook { frame ->
                media?.previewUrls(frame.args[0] as String)?.let { frame.result = it }
            },
        )
    }

    private fun record(id: Long): MessageRecord? = synchronized(lock) {
        state.records[id]?.takeIf(::shouldKeep)
    }

    private fun remember(message: Message) = synchronized(lock) {
        if (message.isLocal || message.id <= 0) return@synchronized
        val incoming = MessageRecord.fromMessage(message, resolveGuildId(message.channelId, message.guildId))
        if (!shouldKeep(incoming)) {
            removeRecord(message.id)
            return@synchronized
        }
        liveMessages.put(message.id, message)
        val previous = state.records[message.id]
        val record = incoming.copy(
            deleted = previous?.deleted == true,
            deletedTimestamp = previous?.deletedTimestamp,
            edits = if (editLoggingEnabled()) previous?.edits.orEmpty() else emptyList(),
            payload = previous?.payload,
        )
        state.put(record)
        if (record.logged && previous?.message != message) persist(record)
    }

    private fun update(store: StoreMessages, api: ApiMessage) = synchronized(lock) {
        val id = api.o()
        val old = runCatching { store.getMessage(api.g(), id) }.getOrNull()
            ?: liveMessages.get(id) ?: boundMessages.get(id)
        val cached = state.records[id]
        val merged = old?.merge(api) ?: cached?.toMessage()?.merge(api) ?: Message(api)
        val base = cached ?: old?.let { MessageRecord.fromMessage(it, resolveGuildId(it.channelId, it.guildId)) }
        val incoming = MessageRecord.fromMessage(merged, resolveGuildId(merged.channelId, merged.guildId))
        if (!shouldKeep(incoming)) {
            removeRecord(id)
            return@synchronized
        }
        val time = api.j()?.g()
        var edits = if (editLoggingEnabled()) base?.edits.orEmpty() else emptyList()
        // Ignore non-edit updates (including NitroSpoof) and duplicate gateway deliveries.
        if (base != null && api.i() != null && incoming.content != base.content && time != null && time > 0) {
            if (editLoggingEnabled()) edits = edits + MessageEdit(time, base.content)
        }
        val record = incoming.copy(
            deleted = base?.deleted == true,
            deletedTimestamp = base?.deletedTimestamp,
            edits = edits,
        )
        state.put(record)
        liveMessages.put(id, merged)
        persist(record)
        if (record.logged) bumpRevision()
    }

    private fun withDeletedMessages(channelId: Long, current: List<Message>): List<Message> = synchronized(lock) {
        val liveIds = current.filter { !it.isLocal }.map { it.id }
        var range = state.ranges[channelId]
        if (liveIds.isNotEmpty()) {
            val oldest = liveIds.minOrNull()!!
            val newest = liveIds.maxOrNull()!!
            range = minOf(range?.first ?: oldest, oldest)..maxOf(range?.last ?: newest, newest)
            state.ranges[channelId] = range
            while (state.ranges.size > MessageLogState.MAX_CHANNELS) {
                val evicted = state.ranges.keys.first()
                state.ranges.remove(evicted)
                state.requestedRanges.remove(evicted)
            }
            // Query the current live window even when earlier batches have already been visited.
            requestRange(channelId, oldest..newest)
        }
        val present = current.mapTo(HashSet()) { it.id }
        val deleted = state.records.values.filter {
            it.channelId == channelId &&
                it.deleted &&
                it.id !in present &&
                shouldKeep(it) &&
                (it.id in state.recentDeletes || range?.contains(it.id) == true)
        }
        if (deleted.isEmpty()) return@synchronized current
        (current + deleted.map(::display)).sortedBy { it.id }
    }

    private fun display(record: MessageRecord): Message {
        val store = media
        if (store == null || !store.hasMedia(record.id)) return record.toMessage()
        // Localize a private copy; the live message may still be shared with Discord's stores.
        return store.localize(if (record.message == null) record.toMessage() else record.toDetachedMessage())
    }

    private fun requestRange(channelId: Long, range: LongRange) {
        val db = database?.takeIf { databaseEnabled && databaseReady } ?: return
        if (state.requestedRanges[channelId] == range) return
        state.requestedRanges[channelId] = range
        val generation = state.generation
        db.loadRangeAsync(channelId, range) { result ->
            if (!running) return@loadRangeAsync
            var changed = false
            synchronized(lock) {
                if (!databaseEnabled) return@synchronized
                val requested = state.requestedRanges[channelId]
                if (state.generation != generation || requested != range) {
                    // Invalidated with no newer request for this channel; render again so it is re-queried.
                    if (requested == null) changed = true
                    return@synchronized
                }
                result.onSuccess { loaded ->
                    loaded.forEach { saved ->
                        if (!shouldKeep(saved)) {
                            db.removeAsync(saved.id)
                            media?.removeAsync(saved.id)
                            return@forEach
                        }
                        val current = state.records[saved.id]
                        val combined = if (current == null) {
                            saved
                        } else {
                            current.copy(
                                deleted = current.deleted || saved.deleted,
                                deletedTimestamp = current.deletedTimestamp ?: saved.deletedTimestamp,
                                edits =
                                    if (editLoggingEnabled()) (saved.edits + current.edits).distinct() else emptyList(),
                                payload = current.payload ?: saved.payload,
                            )
                        }
                        state.put(combined)
                        // Retries media of messages saved before their attachments could be stored.
                        media?.saveAsync(combined, true)
                        changed = true
                    }
                }
            }
            if (changed) {
                decorations.refresh(removeHistory = true)
                bumpRevision()
            }
        }
    }

    private fun shouldKeep(record: MessageRecord) =
        filters.keep(record, resolveGuildId(record.channelId, record.guildId))

    private fun resolveGuildId(channelId: Long, guildId: Long?): Long? {
        if (guildId != null && guildId != 0L) return guildId
        return try {
            StoreStream.getChannels().getChannel(channelId)?.i()?.takeIf { it != 0L } ?: guildId
        } catch (_: Exception) {
            guildId
        }
    }

    private fun removeRecord(id: Long) = synchronized(lock) {
        state.remove(id)
        liveMessages.remove(id)
        boundMessages.remove(id)
        database?.removeAsync(id)
        media?.removeAsync(id)
    }

    private fun prefetch(message: Message) {
        if (!settings.getBool(PREFETCH_MEDIA, true)) return
        // Records exist only for real messages that pass the logging filters.
        if (record(message.id) != null) media?.prefetchAsync(message)
    }

    private fun persist(record: MessageRecord) {
        if (!record.logged) return
        if (databaseEnabled) database?.saveAsync(record)
        // Without the database, media is kept for this session only.
        media?.saveAsync(record, databaseEnabled)
    }

    /** Runs after queued database pruning, removing media of messages that are no longer saved. */
    private fun sweepMedia(db: MessageLoggerDatabase) {
        db.messageIdsAsync { result -> result.onSuccess { ids -> if (running) media?.retainAsync(ids) } }
    }

    private fun ensureDatabase(): MessageLoggerDatabase {
        database?.let { return it }
        val db = MessageLoggerDatabase(
            File(Constants.BASE_PATH, DB_NAME),
            File(Constants.BASE_PATH, "BetterMessageLogger/$DB_NAME"),
        ) { action, error ->
            logger.error("Could not $action", error)
            if (!databaseErrorShown && running) {
                databaseErrorShown = true
                Utils.mainThread.post { if (running) Utils.showToast("BetterMessageLogger: could not $action", true) }
            }
        }
        database = db
        db.openAsync { result ->
            if (!running || database !== db) return@openAsync
            result
                .onSuccess {
                    synchronized(lock) {
                        databaseReady = true
                        databaseErrorShown = false
                        val currentFilters = filters
                        db.pruneAsync { currentFilters.keep(it, resolveGuildId(it.channelId, it.guildId)) }
                        if (!editLoggingEnabled()) db.clearEditsAsync()
                        sweepMedia(db)
                        state.invalidateLoads()
                        if (databaseEnabled) state.records.values.forEach(::persist)
                    }
                    bumpRevision()
                }.onFailure {
                    synchronized(lock) {
                        databaseReady = false
                        databaseEnabled = false
                        settings.setBool("database", false)
                        if (database === db) database = null
                        db.stop()
                    }
                }
        }
        return db
    }

    internal fun setDatabaseEnabled(enabled: Boolean) {
        databaseEnabled = enabled
        settings.setBool("database", enabled)
        synchronized(lock) {
            state.invalidateLoads()
            if (enabled) {
                val db = ensureDatabase()
                if (databaseReady) {
                    val currentFilters = filters
                    db.pruneAsync { currentFilters.keep(it, resolveGuildId(it.channelId, it.guildId)) }
                    if (!editLoggingEnabled()) db.clearEditsAsync()
                    sweepMedia(db)
                    state.records.values.forEach(::persist)
                }
            }
        }
        bumpRevision()
    }

    internal fun setEditLoggingEnabled(enabled: Boolean) {
        settings.setBool(LOG_EDIT_HISTORY, enabled)
        if (!enabled) {
            synchronized(lock) {
                state.invalidateLoads()
                state.records.values.toList().forEach { state.put(it.copy(edits = emptyList())) }
                database?.let {
                    it.clearEditsAsync()
                    sweepMedia(it)
                }
            }
        }
        decorations.refresh(removeHistory = true)
        bumpRevision()
    }

    internal fun settingsChanged() {
        filters = Filters(settings)
        synchronized(lock) {
            state.invalidateLoads()
            state.records.values.toList().filterNot(::shouldKeep).forEach { removeRecord(it.id) }
            val currentFilters = filters
            database?.let { db ->
                db.pruneAsync { currentFilters.keep(it, resolveGuildId(it.channelId, it.guildId)) }
                sweepMedia(db)
            }
        }
        decorations.refresh(removeHistory = true)
        bumpRevision()
    }

    internal fun refreshAppearance() {
        decorations.refresh(removeHistory = true)
        bumpRevision()
    }

    internal fun clearDatabase(done: (() -> Unit)? = null) {
        synchronized(lock) {
            state.clear()
            liveMessages.evictAll()
            boundMessages.evictAll()
            media?.clearAsync()
            ensureDatabase().clearAsync { result ->
                Utils.mainThread.post {
                    if (running) {
                        Utils.showToast(
                            if (result.isSuccess) "Saved logs cleared" else "Could not clear saved logs",
                            true,
                        )
                        done?.invoke()
                    }
                }
            }
        }
        decorations.refresh(removeHistory = true)
        bumpRevision()
    }

    internal fun exportDatabaseToText() {
        if (!databaseEnabled || !databaseReady) {
            Utils.showToast("Enable the database and wait for it to open first")
            return
        }
        val db = database ?: return
        val output = File(Constants.BASE_PATH, TXT_NAME)
        Utils.showToast("Exporting DB to TXT…")
        val done: (Result<File>) -> Unit = { result ->
            Utils.mainThread.post {
                if (running) {
                    Utils.showToast(
                        if (result.isSuccess) {
                            "Exported to Aliucord/${output.name}"
                        } else {
                            "Export failed: ${result.exceptionOrNull()?.message}"
                        },
                        true,
                    )
                }
            }
        }
        db.exportTextAsync(output, done)
    }

    internal fun storageStatistics(done: (MessageLoggerDatabase.Statistics?) -> Unit) {
        val db = database
        if (db == null) {
            done(null)
        } else {
            db.statisticsAsync { result -> Utils.mainThread.post { if (running) done(result.getOrNull()) } }
        }
    }

    private fun editLoggingEnabled() = settings.getBool(LOG_EDIT_HISTORY, true)

    private fun bumpRevision() {
        // Rx subjects are not thread-safe; every emission goes through the main looper.
        Utils.mainThread.post { if (running) revision.onNext(revisionNumber.incrementAndGet()) }
    }

    private inline fun safely(action: String, work: () -> Unit) {
        try {
            work()
        } catch (error: Exception) {
            logger.error("Could not $action", error)
        }
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        if (::decorations.isInitialized) decorations.close()
        synchronized(lock) {
            state.clear()
            liveMessages.evictAll()
            boundMessages.evictAll()
            database?.stop()
            database = null
            databaseReady = false
            media?.stop()
            media = null
        }
        if (instance === this) instance = null
    }

    private class Filters(settings: SettingsAPI) {
        private val ignoreOwn = settings.getBool("ignoreOwn", false)
        private val ignoreBots = settings.getBool("ignoreBots", false)
        private val ids = listOf(
            "ignoredUsers",
            "blackDms",
            "whiteDms",
            "blackChannels",
            "whiteChannels",
            "blackServers",
            "whiteServers",
        ).associateWith { key ->
            settings.getString(key, "").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        }

        fun keep(record: MessageRecord, guildId: Long?): Boolean {
            if ((ignoreBots && record.bot) || record.authorId in ids.getValue("ignoredUsers")) return false
            if (ignoreOwn && record.authorId == runCatching { StoreStream.getUsers().me.id }.getOrNull()) return false

            fun allowed(id: Long, black: String, white: String) = id !in ids.getValue(black) &&
                (ids.getValue(white).isEmpty() || id in ids.getValue(white))
            return if (guildId == null || guildId == 0L) {
                allowed(record.channelId, "blackDms", "whiteDms")
            } else {
                allowed(record.channelId, "blackChannels", "whiteChannels") &&
                    allowed(guildId, "blackServers", "whiteServers")
            }
        }
    }

    companion object {
        internal const val DB_NAME = "BetterMessageLogger.db"
        internal const val TXT_NAME = "BetterMessageLogger.txt"
        internal const val MEDIA_DIR = "BetterMessageLoggerMedia"
        internal const val PREFETCH_MEDIA = "prefetchMedia"
        private const val PREFETCH_LOADED_AGE = 24L * 60 * 60 * 1000

        private fun snowflakeTime(id: Long) = (id shr 22) + 1420070400000L
        internal const val DELETED_LABEL_COLOR = "deletedLabelColor"
        internal const val DELETED_MESSAGE_COLOR = "deletedMessageColor"
        internal const val LOG_EDIT_HISTORY = "logEditHistory"
        internal const val INLINE_EDIT_HISTORY = "inlineEditHistory"
        internal const val SHOW_DELETED_TAG = "showDeletedTag"
        internal const val DEFAULT_DELETED_LABEL_COLOR = "#FFFF0000"
        internal const val DEFAULT_DELETED_MESSAGE_COLOR = "#FFFFFFFF"

        @Volatile internal var instance: BetterMessageLogger? = null
            private set

        private fun isHideMessagesCall() = Thread.currentThread().stackTrace.any { "HideMessages" in it.className }
    }
}
