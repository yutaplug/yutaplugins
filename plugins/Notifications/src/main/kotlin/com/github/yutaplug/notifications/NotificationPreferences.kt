package com.github.yutaplug.notifications

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.aliucord.Http
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Main-thread state, with serialized network requests and account-scoped callbacks. */
internal class NotificationPreferences {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pages = java.util.Collections.newSetFromMap(WeakHashMap<NotificationsPage, Boolean>())
    private var account: String? = null
    private var generation = 0
    private var confirmed: Map<NotificationOption, Boolean>? = null
    private val pending = linkedMapOf<NotificationOption, Boolean>()
    private var saving = emptyMap<NotificationOption, Boolean>()
    private var busy = false
    private var nextWriteAt = 0L
    private var status = "Loading notification settings..."

    @Volatile private var closed = false
    private val flush = Runnable { save() }

    fun attach(page: NotificationsPage) {
        pages.add(page)
        if (closed) {
            page.close()
            return
        }
        refresh()
    }

    fun detach(page: NotificationsPage) {
        pages.remove(page)
    }

    private fun checkAccount(): String? {
        val current = token()
        if (current != account) {
            generation++
            account = current
            confirmed = null
            pending.clear()
            saving = emptyMap()
            busy = false
            nextWriteAt = 0L
            main.removeCallbacks(flush)
        }
        return current
    }

    fun refresh() {
        if (closed) return
        val auth = checkAccount()
        if (auth == null) {
            status = "Sign in to Discord to load notification settings. Tap to retry."
            render()
            return
        }
        if (busy || pending.isNotEmpty()) {
            render()
            return
        }
        busy = true
        status = "Loading notification settings..."
        render()
        val requestGeneration = generation
        worker.execute {
            val result = runCatching { NotificationApi(auth) { !closed }.read() }
            main.post {
                if (!active(auth, requestGeneration)) return@post
                busy = false
                result
                    .onSuccess {
                        confirmed = NotificationProto.values(it)
                        status = ""
                    }.onFailure { status = "Could not load settings: ${reason(it)}. Tap to retry." }
                render()
                schedule()
            }
        }
    }

    fun choose(option: NotificationOption, enabled: Boolean) {
        if (closed) return
        val auth = checkAccount()
        if (auth == null || confirmed == null) {
            refresh()
            return
        }
        pending[option] = enabled
        if (option.scalarValue != null) {
            for (other in NotificationOption.reactions()) if (other !== option) pending.remove(other)
        }
        // Saving is silent; the status row only reports loading and failures.
        status = ""
        render()
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(flush)
        if (closed || busy || pending.isEmpty()) return
        main.postDelayed(flush, maxOf(600L, nextWriteAt - SystemClock.elapsedRealtime()))
    }

    private fun save() {
        if (closed || busy || pending.isEmpty()) return
        val auth = checkAccount()
        if (auth == null || pending.isEmpty()) {
            refresh()
            return
        }
        val changes = pending.toMap()
        pending.clear()
        saving = changes
        busy = true
        status = ""
        nextWriteAt = SystemClock.elapsedRealtime() + 10_000L
        val requestGeneration = generation
        render()
        worker.execute {
            val result = runCatching { NotificationApi(auth) { !closed }.save(changes) }
            main.post {
                if (!active(auth, requestGeneration)) return@post
                busy = false
                saving = emptyMap()
                result
                    .onSuccess {
                        confirmed = NotificationProto.values(it)
                        status = ""
                    }.onFailure { failure ->
                        // Discard unsaved choices and disable switches. A fresh read
                        // reconciles an ambiguous PATCH failure when the user taps retry.
                        confirmed = null
                        pending.clear()
                        if (failure is NotificationFailure) {
                            nextWriteAt = maxOf(nextWriteAt, SystemClock.elapsedRealtime() + failure.retryAfter)
                        }
                        status = "Could not save settings: ${reason(failure)}. Tap to reload."
                    }
                render()
                schedule()
            }
        }
    }

    private fun active(auth: String, expectedGeneration: Int): Boolean {
        if (closed) return false
        if (token() != account) {
            refresh()
            return false
        }
        return account == auth && generation == expectedGeneration
    }

    private fun render() {
        val values = confirmed?.toMutableMap()?.apply {
            putAll(saving)
            putAll(pending)
            val selected = pending.keys.firstOrNull { it.scalarValue != null }
                ?: saving.keys.firstOrNull { it.scalarValue != null }
                ?: NotificationOption.reactions().firstOrNull { get(it) == true }
            for (option in NotificationOption.reactions()) put(option, option === selected)
        }
        pages.toList().forEach { it.render(values, status) }
    }

    fun close() {
        closed = true
        generation++
        main.removeCallbacks(flush)
        worker.shutdownNow()
        pages.toList().forEach { it.close() }
        pages.clear()
        pending.clear()
        confirmed = null
        account = null
    }

    private fun reason(error: Throwable): String = error.message?.take(150) ?: error.javaClass.simpleName
}

internal class NotificationFailure(val retryAfter: Long, message: String) : Exception(message)

internal class NotificationApi(private val auth: String, private val enabled: () -> Boolean) {
    // Validate on the worker thread so malformed responses become page errors.
    fun read(): ByteArray = decode(request()).also { NotificationProto.values(it) }

    fun save(changes: Map<NotificationOption, Boolean>): ByteArray {
        var current = read()
        var attempt = 0
        while (attempt < 2) {
            val response = request(
                JSONObject()
                    .put("settings", Base64.encodeToString(NotificationProto.patch(current, changes), Base64.NO_WRAP))
                    .put("required_data_version", NotificationProto.dataVersion(current)),
            )
            if (response.optBoolean("out_of_date")) {
                current = read()
                attempt++
                continue
            }
            val actual = read()
            val values = NotificationProto.values(actual)
            check(changes.all { (option, choice) -> values[option] == choice }) {
                "Discord did not keep the selected preferences"
            }
            return actual
        }
        error("Settings changed on another client; please retry")
    }

    internal fun request(payload: JSONObject? = null, route: String = "/users/@me/settings-proto/1"): JSONObject {
        check(enabled() && token() == auth) { "Account changed or plugin disabled" }
        val method = if (payload == null) "GET" else "PATCH"
        val request = try {
            Http.Request.newDiscordRNRequest(route, method)
        } catch (_: LinkageError) {
            Http.Request.newDiscordRequest(route, method)
        }
        return request.use {
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", auth)
            request.setHeader("Content-Type", "application/json")
            val response = if (payload == null) request.execute() else request.executeWithBody(payload.toString())
            response.use {
                if (!it.ok()) {
                    val body = runCatching {
                        request.conn.errorStream?.bufferedReader()?.use { reader ->
                            val buffer = CharArray(2048)
                            val count = reader.read(buffer)
                            if (count > 0) JSONObject(String(buffer, 0, count)) else null
                        }
                    }.getOrNull()
                    val seconds = body?.optDouble("retry_after", 0.0) ?: 0.0
                    val delay = if (seconds.isFinite()) (seconds.coerceIn(0.0, 86400.0) * 1000).toLong() else 0L
                    throw NotificationFailure(delay, "HTTP ${it.statusCode}")
                }
                JSONObject(it.text())
            }
        }
    }

    private fun decode(response: JSONObject): ByteArray = Base64.decode(response.getString("settings"), Base64.DEFAULT)
}

internal fun token(): String? =
    StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token?.takeIf { it.isNotEmpty() }
        ?: RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf { it.isNotEmpty() }
