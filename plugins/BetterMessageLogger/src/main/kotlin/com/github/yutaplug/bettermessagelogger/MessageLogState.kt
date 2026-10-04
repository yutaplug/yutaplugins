package com.github.yutaplug.bettermessagelogger

/** All access is protected by the plugin's state lock. SQLite remains the source of saved history. */
internal class MessageLogState {
    val records = LinkedHashMap<Long, MessageRecord>(64, 0.75f, true)
    val recentDeletes = LinkedHashSet<Long>()
    val ranges = LinkedHashMap<Long, LongRange>()
    val requestedRanges = HashMap<Long, LongRange>()
    var generation = 0L
        private set
    private var transientCount = 0
    private var loggedCount = 0

    fun put(record: MessageRecord) {
        records.put(record.id, record)?.let { if (it.logged) loggedCount-- else transientCount-- }
        if (record.logged) loggedCount++ else transientCount++
        var excessTransient = transientCount - MAX_TRANSIENT
        var excessLogged = loggedCount - MAX_LOGGED
        val iterator = records.entries.iterator()
        while (iterator.hasNext() && (excessTransient > 0 || excessLogged > 0)) {
            val entry = iterator.next()
            if (entry.value.logged && excessLogged > 0) {
                excessLogged--
                loggedCount--
            } else if (!entry.value.logged && excessTransient > 0) {
                excessTransient--
                transientCount--
            } else {
                continue
            }
            iterator.remove()
            recentDeletes.remove(entry.key)
        }
    }

    fun invalidateLoads() {
        generation++
        requestedRanges.clear()
    }

    fun remove(id: Long) {
        val removed = records.remove(id) ?: return
        if (removed.logged) loggedCount-- else transientCount--
        recentDeletes.remove(id)
        // Only saved records can be restored by an in-flight load; ignored live messages must not discard it.
        if (removed.logged) invalidateLoads()
    }

    fun resetRange(channelId: Long) {
        ranges.remove(channelId)
        requestedRanges.remove(channelId)
        recentDeletes.removeAll { records[it]?.channelId == channelId }
        invalidateLoads()
    }

    fun clear() {
        records.clear()
        transientCount = 0
        loggedCount = 0
        recentDeletes.clear()
        ranges.clear()
        invalidateLoads()
    }

    companion object {
        const val MAX_TRANSIENT = 512
        const val MAX_LOGGED = 2048
        const val MAX_CHANNELS = 16
        const val MAX_RANGE_RECORDS = 1024
    }
}
