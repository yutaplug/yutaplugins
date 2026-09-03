package com.github.yutaplug.bettermessagelogger;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class MessageLoggerDatabase {
    interface RecordsCallback {
        void onRecords(List<BetterMessageLogger.MessageRecord> records);
    }

    private static final String TABLE = "messages";
    private final File file;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BetterMessageLogger-Database");
        thread.setDaemon(true);
        return thread;
    });
    private SQLiteDatabase database;

    MessageLoggerDatabase(File file) {
        this.file = file;
    }

    synchronized void open() {
        if (database != null && database.isOpen()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        database = SQLiteDatabase.openOrCreateDatabase(file, null);
        // PRAGMA journal_mode returns a result row on Android and cannot be run with execSQL.
        try (Cursor ignored = database.rawQuery("PRAGMA journal_mode=WAL", null)) {
            ignored.moveToFirst();
        }
        try (Cursor ignored = database.rawQuery("PRAGMA synchronous=NORMAL", null)) {
            ignored.moveToFirst();
        }
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "message_id INTEGER PRIMARY KEY, channel_id INTEGER NOT NULL, guild_id INTEGER, "
                + "author_id INTEGER NOT NULL, author_name TEXT NOT NULL, author_avatar TEXT, author_bot INTEGER NOT NULL, "
                + "content TEXT NOT NULL, timestamp INTEGER NOT NULL, edited_timestamp INTEGER, "
                + "deleted INTEGER NOT NULL DEFAULT 0, deleted_timestamp INTEGER, edits TEXT NOT NULL DEFAULT '')");
        try {
            database.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN author_avatar TEXT");
        } catch (Throwable ignored) {
            // Existing databases already contain this column.
        }
        database.execSQL("CREATE INDEX IF NOT EXISTS messages_channel_idx ON messages(channel_id)");
        database.execSQL("CREATE INDEX IF NOT EXISTS messages_author_idx ON messages(author_id)");
    }

    void loadAllAsync(RecordsCallback callback) {
        executor.execute(() -> {
            List<BetterMessageLogger.MessageRecord> records = new ArrayList<>();
            synchronized (this) {
                if (database == null || !database.isOpen()) return;
                try (Cursor cursor = database.rawQuery("SELECT message_id, channel_id, guild_id, author_id, author_name, "
                        + "author_avatar, author_bot, content, timestamp, edited_timestamp, deleted, deleted_timestamp, edits FROM messages", null)) {
                    while (cursor.moveToNext()) records.add(fromCursor(cursor));
                } catch (Throwable ignored) {
                    // A corrupt/old row must not prevent the plugin from loading the remaining rows.
                }
            }
            callback.onRecords(records);
        });
    }

    void upsertAsync(BetterMessageLogger.MessageRecord record) {
        BetterMessageLogger.MessageRecord copy = record.copyWithoutRuntime();
        executor.execute(() -> {
            synchronized (this) {
                if (database == null || !database.isOpen()) return;
                database.execSQL("INSERT OR REPLACE INTO messages (message_id, channel_id, guild_id, author_id, "
                                + "author_name, author_avatar, author_bot, content, timestamp, edited_timestamp, deleted, "
                                + "deleted_timestamp, edits) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        args(copy));
            }
        });
    }

    void deleteByAuthorAsync(long authorId) {
        executor.execute(() -> {
            synchronized (this) {
                if (database != null && database.isOpen()) {
                    database.delete(TABLE, "author_id = ?", new String[]{String.valueOf(authorId)});
                }
            }
        });
    }

    void removeAsync(long messageId) {
        executor.execute(() -> {
            synchronized (this) {
                if (database != null && database.isOpen()) {
                    database.delete(TABLE, "message_id = ?", new String[]{String.valueOf(messageId)});
                }
            }
        });
    }

    void clearAsync() {
        executor.execute(() -> {
            synchronized (this) {
                if (database != null && database.isOpen()) database.delete(TABLE, null, null);
            }
        });
    }

    void exportAsync(File output, Runnable done) {
        executor.execute(() -> {
            try (FileWriter writer = new FileWriter(output, false)) {
                writer.write("BetterMessageLogger export\n\n");
                synchronized (this) {
                    if (database != null && database.isOpen()) {
                        try (Cursor cursor = database.rawQuery("SELECT message_id, channel_id, guild_id, author_id, "
                                + "author_name, author_avatar, author_bot, content, timestamp, edited_timestamp, deleted, "
                                + "deleted_timestamp, edits FROM messages WHERE deleted = 1 OR edits <> '' "
                                + "ORDER BY timestamp, message_id", null)) {
                            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
                            while (cursor.moveToNext()) writeRecord(writer, fromCursor(cursor), format);
                        }
                    }
                }
            } catch (IOException ignored) {
                // The caller reports completion only; failures are logged by the plugin action.
            }
            done.run();
        });
    }

    synchronized void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    void flushAndClose() {
        try {
            executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            // Closing is still preferable to keeping a stale database handle alive.
        }
        close();
    }

    void stop() {
        flushAndClose();
        executor.shutdownNow();
    }

    private BetterMessageLogger.MessageRecord fromCursor(Cursor cursor) {
        Long guildId = cursor.isNull(2) ? null : cursor.getLong(2);
        Long editedTimestamp = cursor.isNull(9) ? null : cursor.getLong(9);
        Long deletedTimestamp = cursor.isNull(11) ? null : cursor.getLong(11);
        return new BetterMessageLogger.MessageRecord(
                cursor.getLong(0), cursor.getLong(1), guildId, cursor.getLong(3), cursor.getString(4),
                cursor.getString(5), cursor.getInt(6) != 0, cursor.getString(7), cursor.getLong(8), editedTimestamp,
                cursor.getInt(10) != 0, deletedTimestamp, cursor.getString(12), null);
    }

    private Object[] args(BetterMessageLogger.MessageRecord record) {
        return new Object[]{record.id, record.channelId, record.guildId, record.authorId, record.authorName,
                record.authorAvatar, record.bot ? 1 : 0, record.content, record.timestamp, record.editedTimestamp,
                record.deleted ? 1 : 0, record.deletedTimestamp, record.edits};
    }

    private void writeRecord(FileWriter writer, BetterMessageLogger.MessageRecord record, DateFormat format)
            throws IOException {
        writer.write("Author: " + record.authorName + "\n");
        writer.write("Date: " + format.format(new Date(record.timestamp)) + "\n");
        writer.write("Content: " + record.content + "\n");
        writer.write("Status: " + (record.deleted ? "DELETED" : "EDITED") + "\n");
        if (!record.edits.isEmpty()) {
            writer.write("Edit history:\n");
            for (String edit : record.edits.split("\u001e")) {
                String[] parts = edit.split("\u001f", 2);
                if (parts.length != 2) continue;
                try {
                    writer.write(format.format(new Date(Long.parseLong(parts[0]))) + ": " + parts[1] + "\n");
                } catch (NumberFormatException ignored) {
                    writer.write(parts[1] + "\n");
                }
            }
        }
        writer.write("\n");
    }
}
