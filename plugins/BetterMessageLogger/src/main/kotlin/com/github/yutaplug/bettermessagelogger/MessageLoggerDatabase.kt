package com.github.yutaplug.bettermessagelogger

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** One queue owns the connection, including opening, exports and closing. */
internal class MessageLoggerDatabase(
    private val file: File,
    private val legacyFile: File? = null,
    private val reportError: (String, Throwable) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BetterMessageLogger-Database").apply { isDaemon = true }
    }
    private var database: SQLiteDatabase? = null
    private var stopped = false

    fun openAsync(done: (Result<Unit>) -> Unit) = enqueue("open database", done) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Could not create database directory"
        }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        database = db
        try {
            db.setForeignKeyConstraintsEnabled(true)
            db.disableWriteAheadLogging()
            // Keep rollback journals in memory so storage contains only the .db file.
            db.rawQuery("PRAGMA journal_mode=MEMORY", null).use {
                check(it.moveToFirst() && it.getString(0).equals("memory", true)) {
                    "Could not enable single-file database storage"
                }
            }
            db.execSQL("PRAGMA synchronous=FULL")
            db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
            upgrade(db)
            migrateFolderDatabase(db)
        } catch (error: Exception) {
            db.close()
            database = null
            throw error
        }
    }

    private fun migrateFolderDatabase(db: SQLiteDatabase) {
        val source = legacyFile?.takeIf { it.canonicalFile != file.canonicalFile } ?: return
        if (!source.isFile) {
            removeEmptyLegacyFolder(source)
            return
        }
        // Open the complete database so SQLite recovers any pending WAL before importing it.
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
            check(legacy.version <= SCHEMA_VERSION) { "Old folder database was created by a newer BetterMessageLogger" }
            check("message_id" in columns(legacy, "messages")) { "Unrecognized old folder database" }
            val normalized = "message_id" in columns(legacy, "message_edits")
            transaction(db) {
                legacy.rawQuery("SELECT * FROM messages", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val incoming = readRecord(cursor)
                        val history = if (normalized) {
                            readHistory(legacy, incoming.id)
                        } else {
                            cursor.string("edits").orEmpty().split('\u001e').mapNotNull { entry ->
                                val separator = entry.indexOf('\u001f')
                                if (separator < 0) return@mapNotNull null
                                val time = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
                                MessageEdit(time, entry.substring(separator + 1))
                            }
                        }
                        if (!incoming.deleted && history.isEmpty()) continue
                        val current = db
                            .rawQuery(
                                "SELECT * FROM messages WHERE message_id = ?",
                                arrayOf(incoming.id.toString()),
                            ).use { if (it.moveToFirst()) readRecord(it) else null }
                        val latest = if (current != null &&
                            (current.editedTimestamp ?: current.timestamp) >=
                            (incoming.editedTimestamp ?: incoming.timestamp)
                        ) {
                            current
                        } else {
                            incoming
                        }
                        val edits = (readHistory(db, incoming.id) + history).distinct().sortedBy { it.timestamp }
                        db.delete("message_edits", "message_id = ?", arrayOf(incoming.id.toString()))
                        upsert(
                            db,
                            latest.copy(
                                deleted = incoming.deleted || current?.deleted == true,
                                deletedTimestamp = current?.deletedTimestamp ?: incoming.deletedTimestamp,
                                edits = edits,
                            ),
                        )
                    }
                }
            }
        }
        // Remove the old files only after the import commits and its connection closes.
        check(SQLiteDatabase.deleteDatabase(source)) { "Could not remove migrated folder database" }
        removeEmptyLegacyFolder(source)
    }

    private fun removeEmptyLegacyFolder(source: File) {
        source.parentFile?.takeIf { it.name == "BetterMessageLogger" && it.list()?.isEmpty() == true }?.let {
            check(it.delete()) { "Could not remove empty BetterMessageLogger folder" }
        }
    }

    private fun readHistory(db: SQLiteDatabase, id: Long): List<MessageEdit> = db
        .rawQuery(
            "SELECT edit_timestamp, content FROM message_edits WHERE message_id = ? ORDER BY position",
            arrayOf(id.toString()),
        ).use { cursor ->
            val result = ArrayList<MessageEdit>()
            while (cursor.moveToNext()) result.add(MessageEdit(cursor.getLong(0), cursor.getString(1)))
            result
        }

    private fun upgrade(db: SQLiteDatabase) {
        check(db.version <= SCHEMA_VERSION) { "Database was created by a newer BetterMessageLogger" }
        transaction(db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages (" +
                    "message_id INTEGER PRIMARY KEY, channel_id INTEGER NOT NULL, guild_id INTEGER, " +
                    "author_id INTEGER NOT NULL, author_name TEXT NOT NULL, author_avatar TEXT, " +
                    "author_bot INTEGER NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, " +
                    "edited_timestamp INTEGER, deleted INTEGER NOT NULL DEFAULT 0, deleted_timestamp INTEGER, " +
                    "edits TEXT NOT NULL DEFAULT '', payload TEXT)",
            )
            val columns = columns(db, "messages")
            if ("author_avatar" !in columns) db.execSQL("ALTER TABLE messages ADD COLUMN author_avatar TEXT")
            if ("payload" !in columns) db.execSQL("ALTER TABLE messages ADD COLUMN payload TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS message_edits (" +
                    "message_id INTEGER NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE, " +
                    "position INTEGER NOT NULL, edit_timestamp INTEGER NOT NULL, content TEXT NOT NULL, " +
                    "PRIMARY KEY(message_id, position))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS messages_channel_message_idx ON messages(channel_id, message_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS messages_author_idx ON messages(author_id)")
            // Ordinary messages only need the bounded live cache, never permanent storage.
            db.execSQL(
                "DELETE FROM messages WHERE deleted = 0 AND NOT EXISTS " +
                    "(SELECT 1 FROM message_edits WHERE message_edits.message_id = messages.message_id)",
            )
            db.version = SCHEMA_VERSION
        }
    }

    fun saveAsync(record: MessageRecord) {
        if (!record.logged) return
        enqueue("save message") {
            transaction(db()) { upsert(db(), record) }
        }
    }

    private fun upsert(db: SQLiteDatabase, record: MessageRecord) {
        val values = ContentValues().apply {
            put("message_id", record.id)
            put("channel_id", record.channelId)
            put("guild_id", record.guildId)
            put("author_id", record.authorId)
            put("author_name", record.authorName)
            put("author_avatar", record.authorAvatar)
            put("author_bot", if (record.bot) 1 else 0)
            put("content", record.content)
            put("timestamp", record.timestamp)
            put("edited_timestamp", record.editedTimestamp)
            put("deleted", if (record.deleted) 1 else 0)
            put("deleted_timestamp", record.deletedTimestamp)
            put("payload", record.serializedMessage())
        }
        // REPLACE deletes the existing row, which would cascade-delete its edit history.
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (!record.deleted) {
            values.remove("deleted")
            values.remove("deleted_timestamp")
        }
        db.update("messages", values, "message_id = ?", arrayOf(record.id.toString()))
        appendEdits(db, record.id, record.edits)
    }

    private fun appendEdits(db: SQLiteDatabase, id: Long, edits: List<MessageEdit>) {
        for (edit in edits) {
            db.execSQL(
                "INSERT INTO message_edits(message_id, position, edit_timestamp, content) " +
                    "SELECT ?, (SELECT COALESCE(MAX(position), -1) + 1 " +
                    "FROM message_edits WHERE message_id = ?), ?, ? " +
                    "WHERE NOT EXISTS (SELECT 1 FROM message_edits WHERE message_id = ? " +
                    "AND edit_timestamp = ? AND content = ?)",
                arrayOf<Any>(id, id, edit.timestamp, edit.content, id, edit.timestamp, edit.content),
            )
        }
    }

    fun loadRangeAsync(channelId: Long, range: LongRange, done: (Result<List<MessageRecord>>) -> Unit) =
        enqueue("load channel history", done) {
            val selection = "SELECT message_id FROM messages WHERE channel_id = ? AND message_id BETWEEN ? AND ? " +
                "ORDER BY message_id DESC LIMIT ${MessageLogState.MAX_RANGE_RECORDS}"
            val args = arrayOf(channelId.toString(), range.first.toString(), range.last.toString())
            val edits = HashMap<Long, MutableList<MessageEdit>>()
            db()
                .rawQuery(
                    "SELECT message_id, edit_timestamp, content FROM message_edits " +
                        "WHERE message_id IN ($selection) ORDER BY message_id, position",
                    args,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        edits
                            .getOrPut(cursor.getLong(0)) { ArrayList() }
                            .add(MessageEdit(cursor.getLong(1), cursor.getString(2)))
                    }
                }
            val result = ArrayList<MessageRecord>()
            db().rawQuery("SELECT * FROM messages WHERE message_id IN ($selection) ORDER BY message_id", args).use {
                while (it.moveToNext()) {
                    val record = readRecord(it)
                    result.add(record.copy(edits = edits[record.id].orEmpty()))
                }
            }
            result
        }

    fun removeAsync(id: Long) = enqueue("remove message") {
        db().delete("messages", "message_id = ?", arrayOf(id.toString()))
    }

    fun pruneAsync(keep: (MessageRecord) -> Boolean) = enqueue("apply message filters") {
        transaction(db()) {
            // Skip payloads and edit histories while scanning; large databases remain bounded in memory.
            val query = "SELECT message_id, channel_id, guild_id, author_id, author_name, author_avatar, " +
                "author_bot, content, timestamp, edited_timestamp, deleted, deleted_timestamp FROM messages " +
                "WHERE message_id > ? ORDER BY message_id LIMIT 256"
            var lastId = 0L
            while (true) {
                val page = ArrayList<MessageRecord>()
                db().rawQuery(query, arrayOf(lastId.toString())).use { cursor ->
                    while (cursor.moveToNext()) page.add(readRecord(cursor))
                }
                if (page.isEmpty()) break
                lastId = page.last().id
                page.filterNot(keep).forEach { db().delete("messages", "message_id = ?", arrayOf(it.id.toString())) }
            }
        }
    }

    fun clearEditsAsync() = enqueue("clear edit history") {
        transaction(db()) {
            db().delete("message_edits", null, null)
            db().execSQL("UPDATE messages SET edits = ''")
            db().delete("messages", "deleted = 0", null)
        }
    }

    fun messageIdsAsync(done: (Result<Set<Long>>) -> Unit) = enqueue("list saved messages", done) {
        db().rawQuery("SELECT message_id FROM messages", null).use { cursor ->
            val result = HashSet<Long>()
            while (cursor.moveToNext()) result.add(cursor.getLong(0))
            result
        }
    }

    fun clearAsync(done: (Result<Unit>) -> Unit) = enqueue("clear saved logs", done) {
        db().delete("messages", null, null)
        // Reclaim disk space and remove deleted content from reusable SQLite pages.
        db().execSQL("VACUUM")
        checkpoint()
    }

    data class Statistics(
        val messages: Long,
        val edits: Long,
        val bytes: Long,
    )

    fun statisticsAsync(done: (Result<Statistics>) -> Unit) = enqueue("read storage statistics", done) {
        fun count(table: String) = db().rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst()
            it.getLong(0)
        }
        Statistics(count("messages"), count("message_edits"), file.length() + File(file.path + "-wal").length())
    }

    fun exportDatabaseAsync(output: File, done: (Result<File>) -> Unit) = enqueue("export database", done) {
        if (output.canonicalFile == file.canonicalFile) return@enqueue output
        checkpoint()
        atomicExport(output) { stream -> file.inputStream().use { it.copyTo(stream) } }
        output
    }

    fun exportTextAsync(output: File, done: (Result<File>) -> Unit) = enqueue("export text", done) {
        atomicExport(output) { stream ->
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            writer.write("BetterMessageLogger export\n\n")
            db().rawQuery("SELECT * FROM messages ORDER BY timestamp, message_id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = readRecord(cursor)
                    writer.write("Message ID: ${record.id}\nChannel ID: ${record.channelId}\n")
                    writer.write("Server ID: ${record.guildId ?: "DM"}\n")
                    writer.write("Author: ${record.authorName} (${record.authorId})\n")
                    writer.write("Date: ${format.format(Date(record.timestamp))}\n")
                    writer.write("Status: ${if (record.deleted) "DELETED" else "EDITED"}\n")
                    record.deletedTimestamp?.let { writer.write("Deleted: ${format.format(Date(it))}\n") }
                    writer.write("Content:\n${record.content}\n")
                    db()
                        .rawQuery(
                            "SELECT edit_timestamp, content FROM message_edits WHERE message_id = ? ORDER BY position",
                            arrayOf(record.id.toString()),
                        ).use { history ->
                            var first = true
                            while (history.moveToNext()) {
                                if (first) writer.write("Edit history:\n")
                                first = false
                                writer.write("${format.format(Date(history.getLong(0)))}:\n${history.getString(1)}\n")
                            }
                        }
                    writer.write("\n")
                }
            }
            writer.flush()
        }
        output
    }

    private fun checkpoint() {
        db().rawQuery("PRAGMA journal_mode", null).use { mode ->
            if (!mode.moveToFirst() || !mode.getString(0).equals("wal", true)) return
        }
        db().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
            check(it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == it.getInt(2)) {
                "Database is busy; try exporting again"
            }
        }
    }

    private fun atomicExport(output: File, write: (FileOutputStream) -> Unit) {
        val parent = requireNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create export folder" }
        val temporary = File.createTempFile(".${output.name}.", ".part", parent)
        try {
            FileOutputStream(temporary).use {
                write(it)
                it.fd.sync()
            }
            check(temporary.renameTo(output)) { "Could not finish export" }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        // Closing runs after every accepted write/export. Never discard queued writes on a timeout.
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

    @Synchronized
    private fun <T> enqueue(action: String, done: ((Result<T>) -> Unit)? = null, work: () -> T) {
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

    private fun transaction(db: SQLiteDatabase, work: () -> Unit) {
        db.beginTransaction()
        try {
            work()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val result = HashSet<String>()
            while (cursor.moveToNext()) result.add(cursor.getString(1))
            result
        }

    private fun readRecord(cursor: Cursor) = MessageRecord(
        cursor.long("message_id")!!,
        cursor.long("channel_id")!!,
        cursor.long("guild_id"),
        cursor.long("author_id") ?: 0,
        cursor.string("author_name")?.takeIf { name -> name.any { !it.isWhitespace() } } ?: "Unknown user",
        cursor.string("author_avatar"),
        cursor.long("author_bot") == 1L,
        cursor.string("content").orEmpty(),
        cursor.long("timestamp")!!,
        cursor.long("edited_timestamp"),
        cursor.long("deleted") == 1L,
        cursor.long("deleted_timestamp"),
        payload = cursor.string("payload"),
    )

    private fun Cursor.long(name: String): Long? = getColumnIndex(name).let {
        if (it < 0 || isNull(it)) null else getLong(it)
    }

    private fun Cursor.string(name: String): String? = getColumnIndex(name).let {
        if (it < 0 || isNull(it)) null else getString(it)
    }

    companion object {
        private const val SCHEMA_VERSION = 2
    }
}
