package com.github.yutaplug.bettermessagelogger

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/** Which logged messages a dashboard list or count covers. */
internal data class LogQuery(
    val status: Int = ALL,
    val guildId: Long? = null,
    val dmsOnly: Boolean = false,
    val channelId: Long? = null,
    val authorId: Long? = null,
    val search: String? = null,
) {
    companion object {
        const val ALL = 0
        const val DELETED = 1
        const val EDITED = 2
    }
}

/** A server, channel or user in the dashboard, with how many of its messages were logged. */
internal data class LogGroup(
    val id: Long,
    val name: String?,
    val parentId: Long?,
    val count: Long,
    val deleted: Long,
    val edited: Long,
)

internal data class LogStatistics(
    val messages: Long,
    val deleted: Long,
    val edited: Long,
    val bytes: Long,
)

/**
 * The log, in SQLite. When logs are kept across restarts it lives in a file; otherwise in memory, so the
 * dashboard and history work the same either way. One thread owns the connection; every call is queued on it.
 */
internal class LogDatabase(private val reportError: (String, Throwable) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BetterMessageLogger-Database").apply { isDaemon = true }
    }
    private var database: SQLiteDatabase? = null
    private var file: File? = null
    private var stopped = false

    /** Opens [target] (null for memory), moving this session's memory log into it when switching to a file. */
    fun openAsync(target: File?, legacy: File?, done: (Result<Unit>) -> Unit) = enqueue("open database", done) {
        val previous = database
        if (previous != null && file?.canonicalPath == target?.canonicalPath) return@enqueue
        val next = open(target)
        try {
            if (target != null && legacy != null) importFolderDatabase(next, legacy)
            if (previous != null && file == null && target != null) copy(previous, next)
        } catch (error: Exception) {
            next.close()
            throw error
        }
        previous?.close()
        database = next
        file = target
    }

    private fun open(target: File?): SQLiteDatabase {
        val db = if (target == null) {
            SQLiteDatabase.create(null)
        } else {
            val parent = target.parentFile
            check(parent == null || parent.isDirectory || parent.mkdirs()) { "Could not create database directory" }
            SQLiteDatabase.openOrCreateDatabase(target, null)
        }
        try {
            db.disableWriteAheadLogging()
            // Keeps the rollback journal in memory, so storage only ever holds the .db file.
            db.rawQuery("PRAGMA journal_mode=MEMORY", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=NORMAL")
            db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
            upgrade(db)
            // Enabled after the upgrade so rebuilding old tables can't cascade into the new ones.
            db.setForeignKeyConstraintsEnabled(true)
        } catch (error: Exception) {
            db.close()
            throw error
        }
        return db
    }

    // region Schema

    private fun upgrade(db: SQLiteDatabase) {
        val version = db.version
        check(version <= SCHEMA_VERSION) { "Database was created by a newer BetterMessageLogger" }
        if (version == SCHEMA_VERSION) return
        transaction(db) {
            val old = tables(db)
            if ("messages" in old) db.execSQL("ALTER TABLE messages RENAME TO messages_old")
            createSchema(db)
            if ("messages" in old) {
                importOld(db, db, "messages_old", if ("message_edits" in old) "message_edits" else null)
                if ("message_edits" in old) db.execSQL("DROP TABLE message_edits")
                db.execSQL("DROP TABLE messages_old")
            }
            db.version = SCHEMA_VERSION
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "message_id INTEGER PRIMARY KEY, channel_id INTEGER NOT NULL, guild_id INTEGER, " +
                "author_id INTEGER NOT NULL, author_name TEXT NOT NULL, author_avatar TEXT, " +
                "author_bot INTEGER NOT NULL DEFAULT 0, content TEXT NOT NULL, timestamp INTEGER NOT NULL, " +
                "edited_timestamp INTEGER, deleted INTEGER NOT NULL DEFAULT 0, deleted_timestamp INTEGER, " +
                "edit_count INTEGER NOT NULL DEFAULT 0, payload TEXT)",
        )
        // One row per replaced version; a gateway update delivered twice has the same edit time.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS edits (" +
                "message_id INTEGER NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE, " +
                "edit_timestamp INTEGER NOT NULL, content TEXT NOT NULL, removed TEXT, " +
                "UNIQUE(message_id, edit_timestamp))",
        )
        // Server and channel names, so the dashboard can name places Discord no longer has loaded.
        db.execSQL("CREATE TABLE IF NOT EXISTS names (id INTEGER PRIMARY KEY, name TEXT NOT NULL, parent_id INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS log_channel ON messages(channel_id, message_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS log_guild ON messages(guild_id, message_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS log_author ON messages(author_id, message_id)")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS edits_added AFTER INSERT ON edits BEGIN " +
                "UPDATE messages SET edit_count = edit_count + 1 WHERE message_id = NEW.message_id; END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS edits_removed AFTER DELETE ON edits BEGIN " +
                "UPDATE messages SET edit_count = edit_count - 1 WHERE message_id = OLD.message_id; END",
        )
    }

    /** Imports the Aliucord/BetterMessageLogger/ folder database of versions before 1.0.20, then deletes it. */
    private fun importFolderDatabase(db: SQLiteDatabase, source: File) {
        if (!source.isFile) {
            removeEmptyFolder(source)
            return
        }
        // Opening it fully lets SQLite recover a pending WAL before reading.
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
            val names = tables(legacy)
            if ("messages" in names) {
                transaction(db) {
                    importOld(legacy, db, "messages", if ("message_edits" in names) "message_edits" else null)
                }
            }
        }
        check(SQLiteDatabase.deleteDatabase(source)) { "Could not remove the old folder database" }
        removeEmptyFolder(source)
    }

    private fun removeEmptyFolder(source: File) {
        source.parentFile?.takeIf { it.name == "BetterMessageLogger" && it.list()?.isEmpty() == true }?.delete()
    }

    /** Reads messages of an older schema: edits in a separate table, or packed into an `edits` column. */
    private fun importOld(source: SQLiteDatabase, target: SQLiteDatabase, table: String, editsTable: String?) {
        val editColumns = editsTable?.let { columns(source, it) }.orEmpty()
        val ordered = if ("position" in editColumns) "position" else "edit_timestamp"
        source.rawQuery("SELECT * FROM $table", null).use { cursor ->
            while (cursor.moveToNext()) {
                val record = readRecord(cursor)
                val edits = if (editsTable != null && "message_id" in editColumns) {
                    source.rawQuery(
                        "SELECT edit_timestamp, content FROM $editsTable WHERE message_id = ? ORDER BY $ordered",
                        arrayOf(record.id.toString()),
                    ).use { readEdits(it) }
                } else {
                    unpackEdits(cursor.string("edits"))
                }
                val deleted = cursor.long("deleted") == 1L
                if (!deleted && edits.isEmpty()) continue
                upsert(target, record.copy(deleted = deleted, edits = edits))
            }
        }
    }

    /** The packed format of the first versions: entries split by U+001E, each `time` U+001F `content`. */
    private fun unpackEdits(packed: String?): List<MessageEdit> = packed.orEmpty().split('\u001e').mapNotNull {
        val separator = it.indexOf('\u001f')
        if (separator < 0) return@mapNotNull null
        val time = it.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
        MessageEdit(time, it.substring(separator + 1))
    }

    private fun copy(from: SQLiteDatabase, to: SQLiteDatabase) = transaction(to) {
        from.rawQuery("SELECT * FROM messages", null).use { cursor ->
            while (cursor.moveToNext()) {
                val record = readRecord(cursor)
                upsert(to, record.copy(edits = edits(from, record.id)))
            }
        }
        from.rawQuery("SELECT id, name, parent_id FROM names", null).use { cursor ->
            while (cursor.moveToNext()) {
                saveName(to, cursor.getLong(0), cursor.getString(1), cursor.long("parent_id"))
            }
        }
    }

    // endregion

    // region Writes

    /** Saves the message row and any edits it doesn't have yet. A saved deletion is never undone. */
    fun saveAsync(record: MessageRecord) {
        if (!record.logged) return
        val payload = record.serialized()
        enqueue("save message") { transaction(db()) { upsert(db(), record, payload) } }
    }

    /**
     * Marks a message deleted when it was saved earlier (for example after an edit in a past session) but isn't in
     * memory anymore. [done] gets the updated record, or null when it wasn't saved.
     */
    fun markDeletedAsync(channelId: Long, id: Long, time: Long, done: (MessageRecord?) -> Unit) =
        enqueue("save deletion", { done(it.getOrNull()) }) {
            val values = ContentValues().apply {
                put("deleted", 1)
                put("deleted_timestamp", time)
            }
            val changed = db().update(
                "messages",
                values,
                "message_id = ? AND channel_id = ? AND deleted = 0",
                arrayOf(id.toString(), channelId.toString()),
            )
            if (changed > 0) load(id) else null
        }

    /**
     * Applies an edit to a saved message that isn't in memory: the saved text becomes a history entry when it
     * differs. [done] gets the updated record, or null when it wasn't saved or nothing changed.
     */
    fun applyEditAsync(incoming: MessageRecord, time: Long, done: (MessageRecord?) -> Unit) {
        val payload = incoming.serialized()
        enqueue("save edit", { done(it.getOrNull()) }) {
            val saved = load(incoming.id) ?: return@enqueue null
            if (saved.content == incoming.content) return@enqueue null
            val edits = saved.edits + MessageEdit(time, saved.content)
            val updated = incoming.copy(
                deleted = saved.deleted,
                deletedTimestamp = saved.deletedTimestamp,
                edits = edits,
                message = null,
                payload = payload,
            )
            transaction(db()) { upsert(db(), updated, payload) }
            updated
        }
    }

    fun saveNameAsync(id: Long, name: String, parentId: Long?) = enqueue("save name") {
        saveName(db(), id, name, parentId)
    }

    private fun saveName(db: SQLiteDatabase, id: Long, name: String, parentId: Long?) {
        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("parent_id", parentId)
        }
        db.insertWithOnConflict("names", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsert(db: SQLiteDatabase, record: MessageRecord, payload: String? = record.serialized()) {
        val values = ContentValues().apply {
            put("message_id", record.id)
            put("channel_id", record.channelId)
            put("guild_id", record.guildId?.takeIf { it != 0L })
            put("author_id", record.authorId)
            put("author_name", record.authorName)
            put("author_avatar", record.authorAvatar)
            put("author_bot", if (record.bot) 1 else 0)
            put("content", record.content)
            put("timestamp", record.timestamp)
            put("edited_timestamp", record.editedTimestamp)
            put("deleted", if (record.deleted) 1 else 0)
            put("deleted_timestamp", record.deletedTimestamp)
            if (payload != null) put("payload", payload)
        }
        // REPLACE would delete the row first and cascade-delete its edit history.
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (!record.deleted) {
            values.remove("deleted")
            values.remove("deleted_timestamp")
        }
        db.update("messages", values, "message_id = ?", arrayOf(record.id.toString()))
        for (edit in record.edits) {
            val row = ContentValues().apply {
                put("message_id", record.id)
                put("edit_timestamp", edit.timestamp)
                put("content", edit.content)
                put("removed", edit.removedJson())
            }
            db.insertWithOnConflict("edits", null, row, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun removeAsync(id: Long) = enqueue("remove message") {
        db().delete("messages", "message_id = ?", arrayOf(id.toString()))
    }

    /** Deletes a saved log file that isn't the open one (queued, so it runs after any switch away from it). */
    fun deleteFileAsync(target: File) = enqueue("delete the saved log") {
        if (file?.canonicalPath != target.canonicalPath && target.exists()) SQLiteDatabase.deleteDatabase(target)
    }

    /** Removes saved messages the logging filters exclude now. Pages through rows so memory stays bounded. */
    fun pruneAsync(keep: (MessageRecord) -> Boolean, done: () -> Unit = {}) =
        enqueue("apply message filters", { done() }) {
            val query = "SELECT $SUMMARY_COLUMNS FROM messages WHERE message_id > ? ORDER BY message_id LIMIT 256"
            var last = 0L
            transaction(db()) {
                while (true) {
                    val page = db().rawQuery(query, arrayOf(last.toString())).use { cursor ->
                        val list = ArrayList<MessageRecord>()
                        while (cursor.moveToNext()) list.add(readRecord(cursor))
                        list
                    }
                    if (page.isEmpty()) break
                    last = page.last().id
                    page.filterNot(keep).forEach {
                        db().delete("messages", "message_id = ?", arrayOf(it.id.toString()))
                    }
                }
            }
        }

    fun clearAsync(done: (Result<Unit>) -> Unit) = enqueue("clear saved logs", done) {
        db().delete("messages", null, null)
        db().delete("names", null, null)
        // Reclaims space and wipes deleted content from reusable pages.
        if (file != null) db().execSQL("VACUUM")
    }

    // endregion

    // region Reads

    fun loadRangeAsync(channelId: Long, range: LongRange, done: (Result<List<MessageRecord>>) -> Unit) =
        enqueue("load channel history", done) {
            val selection = "SELECT message_id FROM messages WHERE channel_id = ? AND message_id BETWEEN ? AND ? " +
                "ORDER BY message_id DESC LIMIT $MAX_RANGE_RECORDS"
            val args = arrayOf(channelId.toString(), range.first.toString(), range.last.toString())
            val edits = HashMap<Long, MutableList<MessageEdit>>()
            db().rawQuery(
                "SELECT message_id, edit_timestamp, content, removed FROM edits " +
                    "WHERE message_id IN ($selection) ORDER BY message_id, edit_timestamp",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    edits.getOrPut(cursor.getLong(0)) { ArrayList() }.add(
                        MessageEdit(cursor.getLong(1), cursor.getString(2), MessageEdit.removedFrom(cursor.getString(3))),
                    )
                }
            }
            db().rawQuery("SELECT * FROM messages WHERE message_id IN ($selection) ORDER BY message_id", args).use {
                val result = ArrayList<MessageRecord>()
                while (it.moveToNext()) {
                    val record = readRecord(it)
                    result.add(record.copy(edits = edits[record.id].orEmpty()))
                }
                result
            }
        }

    fun loadAsync(id: Long, done: (MessageRecord?) -> Unit) = enqueue("load message", { done(it.getOrNull()) }) {
        load(id)
    }

    private fun load(id: Long): MessageRecord? =
        db().rawQuery("SELECT * FROM messages WHERE message_id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) readRecord(it).copy(edits = edits(db(), id)) else null
        }

    /** A page of logged messages, newest first, without their payload or history (see [LogEntry]). */
    fun queryAsync(query: LogQuery, offset: Int, limit: Int, done: (Result<List<LogEntry>>) -> Unit) =
        enqueue("read logged messages", done) {
            val (where, args) = where(query)
            db().rawQuery(
                "SELECT $SUMMARY_COLUMNS, edit_count, " +
                    "(payload LIKE '%\"attachments\":[{%' OR payload LIKE '%\"embeds\":[{%') AS has_media " +
                    "FROM messages WHERE $where ORDER BY message_id DESC LIMIT $limit OFFSET $offset",
                args,
            ).use { cursor ->
                val result = ArrayList<LogEntry>()
                while (cursor.moveToNext()) {
                    result.add(
                        LogEntry(readRecord(cursor), cursor.long("edit_count")?.toInt() ?: 0, cursor.long("has_media") == 1L),
                    )
                }
                result
            }
        }

    /** Logged messages grouped by server ([GROUP_GUILD]), channel ([GROUP_CHANNEL]) or author ([GROUP_AUTHOR]). */
    fun groupsAsync(query: LogQuery, by: Int, done: (Result<List<LogGroup>>) -> Unit) =
        enqueue("read logged places", done) {
            val (where, args) = where(query)
            val column = when (by) {
                GROUP_GUILD -> "guild_id"
                GROUP_CHANNEL -> "channel_id"
                else -> "author_id"
            }
            val name = if (by == GROUP_AUTHOR) {
                "author_name"
            } else {
                "(SELECT name FROM names WHERE names.id = $column)"
            }
            val parent = if (by == GROUP_CHANNEL) "guild_id" else "NULL"
            // A bare column next to MAX() comes from the row holding the maximum: the newest message.
            db().rawQuery(
                "SELECT $column, $name, $parent, MAX(message_id), COUNT(*), SUM(deleted), SUM(edit_count > 0) " +
                    "FROM messages WHERE $where AND $column IS NOT NULL GROUP BY $column ORDER BY 4 DESC",
                args,
            ).use { cursor ->
                val result = ArrayList<LogGroup>()
                while (cursor.moveToNext()) {
                    result.add(
                        LogGroup(
                            cursor.getLong(0),
                            if (cursor.isNull(1)) null else cursor.getString(1),
                            if (cursor.isNull(2)) null else cursor.getLong(2),
                            cursor.getLong(4),
                            cursor.getLong(5),
                            cursor.getLong(6),
                        ),
                    )
                }
                result
            }
        }

    fun countAsync(query: LogQuery, done: (Result<Long>) -> Unit) = enqueue("count logged messages", done) {
        val (where, args) = where(query)
        db().rawQuery("SELECT COUNT(*) FROM messages WHERE $where", args).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private fun where(query: LogQuery): Pair<String, Array<String>> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        when (query.status) {
            LogQuery.DELETED -> clauses.add("deleted = 1")
            LogQuery.EDITED -> clauses.add("edit_count > 0")
        }
        if (query.dmsOnly) clauses.add("guild_id IS NULL")
        query.guildId?.let {
            clauses.add("guild_id = ?")
            args.add(it.toString())
        }
        query.channelId?.let {
            clauses.add("channel_id = ?")
            args.add(it.toString())
        }
        query.authorId?.let {
            clauses.add("author_id = ?")
            args.add(it.toString())
        }
        query.search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            clauses.add(
                "(content LIKE ? ESCAPE '\\' OR author_name LIKE ? ESCAPE '\\' OR EXISTS " +
                    "(SELECT 1 FROM edits WHERE edits.message_id = messages.message_id AND edits.content LIKE ? ESCAPE '\\'))",
            )
            repeat(3) { args.add(pattern) }
        }
        return (if (clauses.isEmpty()) "1" else clauses.joinToString(" AND ")) to args.toTypedArray()
    }

    fun messageIdsAsync(done: (Result<Set<Long>>) -> Unit) = enqueue("list saved messages", done) {
        db().rawQuery("SELECT message_id FROM messages", null).use { cursor ->
            val result = HashSet<Long>()
            while (cursor.moveToNext()) result.add(cursor.getLong(0))
            result
        }
    }

    fun statisticsAsync(done: (Result<LogStatistics>) -> Unit) = enqueue("read storage statistics", done) {
        db().rawQuery("SELECT COUNT(*), SUM(deleted), SUM(edit_count > 0) FROM messages", null).use {
            it.moveToFirst()
            LogStatistics(it.getLong(0), it.getLong(1), it.getLong(2), file?.length() ?: 0L)
        }
    }

    fun exportTextAsync(output: File, describe: (MessageRecord) -> String, done: (Result<File>) -> Unit) =
        enqueue("export logs", done) {
            val parent = requireNotNull(output.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Could not create export folder" }
            val temporary = File.createTempFile(".${output.name}.", ".part", parent)
            try {
                FileOutputStream(temporary).use { stream ->
                    val writer = OutputStreamWriter(stream, Charsets.UTF_8)

                    fun format(time: Long) = Dates.absolute(time)
                    writer.write("BetterMessageLogger export\n\n")
                    db().rawQuery("SELECT * FROM messages ORDER BY message_id", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val record = readRecord(cursor)
                            writer.write("Message ID: ${record.id}\n")
                            writer.write("Where: ${describe(record)}\n")
                            writer.write("Author: ${record.authorName} (${record.authorId})\n")
                            writer.write("Sent: ${format(record.timestamp)}\n")
                            record.deletedTimestamp?.let { writer.write("Deleted: ${format(it)}\n") }
                            if (record.deleted && record.deletedTimestamp == null) writer.write("Deleted\n")
                            writer.write("Content:\n${record.content}\n")
                            val history = edits(db(), record.id)
                            if (history.isNotEmpty()) writer.write("Edit history (oldest first):\n")
                            history.forEach { edit ->
                                writer.write("Replaced ${format(edit.timestamp)}:\n${edit.content}\n")
                                edit.removed.forEach { writer.write("Removed attachment: ${it.a()} ${it.f()}\n") }
                            }
                            writer.write("\n")
                        }
                    }
                    writer.flush()
                    stream.fd.sync()
                }
                check(temporary.renameTo(output)) { "Could not finish export" }
            } finally {
                temporary.delete()
            }
            output
        }

    private fun edits(db: SQLiteDatabase, id: Long): List<MessageEdit> = db.rawQuery(
        "SELECT edit_timestamp, content, removed FROM edits WHERE message_id = ? ORDER BY edit_timestamp",
        arrayOf(id.toString()),
    ).use { readEdits(it) }

    private fun readEdits(cursor: Cursor): List<MessageEdit> {
        val result = ArrayList<MessageEdit>()
        while (cursor.moveToNext()) {
            val removed = if (cursor.columnCount > 2 && !cursor.isNull(2)) cursor.getString(2) else null
            result.add(MessageEdit(cursor.getLong(0), cursor.getString(1).orEmpty(), MessageEdit.removedFrom(removed)))
        }
        return result
    }

    private fun readRecord(cursor: Cursor) = MessageRecord(
        id = cursor.long("message_id")!!,
        channelId = cursor.long("channel_id")!!,
        guildId = cursor.long("guild_id")?.takeIf { it != 0L },
        authorId = cursor.long("author_id") ?: 0,
        authorName = cursor.string("author_name")?.takeIf { it.trim().isNotEmpty() } ?: "Unknown user",
        authorAvatar = cursor.string("author_avatar"),
        bot = cursor.long("author_bot") == 1L,
        content = cursor.string("content").orEmpty(),
        timestamp = cursor.long("timestamp") ?: MessageRecord.snowflakeTime(cursor.long("message_id")!!),
        editedTimestamp = cursor.long("edited_timestamp"),
        deleted = cursor.long("deleted") == 1L,
        deletedTimestamp = cursor.long("deleted_timestamp"),
        payload = cursor.string("payload"),
    )

    // endregion

    // region Plumbing

    fun stop() = synchronized(this) {
        if (stopped) return@synchronized
        stopped = true
        // Closing runs after every accepted write. Queued writes are never dropped.
        executor.execute {
            try {
                database?.close()
            } catch (error: Exception) {
                reportError("close database", error)
            } finally {
                database = null
            }
        }
        executor.shutdown()
    }

    private fun db() = checkNotNull(database?.takeIf { it.isOpen }) { "Database is unavailable" }

    private fun <T> enqueue(action: String, done: ((Result<T>) -> Unit)? = null, work: () -> T) {
        synchronized(this) {
            if (stopped) {
                done?.invoke(Result.failure(IllegalStateException("Database is closed")))
                return
            }
            executor.execute {
                val result = try {
                    Result.success(work())
                } catch (error: Exception) {
                    reportError(action, error)
                    Result.failure(error)
                }
                done?.invoke(result)
            }
        }
    }

    private fun transaction(db: SQLiteDatabase, work: () -> Unit) {
        db.beginTransaction()
        try {
            work()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            val result = HashSet<String>()
            while (cursor.moveToNext()) result.add(cursor.getString(0))
            result
        }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val result = HashSet<String>()
            while (cursor.moveToNext()) result.add(cursor.getString(1))
            result
        }

    private fun Cursor.long(name: String): Long? = getColumnIndex(name).let {
        if (it < 0 || isNull(it)) null else getLong(it)
    }

    private fun Cursor.string(name: String): String? = getColumnIndex(name).let {
        if (it < 0 || isNull(it)) null else getString(it)
    }

    // endregion

    companion object {
        private const val SCHEMA_VERSION = 3
        const val MAX_RANGE_RECORDS = 1024
        const val GROUP_GUILD = 0
        const val GROUP_CHANNEL = 1
        const val GROUP_AUTHOR = 2

        // Everything but the payload, for scans and lists that don't render the message.
        private const val SUMMARY_COLUMNS = "message_id, channel_id, guild_id, author_id, author_name, " +
            "author_avatar, author_bot, content, timestamp, edited_timestamp, deleted, deleted_timestamp"
    }
}

/** A dashboard row: the message without payload or history, how often it was edited, and whether it had media. */
internal data class LogEntry(val record: MessageRecord, val editCount: Int, val hasMedia: Boolean)
