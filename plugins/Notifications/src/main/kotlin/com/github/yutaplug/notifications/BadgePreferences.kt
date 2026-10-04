package com.github.yutaplug.notifications

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.gateway.io.IncomingParser
import com.discord.models.domain.Model
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Badge flags are supplied in READY and NOTIFICATION_SETTINGS_UPDATE, not settings-proto. */
internal class BadgePreferences {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pages = java.util.Collections.newSetFromMap(WeakHashMap<NotificationsPage, Boolean>())
    private val snapshotReader = ThreadLocal<Model.JsonReader>()
    private val incoming = ThreadLocal<IncomingParser>()
    private var account: String? = null
    private var confirmed: Int? = null
    private val pending = linkedMapOf<Int, Boolean>()
    private var saving = emptyMap<Int, Boolean>()
    private var busy = false
    private var nextWriteAt = 0L
    private var status = "Restart Aliucord to load badge preferences from your account."

    @Volatile private var closed = false
    private val flush = Runnable { save() }

    fun start(patcher: PatcherAPI) {
        patcher.patch(
            IncomingParser::class.java,
            "assignField",
            arrayOf(Model.JsonReader::class.java),
            PreHook { frame ->
                incoming.set(frame.thisObject as IncomingParser)
            },
        )
        patcher.patch(
            IncomingParser::class.java,
            "assignField",
            arrayOf(Model.JsonReader::class.java),
            Hook {
                incoming.remove()
            },
        )
        patcher.patch(
            Model.JsonReader::class.java,
            "nextName",
            emptyArray(),
            Hook { frame ->
                val isUpdateData = frame.result == "d" &&
                    incoming.get()?.let {
                        ReflectUtils.getField(it, "type") == "NOTIFICATION_SETTINGS_UPDATE"
                    } == true
                if (frame.result == "notification_settings" || isUpdateData) {
                    snapshotReader.set(frame.thisObject as Model.JsonReader)
                }
            },
        )
        patcher.patch(
            Model.JsonReader::class.java,
            "skipValue",
            emptyArray(),
            PreHook { frame ->
                val reader = frame.thisObject as Model.JsonReader
                if (snapshotReader.get() !== reader) return@PreHook
                snapshotReader.remove()
                incoming.remove()
                val auth = token() ?: return@PreHook
                var flags: Int? = null
                reader.nextObject { name ->
                    if (name == "flags") flags = reader.nextInt(0) else reader.skipValue()
                }
                flags?.let { value -> main.post { receive(auth, value) } }
                frame.result = null
            },
        )
    }

    private fun receive(auth: String, flags: Int) {
        if (closed || token() != auth) return
        checkAccount()
        confirmed = flags
        status = ""
        render()
    }

    private fun checkAccount(): String? {
        val auth = token()
        if (auth != account) {
            account = auth
            confirmed = null
            pending.clear()
            saving = emptyMap()
            busy = false
            nextWriteAt = 0L
            status = "Restart Aliucord to load badge preferences from your account."
            main.removeCallbacks(flush)
        }
        return auth
    }

    fun attach(page: NotificationsPage) {
        pages.add(page)
        checkAccount()
        render()
    }

    fun detach(page: NotificationsPage) {
        pages.remove(page)
    }

    fun choose(bit: Int, enabled: Boolean) {
        checkAccount()
        if (closed || confirmed == null) {
            render()
            return
        }
        require(bit == 16 || bit == 32)
        pending[bit] = enabled
        status = ""
        render()
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(flush)
        if (!closed && !busy && pending.isNotEmpty()) {
            main.postDelayed(flush, maxOf(600L, nextWriteAt - SystemClock.elapsedRealtime()))
        }
    }

    private fun save() {
        if (closed || busy) return
        val auth = checkAccount() ?: return
        val base = confirmed ?: return
        if (pending.isEmpty()) return
        val choices = pending.toMap()
        pending.clear()
        saving = choices
        val flags = changedFlags(base, choices)
        busy = true
        nextWriteAt = SystemClock.elapsedRealtime() + 10_000L
        worker.execute {
            val result = runCatching {
                NotificationApi(auth) { !closed }
                    .request(
                        JSONObject().put("flags", flags),
                        "/users/@me/notification-settings",
                    ).getInt("flags")
                    .also { actual ->
                        check(actual and 48 == flags and 48) { "Discord did not keep the selected badge preferences" }
                    }
            }
            main.post {
                if (closed || token() != auth || account != auth) return@post
                busy = false
                saving = emptyMap()
                result
                    .onSuccess { actual ->
                        confirmed = actual
                        status = ""
                    }.onFailure {
                        pending.clear()
                        if (it is NotificationFailure) {
                            nextWriteAt = maxOf(nextWriteAt, SystemClock.elapsedRealtime() + it.retryAfter)
                        }
                        status = "Could not save badge preferences: ${it.message?.take(120)}. Try the switch again."
                    }
                render()
                schedule()
            }
        }
    }

    private fun render() {
        val flags = confirmed?.let { changedFlags(changedFlags(it, saving), pending) }
        pages.toList().forEach { it.renderBadges(flags, status) }
    }

    fun close() {
        closed = true
        main.removeCallbacks(flush)
        worker.shutdownNow()
        pages.clear()
        snapshotReader.remove()
        incoming.remove()
        account = null
        confirmed = null
    }

    companion object {
        fun changedFlags(flags: Int, choices: Map<Int, Boolean>): Int {
            var result = flags
            for ((bit, enabled) in choices) result = if (enabled) result or bit else result and bit.inv()
            return result
        }
    }
}
