package com.github.yutaplug.bettermessagelogger

import android.util.LruCache
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.api.channel.ChannelUtils
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStream
import java.io.File
import com.discord.api.message.Message as ApiMessage

/**
 * What the logger knows: recent live messages (so a delete or edit can be recorded even when Discord has
 * dropped the channel), the logged records shown in chat (a bounded cache of the [LogDatabase]), and messages
 * hidden until restart. Records are immutable, and every map is guarded by [lock].
 */
internal class MessageLog(
    private val settings: SettingsAPI,
    private val databaseFile: File,
    private val reportError: (String, Throwable) -> Unit,
    private val changed: () -> Unit,
) {
    private val lock = Any()
    private val live = LruCache<Long, Message>(LIVE_CACHE)
    private val logged = LinkedHashMap<Long, MessageRecord>(256, 0.75f, true)
    private val shown = HashMap<Long, Pair<MessageRecord, Message>>()
    private val hidden = LinkedHashSet<Long>()
    private val windows = HashMap<Long, LongRange>()
    private val named = HashSet<Long>()

    @Volatile var filters = Filters(settings)
        private set

    @Volatile var mediaOptions = MediaOptions(settings)
        private set

    @Volatile var database: LogDatabase? = null
        private set

    @Volatile var databaseReady = false
        private set

    @Volatile var media: MediaStore? = null
        private set

    var decorations: ChatDecorations? = null

    fun start(mediaFolder: File) {
        media = MediaStore(mediaFolder, reportError) { synchronized(lock) { shown.clear() }; changed() }
        database = LogDatabase(reportError)
        loadHidden()
        open(Prefs.persist(settings), importOld = true)
    }

    fun stop() {
        synchronized(lock) {
            logged.clear()
            shown.clear()
            hidden.clear()
            windows.clear()
        }
        live.evictAll()
        database?.stop()
        database = null
        databaseReady = false
        media?.stop()
        media = null
    }

    // region Incoming events (Discord's store thread)

    fun onCreated(messages: List<ApiMessage>) {
        for (api in messages) {
            val message = Message(api)
            if (!message.isLocal && message.id > 0) {
                live.put(message.id, message)
                prefetch(message)
            }
        }
    }

    fun onLoaded(messages: List<Message>) {
        val recent = System.currentTimeMillis() - PREFETCH_HISTORY_MS
        for (message in messages) {
            if (message.isLocal || message.id <= 0) continue
            live.put(message.id, message)
            // Scrolling back through old history shouldn't download its media.
            if (MessageRecord.snowflakeTime(message.id) > recent) prefetch(message)
        }
    }

    /** Messages drawn in chat are the most reliable copy of what the user saw. */
    fun onShown(message: Message) {
        if (message.isLocal || message.id <= 0) return
        if (synchronized(lock) { logged[message.id]?.deleted == true }) return
        live.put(message.id, message)
    }

    /** Runs before Discord applies a MESSAGE_UPDATE, which may be partial, while the old version is known. */
    fun onUpdate(store: StoreMessages, update: ApiMessage) {
        val id = update.o()
        val before = storedMessage(store, update.g(), id)
        val record = synchronized(lock) { logged[id] }
        val editedAt = update.j()?.g()?.takeIf { it > 0 }
        if (before == null && record == null) {
            updateSavedOnly(update, editedAt)
            return
        }
        val after = before?.merge(update) ?: record!!.toMessage().merge(update)
        live.put(id, after)
        val previous = record ?: recordOf(before!!)
        val next = recordOf(after)
        if (!filters.logs(next)) {
            forget(id)
            return
        }
        val removed = attachmentsRemoved(previous, after)
        // Only content edits and removed attachments count; embed resolves and duplicate deliveries don't.
        val edited = editedAt != null && ((update.i() != null && next.content != previous.content) || removed.isNotEmpty())
        val history = when {
            edited && previous.edits.none { it.timestamp == editedAt } ->
                previous.edits + MessageEdit(editedAt!!, previous.content, removed)
            else -> previous.edits
        }
        val updated = next.copy(deleted = previous.deleted, deletedTimestamp = previous.deletedTimestamp, edits = history)
        if (!updated.logged) return
        synchronized(lock) { remember(updated) }
        persist(updated)
        if (removed.isNotEmpty()) {
            val options = mediaOptions
            val items = removed.mapNotNull(MediaStore::attachmentItem).filter(options::allows)
            if (items.isNotEmpty()) media?.saveAsync(id, items, options.maxBytes)
        }
        redraw()
    }

    /** An edit of a message only the database still has, from an earlier session. */
    private fun updateSavedOnly(update: ApiMessage, editedAt: Long?) {
        if (editedAt == null || update.i() == null || update.e() == null) return
        val incoming = recordOf(Message(update))
        if (!filters.logs(incoming)) return
        database?.applyEditAsync(incoming, editedAt) { saved ->
            if (saved == null) return@applyEditAsync
            synchronized(lock) { remember(saved) }
            redraw()
        }
    }

    fun onDelete(store: StoreMessages, event: ModelMessageDelete) {
        val channelId = event.channelId
        val now = System.currentTimeMillis()
        for (id in event.messageIds.orEmpty()) {
            val message = storedMessage(store, channelId, id)
            if (message?.isLocal == true) continue
            val record = synchronized(lock) { logged[id] }
            val latest = when {
                message != null -> recordOf(message).copy(edits = record?.edits.orEmpty())
                record != null -> record
                else -> {
                    markSavedDeleted(channelId, id, now)
                    continue
                }
            }
            if (latest.channelId != channelId || !filters.logs(latest)) continue
            val deleted = latest.copy(deleted = true, deletedTimestamp = record?.deletedTimestamp ?: now)
            synchronized(lock) { remember(deleted) }
            persist(deleted)
            keepMedia(deleted)
        }
        redraw()
    }

    /** A message deleted while not in memory, which may be in the database from an earlier session. */
    private fun markSavedDeleted(channelId: Long, id: Long, time: Long) {
        database?.markDeletedAsync(channelId, id, time) { saved ->
            if (saved == null || !filters.logs(saved)) return@markDeletedAsync
            synchronized(lock) { remember(saved) }
            keepMedia(saved)
            redraw()
        }
    }

    private fun storedMessage(store: StoreMessages, channelId: Long, id: Long): Message? = try {
        store.getMessage(channelId, id)
    } catch (_: Exception) {
        null
    } ?: live.get(id)

    private fun attachmentsRemoved(previous: MessageRecord, after: Message) = try {
        val remaining = HashSet<String?>()
        after.attachments?.forEach { remaining.add(it.f()) }
        previous.attachments().filter { it.f() !in remaining }
    } catch (_: Exception) {
        emptyList()
    }

    // endregion

    // region Chat

    fun record(id: Long): MessageRecord? = synchronized(lock) { logged[id] }?.takeIf(filters::logs)

    /** Whether Hide Message is on; when off, hidden messages show again (the list is kept). */
    @Volatile var hidingEnabled = Prefs.hideEnabled(settings)
        private set

    /** Hides a message from chat, until Discord restarts or for good, as the settings say. */
    fun hide(id: Long) {
        synchronized(lock) {
            hidden.add(id)
            while (hidden.size > HIDDEN_LIMIT) hidden.remove(hidden.first())
        }
        saveHidden()
        changed()
    }

    fun unhideAll() {
        synchronized(lock) { hidden.clear() }
        saveHidden()
        changed()
    }

    val hiddenCount get() = synchronized(lock) { hidden.size }

    val hidesAcrossRestarts get() = Prefs.hidePersist(settings)

    fun setHidingEnabled(enabled: Boolean) {
        settings.setBool(Prefs.HIDE_ENABLED, enabled)
        hidingEnabled = enabled
        changed()
    }

    /** Kept across restarts: saves the current list. Until restart: forgets the saved list, keeping this session's. */
    fun setHidePersist(persist: Boolean) {
        settings.setBool(Prefs.HIDE_PERSIST, persist)
        saveHidden()
    }

    private fun loadHidden() {
        if (!Prefs.hidePersist(settings)) return
        val saved = settings.getString(Prefs.HIDDEN_IDS, "").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        synchronized(lock) { hidden.addAll(saved) }
    }

    private fun saveHidden() {
        val value = if (Prefs.hidePersist(settings)) synchronized(lock) { hidden.joinToString(",") } else ""
        settings.setString(Prefs.HIDDEN_IDS, value)
    }

    /**
     * The chat list Discord is about to show, with hidden messages removed and logged deleted ones added back.
     * A deleted message belongs in the list when it falls between the oldest and newest message shown, or is newer
     * than all of them while the chat is at the present ([detached] false).
     */
    fun arrange(channelId: Long, messages: List<Message>, detached: Boolean): List<Message> {
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        val present = HashSet<Long>(messages.size * 2)
        for (message in messages) {
            present.add(message.id)
            if (message.isLocal) continue
            if (message.id < oldest) oldest = message.id
            if (message.id > newest) newest = message.id
        }
        val result = synchronized(lock) {
            val hidden = if (hidingEnabled) hidden else emptySet<Long>()
            val kept = if (hidden.isEmpty()) messages else messages.filter { it.id !in hidden }
            if (oldest == Long.MAX_VALUE) return@synchronized kept
            val window = oldest..(if (detached) newest else Long.MAX_VALUE)
            load(channelId, window)
            val current = filters
            val restored = logged.values.filter {
                it.deleted && it.channelId == channelId && it.id in window && it.id !in present && it.id !in hidden &&
                    current.logs(it)
            }
            if (restored.isEmpty()) kept else (kept + restored.map(::shownMessage)).sortedBy { it.id }
        }
        return result
    }

    /** The deleted message an action sheet asks for, when Discord doesn't have it anymore. */
    fun deletedMessage(channelId: Long, id: Long): Message? = synchronized(lock) {
        logged[id]?.takeIf { it.deleted && it.channelId == channelId && filters.logs(it) }?.let(::shownMessage)
    }

    /** What chat shows for a deleted message: with saved media swapped in, built once per record. */
    private fun shownMessage(record: MessageRecord): Message {
        shown[record.id]?.let { (source, message) -> if (source === record) return message }
        val store = media
        val message = if (store != null && store.hasMedia(record.id)) store.localize(record.detached()) else record.toMessage()
        shown[record.id] = record to message
        return message
    }

    /** Pulls saved records of [window] into memory, once per window. */
    private fun load(channelId: Long, window: LongRange) {
        val db = database?.takeIf { databaseReady } ?: return
        if (windows[channelId] == window) return
        windows[channelId] = window
        if (windows.size > WINDOWS) windows.remove(windows.keys.first())
        db.loadRangeAsync(channelId, window) { result ->
            val saved = result.getOrNull() ?: return@loadRangeAsync
            val missingMedia = ArrayList<MessageRecord>()
            var added = false
            synchronized(lock) {
                for (record in saved) {
                    if (!filters.logs(record)) continue
                    val known = logged[record.id]
                    val merged = known?.mergedWith(record) ?: record
                    if (merged == known) continue
                    remember(merged)
                    added = true
                    if (known == null && merged.deleted) missingMedia.add(merged)
                }
            }
            // Media of messages deleted before it could be saved gets another try.
            if (keepsMedia()) missingMedia.forEach(::keepMedia)
            if (added) redraw()
        }
    }

    private fun MessageRecord.mergedWith(saved: MessageRecord) = copy(
        deleted = deleted || saved.deleted,
        deletedTimestamp = deletedTimestamp ?: saved.deletedTimestamp,
        edits = (saved.edits + edits).distinctBy { it.timestamp }.sortedBy { it.timestamp },
        payload = payload ?: saved.payload,
    )

    // endregion

    // region Records

    private fun recordOf(message: Message) = MessageRecord.of(message, guildOf(message.channelId, message.guildId))

    private fun remember(record: MessageRecord) {
        shown.remove(record.id)
        if (!record.logged) {
            logged.remove(record.id)
            return
        }
        logged[record.id] = record
        val overflow = logged.size - LOGGED_CACHE
        if (overflow > 0) logged.keys.take(overflow).forEach { logged.remove(it); shown.remove(it) }
    }

    private fun forget(id: Long) {
        val removed = synchronized(lock) {
            shown.remove(id)
            logged.remove(id)
        } ?: return
        if (removed.logged) {
            database?.removeAsync(id)
            media?.removeAsync(id)
        }
    }

    /** Removes a message from the log (not from Discord). */
    fun delete(id: Long) {
        synchronized(lock) {
            shown.remove(id)
            logged.remove(id)
            windows.clear()
        }
        database?.removeAsync(id)
        media?.removeAsync(id)
        redraw()
    }

    private fun persist(record: MessageRecord) {
        val db = database ?: return
        db.saveAsync(record)
        rememberNames(db, record)
    }

    /** Saves channel and server names, so the dashboard can name them after Discord forgets them. */
    private fun rememberNames(db: LogDatabase, record: MessageRecord) {
        val fresh = synchronized(lock) { listOfNotNull(record.channelId, record.guildId).filter(named::add) }
        if (fresh.isEmpty()) return
        try {
            if (record.channelId in fresh) {
                StoreStream.getChannels().getChannel(record.channelId)?.let {
                    db.saveNameAsync(record.channelId, ChannelUtils.d(it, Utils.appContext, false), record.guildId)
                }
            }
            val guildId = record.guildId
            if (guildId != null && guildId in fresh) {
                StoreStream.getGuilds().getGuild(guildId)?.name?.let { db.saveNameAsync(guildId, it, null) }
            }
        } catch (error: Exception) {
            reportError("remember channel names", error)
        }
    }

    private fun keepMedia(record: MessageRecord) {
        val store = media ?: return
        val options = mediaOptions
        val items = try {
            MediaStore.items(record.toMessage()).filter(options::allows)
        } catch (_: Exception) {
            emptyList()
        }
        store.saveAsync(record.id, items, options.maxBytes)
    }

    private fun prefetch(message: Message) {
        val store = media ?: return
        val author = message.author ?: return
        val guildId = guildOf(message.channelId, message.guildId)
        val current = filters
        if (current.logs(author.id, author.e() == true, message.channelId, guildId) &&
            current.prefetches(author.id, message.channelId, guildId)
        ) {
            store.prefetchAsync(message, mediaOptions)
        }
    }

    fun guildOf(channelId: Long, guildId: Long?): Long? {
        if (guildId != null && guildId != 0L) return guildId
        return try {
            StoreStream.getChannels().getChannel(channelId)?.i()?.takeIf { it != 0L }
        } catch (_: Exception) {
            null
        }
    }

    /** "#channel · Server", or the DM's name, for exports and the dashboard. */
    fun describePlace(channelId: Long, guildId: Long?): String = try {
        val channel = StoreStream.getChannels().getChannel(channelId)
        val name = channel?.let { ChannelUtils.d(it, Utils.appContext, true) } ?: "Channel $channelId"
        val server = guildId?.let { StoreStream.getGuilds().getGuild(it)?.name ?: "Server $it" }
        if (server == null) name else "$name · $server"
    } catch (_: Exception) {
        "Channel $channelId"
    }

    // endregion

    // region Storage

    /** Media survives a restart only when the log itself does. */
    private fun keepsMedia() = Prefs.persist(settings)

    private fun open(persist: Boolean, importOld: Boolean) {
        val db = database ?: return
        databaseReady = false
        val oldFolder = if (importOld && persist) File(databaseFile.parentFile, "BetterMessageLogger/${databaseFile.name}") else null
        db.openAsync(if (persist) databaseFile else null, oldFolder) { result ->
            if (database !== db) return@openAsync
            if (result.isFailure) {
                if (persist) {
                    Utils.mainThread.post { Utils.showToast("BetterMessageLogger: the saved log couldn't be opened", true) }
                    // Keep logging this session in memory.
                    open(persist = false, importOld = false)
                }
                return@openAsync
            }
            databaseReady = true
            val pending = synchronized(lock) {
                windows.clear()
                named.clear()
                logged.values.toList()
            }
            // Anything logged while it was opening, or kept from the previous log.
            pending.forEach(db::saveAsync)
            prune()
            changed()
        }
    }

    /** Drops saved messages the filters exclude, then media that isn't needed anymore. */
    private fun prune() {
        val db = database ?: return
        db.pruneAsync(filters::logs) { cleanUpMedia() }
    }

    private fun cleanUpMedia() {
        val store = media ?: return
        if (!keepsMedia()) {
            store.cleanUpAsync(emptySet())
            return
        }
        database?.messageIdsAsync { ids -> store.cleanUpAsync(ids.getOrNull() ?: return@messageIdsAsync) }
    }

    fun setPersist(enabled: Boolean) {
        settings.setBool(Prefs.PERSIST, enabled)
        open(enabled, importOld = false)
    }

    fun filtersChanged() {
        filters = Filters(settings)
        val current = filters
        synchronized(lock) {
            logged.values.filterNot(current::logs).map { it.id }.forEach {
                logged.remove(it)
                shown.remove(it)
            }
            windows.clear()
        }
        prune()
        redraw()
    }

    fun mediaOptionsChanged() {
        mediaOptions = MediaOptions(settings)
    }

    fun clear(done: (Boolean) -> Unit) {
        synchronized(lock) {
            logged.clear()
            shown.clear()
            windows.clear()
        }
        media?.clearAsync()
        val db = database ?: return done(false)
        db.clearAsync { result ->
            // A saved log that's switched off is cleared too.
            if (!Prefs.persist(settings)) db.deleteFileAsync(databaseFile)
            Utils.mainThread.post { done(result.isSuccess) }
        }
        redraw()
    }

    // endregion

    private fun redraw() {
        decorations?.refresh()
        changed()
    }

    companion object {
        private const val LIVE_CACHE = 2048
        private const val LOGGED_CACHE = 4096
        private const val WINDOWS = 32
        private const val HIDDEN_LIMIT = 5000
        private const val PREFETCH_HISTORY_MS = 24L * 60 * 60 * 1000
    }
}
