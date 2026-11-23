package com.github.yutaplug.superreactions

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.utils.IOUtils
import com.discord.utilities.rest.RestAPI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The few REST calls Discord 126.21 has no API for. Requests run one at a time
 * off the main thread; results are delivered on the main thread.
 */
internal class BurstApi {
    class Result(val status: Int, val body: String) {
        val ok get() = status in 200..299
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "SuperReactions") }
    @Volatile private var closed = false

    fun addBurst(channelId: Long, messageId: Long, emojiKey: String, done: (Result) -> Unit) =
        send("${route(channelId, messageId, emojiKey)}/@me?location=Message%20Inline%20Button&type=1", "PUT", done)

    fun removeBurst(channelId: Long, messageId: Long, emojiKey: String, done: (Result) -> Unit) =
        send(
            "${route(channelId, messageId, emojiKey)}/1/@me?location=Message%20Inline%20Button&burst=true",
            "DELETE",
            done,
        )

    fun burstUsers(channelId: Long, messageId: Long, emojiKey: String, done: (Result) -> Unit) =
        send("${route(channelId, messageId, emojiKey)}?limit=100&type=1", "GET", done)

    fun messagesAround(channelId: Long, messageId: Long, done: (Result) -> Unit) =
        send("/channels/$channelId/messages?around=$messageId&limit=50", "GET", done)

    private fun route(channelId: Long, messageId: Long, emojiKey: String) =
        "/channels/$channelId/messages/$messageId/reactions/${Uri.encode(emojiKey)}"

    private fun send(route: String, method: String, done: (Result) -> Unit) {
        if (closed) return
        worker.execute {
            val result = try {
                execute(route, method)
            } catch (_: Throwable) {
                Result(0, "")
            }
            if (!closed) Utils.mainThread.post { if (!closed) done(result) }
        }
    }

    private fun execute(route: String, method: String): Result =
        Http.Request.newDiscordRNRequest(route, method).use { request ->
            request.setRequestTimeout(15_000)
            RestAPI.AppHeadersProvider.INSTANCE.fingerprint?.let { request.setHeader("X-Fingerprint", it) }
            val response = request.execute()
            val body = when {
                response.statusCode == 204 -> ""
                response.ok() -> response.text()
                // Response.text() asserts success; error bodies are read directly.
                else -> request.conn.errorStream?.use { IOUtils.readAsText(it) } ?: ""
            }
            Result(response.statusCode, body)
        }

    fun close() {
        closed = true
        worker.shutdownNow()
    }
}
