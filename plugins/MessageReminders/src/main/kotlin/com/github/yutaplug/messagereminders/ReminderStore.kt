package com.github.yutaplug.messagereminders

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** One saved message with a reminder. [author] and [preview] come from the saved message payload, if any. */
internal data class Reminder(
    val channelId: Long,
    val messageId: Long,
    val guildId: Long,
    val dueAt: Long,
    val author: String?,
    val preview: String?,
) {
    val key get() = key(channelId, messageId)

    companion object {
        fun key(channelId: Long, messageId: Long) = "$channelId:$messageId"
    }
}

/**
 * Your reminders, kept in sync with Discord's saved messages API
 * (`/users/@me/saved-messages`, where a reminder is a saved message with a `due_at`).
 * Saved messages without a reminder (bookmarks) are left alone.
 */
internal class ReminderStore(private val logger: Logger, private val onChanged: () -> Unit) {
    private val reminders = ConcurrentHashMap<String, Reminder>()

    @Volatile var loaded = false
        private set

    @Volatile private var lastRefresh = 0L

    fun get(channelId: Long, messageId: Long): Reminder? = reminders[Reminder.key(channelId, messageId)]

    fun all(): List<Reminder> = reminders.values.sortedBy { it.dueAt }

    fun clear() {
        reminders.clear()
        loaded = false
        lastRefresh = 0L
    }

    /** Reloads the list unless it was loaded in the last [maxAgeMs]. Blocking; call off the main thread. */
    fun refresh(maxAgeMs: Long = 0L): Boolean {
        if (maxAgeMs > 0 && System.currentTimeMillis() - lastRefresh < maxAgeMs) return true
        return try {
            val body = request("/users/@me/saved-messages", "GET", null)
            val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
            val fresh = HashMap<String, Reminder>()
            for (index in 0 until results.length()) {
                parse(results.optJSONObject(index))?.let { fresh[it.key] = it }
            }
            reminders.keys.retainAll(fresh.keys)
            reminders.putAll(fresh)
            loaded = true
            lastRefresh = System.currentTimeMillis()
            onChanged()
            true
        } catch (error: Throwable) {
            logger.error("Failed to load your reminders", error)
            false
        }
    }

    /**
     * Creates or moves the reminder on a message. [fallback] fills in the author and preview
     * when Discord's response doesn't carry the message. Blocking; throws on failure.
     */
    fun set(channelId: Long, messageId: Long, guildId: Long, dueAt: Long, fallback: Reminder?) {
        val body = request(
            "/users/@me/saved-messages/$channelId/$messageId",
            "PUT",
            JSONObject().put("due_at", formatDate(dueAt)),
        )
        val parsed = try {
            parse(JSONObject(body))
        } catch (_: Throwable) {
            null
        }
        val reminder = Reminder(
            channelId,
            messageId,
            parsed?.guildId?.takeIf { it != 0L } ?: guildId,
            parsed?.dueAt?.takeIf { it > 0 } ?: dueAt,
            parsed?.author ?: fallback?.author,
            parsed?.preview ?: fallback?.preview,
        )
        reminders[reminder.key] = reminder
        onChanged()
    }

    /** Marks a reminder complete, which removes the saved message. Blocking; throws on failure. */
    fun complete(channelId: Long, messageId: Long) {
        request("/users/@me/saved-messages/$channelId/$messageId", "DELETE", null)
        removeLocal(channelId, messageId)
    }

    /** SAVED_MESSAGE_CREATE: a message was saved or its reminder changed, maybe on another client. */
    fun onSaved(payload: JSONObject) {
        val reminder = parse(payload)
        val saveData = payload.optJSONObject("save_data") ?: payload
        val channelId = saveData.optString("channel_id").toLongOrNull() ?: return
        val messageId = saveData.optString("message_id").toLongOrNull() ?: return
        val key = Reminder.key(channelId, messageId)
        if (reminder == null) {
            // Saved without a reminder: any reminder it had is gone.
            if (reminders.remove(key) != null) onChanged()
            return
        }
        val previous = reminders[key]
        reminders[key] = reminder.copy(
            author = reminder.author ?: previous?.author,
            preview = reminder.preview ?: previous?.preview,
        )
        onChanged()
    }

    /** SAVED_MESSAGE_DELETE */
    fun onDeleted(payload: JSONObject) {
        val channelId = payload.optString("channel_id").toLongOrNull() ?: return
        val messageId = payload.optString("message_id").toLongOrNull() ?: return
        removeLocal(channelId, messageId)
    }

    private fun removeLocal(channelId: Long, messageId: Long) {
        if (reminders.remove(Reminder.key(channelId, messageId)) != null) onChanged()
    }

    private fun parse(item: JSONObject?): Reminder? {
        item ?: return null
        val saveData = item.optJSONObject("save_data") ?: return null
        val dueAt = parseDate(saveData.optString("due_at").takeIf { !saveData.isNull("due_at") })
        if (dueAt <= 0L) return null
        val channelId = saveData.optString("channel_id").toLongOrNull() ?: return null
        val messageId = saveData.optString("message_id").toLongOrNull() ?: return null
        val guildId = saveData.optString("guild_id").toLongOrNull() ?: 0L
        val message = item.optJSONObject("message")
        val author = message?.optJSONObject("author")?.let { user ->
            user.optString("global_name").takeIf { !user.isNull("global_name") && it.isNotEmpty() }
                ?: user.optString("username").takeIf { it.isNotEmpty() }
        } ?: saveData.optString("author_summary").takeIf { it.isNotEmpty() }
        val preview = message?.let(::previewOf) ?: saveData.optString("message_summary").takeIf { it.isNotEmpty() }
        return Reminder(channelId, messageId, guildId, dueAt, author, preview)
    }

    private fun request(path: String, method: String, body: JSONObject?): String {
        Http.Request.newDiscordRNRequest(path, method).use { request ->
            request.setRequestTimeout(REQUEST_TIMEOUT)
            val response = if (body != null) {
                request.setHeader("Content-Type", "application/json").executeWithBody(body.toString())
            } else {
                request.execute()
            }
            response.use {
                if (it.ok()) return if (it.statusCode == 204) "" else it.text()
                // Response.text() refuses non-2xx responses, so read Discord's error body directly.
                val error = try {
                    request.conn.errorStream?.bufferedReader()?.use { reader -> reader.readText() }
                } catch (_: Throwable) {
                    null
                }
                logger.warn("$method $path failed with HTTP ${it.statusCode}: $error")
                val message = try {
                    JSONObject(error ?: "").optString("message").takeIf { text -> text.isNotEmpty() }
                } catch (_: Throwable) {
                    null
                }
                throw IllegalStateException(message ?: "HTTP ${it.statusCode}")
            }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT = 15_000

        /** A short single-line preview of a raw message object. */
        fun previewOf(message: JSONObject): String? {
            val content = message.optString("content").replace('\n', ' ').trim()
            if (content.isNotEmpty()) return content
            val attachments = message.optJSONArray("attachments")?.length() ?: 0
            if (attachments > 0) return if (attachments == 1) "Attachment" else "$attachments attachments"
            val embeds = message.optJSONArray("embeds")?.length() ?: 0
            if (embeds > 0) return "Embed"
            val stickers = message.optJSONArray("sticker_items")?.length() ?: 0
            if (stickers > 0) return "Sticker"
            return null
        }

        /** Parses Discord's ISO 8601 timestamps, e.g. `2025-01-01T09:00:00.000000+00:00`. */
        fun parseDate(value: String?): Long {
            if (value == null || value.length < 19) return 0L
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                format.timeZone = TimeZone.getTimeZone("UTC")
                var millis = format.parse(value.substring(0, 19))?.time ?: return 0L
                var rest = value.substring(19)
                if (rest.startsWith(".")) {
                    val digits = rest.drop(1).takeWhile { it.isDigit() }
                    millis += (digits + "000").take(3).toLong()
                    rest = rest.drop(1 + digits.length)
                }
                if (rest.length >= 6 && (rest[0] == '+' || rest[0] == '-')) {
                    val offset = (rest.substring(1, 3).toInt() * 60 + rest.substring(4, 6).toInt()) * 60_000L
                    millis -= if (rest[0] == '+') offset else -offset
                }
                millis
            } catch (_: Throwable) {
                0L
            }
        }

        fun formatDate(millis: Long): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(millis))
        }
    }
}
