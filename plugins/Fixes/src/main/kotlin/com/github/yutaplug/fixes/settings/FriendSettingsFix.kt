package com.github.yutaplug.fixes.settings

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.app.AppActivity
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.utilities.rest.RestAPI
import com.discord.views.CheckedSetting
import com.discord.widgets.settings.WidgetSettingsPrivacy
import com.github.yutaplug.fixes.FixPatcher
import org.json.JSONObject
import rx.subjects.SerializedSubject
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import d0.z.d.d as DiscordCallableReference

/** Bridges the legacy friend-request controls to the account's current settings. */
internal class FriendSettingsFix(private val log: (String, Throwable) -> Unit) {
    private class Session(val token: String) {
        val state = FriendRequestState()
        var nextWriteAt = 0L
        var scheduled = false
        var loadRequested = false
        var loading = false
        var status = "Loading current settings…"
    }

    private class HttpFailure(
        val status: Int,
        val retryAfter: Long,
        message: String,
    ) : Exception(message)

    private val lock = Any()
    private var session: Session? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "SettingsFixRequests") }
    private val main = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<WidgetSettingsPrivacy, List<CheckedSetting>>()

    @Volatile private var closed = false
    private var hookErrorShown = false

    fun start(patcher: FixPatcher) {
        patcher.patchRequired(
            StoreUserSettings::class.java,
            "setFriendSourceFlags",
            arrayOf(
                AppActivity::class.java,
                Boolean::class.javaObjectType,
                Boolean::class.javaObjectType,
                Boolean::class.javaObjectType,
            ),
            PreHook { frame ->
                if (frame.args[0] != null) {
                    val active = activeSession()
                    if (active != null) {
                        synchronized(lock) {
                            val base = active.state.displayed ?: 14
                            choose(
                                active,
                                SettingsProto.flags(
                                    frame.args[1] as Boolean? ?: (base and 8 != 0),
                                    frame.args[2] as Boolean? ?: (base and 4 != 0),
                                    frame.args[3] as Boolean? ?: (base and 2 != 0),
                                ),
                            )
                        }
                        render()
                    }
                }
                frame.result = null
            },
        )
        // Patch the public Function1 entry point. Private configure methods can
        // be inlined into their callers by ART and bypass a method hook.
        val modelCallback = WidgetSettingsPrivacy::class.java.classLoader!!.loadClass(
            "com.discord.widgets.settings.WidgetSettingsPrivacy\$onViewBoundOrOnResume\$1",
        )
        patcher.patchRequired(
            modelCallback,
            "invoke",
            arrayOf(Any::class.java),
            Hook { frame ->
                guardHook {
                    val widget = (frame.thisObject as DiscordCallableReference).boundReceiver as WidgetSettingsPrivacy
                    val model = frame.args[0] as WidgetSettingsPrivacy.Model
                    bindPage(widget, model.friendSourceFlags?.let(::flags) ?: 0)
                }
            },
        )
        patcher.patchRequired(
            WidgetSettingsPrivacy::class.java,
            "onViewBoundOrOnResume",
            hook = Hook { frame ->
                guardHook {
                    // Also bind directly on page entry, even before the first
                    // observable model has arrived or the callback is compiled.
                    bindPage(frame.thisObject as WidgetSettingsPrivacy, 0)
                    activeSession()?.let { refresh(it, force = true) }
                }
            },
        )
        patcher.patchRequired(
            ModelUserSettings::class.java,
            "getFriendSourceFlags",
            hook = PreHook { frame ->
                val actual = activeSession()?.let { synchronized(lock) { it.state.confirmed } }
                if (actual != null) frame.result = model(actual)
            },
        )
    }

    private fun guardHook(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            log("Could not bind friend-request controls", error)
            if (!hookErrorShown) {
                hookErrorShown = true
                Utils.showToast("SettingsFix controls failed: ${reason(error)}", true)
            }
        }
    }

    private fun bindPage(widget: WidgetSettingsPrivacy, fallback: Int) {
        if (widget.view == null) return
        // These are Discord's actual view bindings, avoiding resource-name
        // lookups that can fail after resource patching by another plugin.
        val binding = WidgetSettingsPrivacy.`access$getBinding$p`(widget)
        val radios = listOf(binding.r, binding.s, binding.t)
        views[widget] = radios
        bind(radios, fallback)
    }

    private fun activeSession(): Session? {
        val auth = token()?.takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) {
            session?.takeIf { it.token == auth } ?: Session(auth).also { session = it }
        }
    }

    private fun bind(radios: List<CheckedSetting>, fallback: Int) {
        val active = activeSession()
        if (active == null) {
            radios.forEach { radio ->
                radio.setSubtext("Unable to load account authentication.")
                radio.setOnCheckedListener {
                    Utils.showToast("SettingsFix cannot access the signed-in account.", true)
                }
            }
            return
        }
        // A model callback can run without the lifecycle hook running (for
        // example when its caller was inlined). Start the read from either path.
        refresh(active)
        val display = synchronized(lock) {
            (active.state.displayed ?: fallback) to if (active.state.isPending) "Saving…" else active.status
        }
        radios.forEachIndexed { index, radio ->
            val bit = when (index) {
                0 -> 8
                1 -> 2
                else -> 4
            }
            radio.isChecked = display.first and bit != 0
            radio.setSubtext(display.second.takeIf { it.isNotEmpty() })
            radio.setOnCheckedListener { checked ->
                var retry = false
                synchronized(lock) {
                    val base = active.state.displayed
                    if (session !== active) {
                        Utils.showToast("Reopen Privacy & Safety for the signed-in account.", true)
                    } else if (base == null && !active.loading) {
                        retry = true
                    } else if (base == null) {
                        Utils.showToast("Wait for current friend-request settings to load.", true)
                    } else {
                        choose(active, FriendRequestState.toggle(base, index, checked))
                    }
                }
                if (retry) refresh(active, force = true)
                // CheckedSetting updates only the tapped row. Redraw dependent rows
                // immediately, and protect the choice from old model emissions.
                render()
            }
        }
    }

    private fun choose(active: Session, flags: Int) {
        active.state.select(flags)
        active.status = ""
        schedule(active)
    }

    private fun schedule(active: Session) {
        if (closed || active.scheduled || active.state.saving != null || active.state.queued == null) return
        active.scheduled = true
        val delay = maxOf(350L, active.nextWriteAt - now())
        try {
            worker.schedule({
                val selection = synchronized(lock) {
                    active.scheduled = false
                    if (!isActive(active)) {
                        null
                    } else {
                        active.state.beginSave()?.also {
                            active.nextWriteAt = now() + 10_000L
                        }
                    }
                } ?: return@schedule
                save(active, selection)
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            active.scheduled = false
        }
    }

    private fun refresh(active: Session, force: Boolean = false) {
        synchronized(lock) {
            if (closed || session !== active || active.loading || (!force && active.loadRequested)) return
            active.loadRequested = true
            active.loading = true
            if (!active.state.isPending) active.status = "Loading current settings…"
        }
        try {
            worker.execute {
                try {
                    if (!isActive(active)) return@execute
                    val actual = SettingsProto.friendFlags(request(active))
                    synchronized(lock) {
                        if (!isActive(active)) return@execute
                        active.state.read(actual)
                        if (!active.state.isPending) active.status = ""
                    }
                    publish(active, actual)
                } catch (error: Throwable) {
                    // Executor tasks also swallow LinkageError (e.g. an API
                    // absent from an older Aliucord build), not just Exception.
                    if (isActive(active)) {
                        log("Could not load friend-request settings", error)
                        synchronized(lock) { active.status = "Load failed: ${reason(error)}. Tap to retry." }
                        Utils.showToast("SettingsFix: ${reason(error)}", true)
                    }
                } finally {
                    synchronized(lock) { active.loading = false }
                    render()
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { active.loading = false }
        }
    }

    private fun save(active: Session, selection: FriendRequestState.Selection) {
        var actual: Int? = null
        var failure: Throwable? = null
        try {
            var current = request(active)
            var attempt = 0
            while (attempt <= 1) {
                val desired = (SettingsProto.friendFlags(current) and 14.inv()) or selection.flags
                val payload = JSONObject()
                    .put("settings", Base64.encodeToString(SettingsProto.friendPatch(current, desired), Base64.NO_WRAP))
                    .put("required_data_version", SettingsProto.dataVersion(current))
                check(isActive(active)) { "Account changed before saving" }
                val response = requestJson(active, payload)
                current = decode(response)
                if (response.optBoolean("out_of_date")) {
                    check(attempt == 0) { "Privacy settings changed concurrently; try again" }
                    attempt++
                    continue
                }
                actual = SettingsProto.friendFlags(request(active))
                check(actual and 14 == selection.flags) { "Discord kept a different friend-request selection" }
                break
            }
        } catch (error: Throwable) {
            failure = error
            if (isActive(active)) {
                log("Could not save friend-request settings", error)
                // Reconcile ambiguous failures (e.g. a read timed out after PATCH).
                if (actual == null && error !is HttpFailure) {
                    actual = runCatching { SettingsProto.friendFlags(request(active)) }.getOrNull()
                }
            }
        }
        if (!isActive(active)) return
        synchronized(lock) {
            active.state.complete(selection, actual)
            active.status = if (failure == null) "" else "Save failed: ${reason(failure)}"
            if (failure is HttpFailure && failure.retryAfter > 0) {
                active.nextWriteAt = maxOf(active.nextWriteAt, now() + failure.retryAfter)
            }
            schedule(active)
        }
        actual?.let { publish(active, it) }
        render()
        if (failure != null) {
            Utils.showToast("SettingsFix could not save friend requests: ${reason(failure)}", true)
        } else if (synchronized(lock) { !active.state.isPending }) {
            Utils.showToast("Friend-request settings saved.", false)
        }
    }

    private fun request(active: Session): ByteArray = decode(requestJson(active))

    private fun requestJson(active: Session, payload: JSONObject? = null): JSONObject {
        check(isActive(active)) { "Account changed before request" }
        val route = "/users/@me/settings-proto/1"
        val method = if (payload == null) "GET" else "PATCH"
        val request = try {
            Http.Request.newDiscordRNRequest(route, method)
        } catch (_: LinkageError) {
            // This helper was added after the original Discord request helper.
            // Keep reads and writes usable on cores that do not provide it.
            Http.Request.newDiscordRequest(route, method)
        }
        return request.use {
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", active.token)
            request.setHeader("Content-Type", "application/json")
            val response = if (payload == null) request.execute() else request.executeWithBody(payload.toString())
            response.use {
                if (!it.ok()) {
                    val errorBody = runCatching {
                        request.conn.errorStream?.bufferedReader()?.use { reader ->
                            val buffer = CharArray(2048)
                            val count = reader.read(buffer)
                            if (count > 0) JSONObject(String(buffer, 0, count)) else null
                        }
                    }.getOrNull()
                    val retry = errorBody?.optDouble("retry_after", 0.0) ?: 0.0
                    val detail = errorBody?.optString("message")?.take(120)?.takeIf(::hasText)
                    val retryMillis = if (retry.isFinite()) (maxOf(0.0, minOf(retry, 86400.0)) * 1000).toLong() else 0L
                    throw HttpFailure(
                        it.statusCode,
                        retryMillis,
                        "HTTP ${it.statusCode}${detail?.let { message -> ": $message" } ?: ""}",
                    )
                }
                JSONObject(it.text())
            }
        }
    }

    private fun decode(response: JSONObject): ByteArray = Base64.decode(response.getString("settings"), Base64.DEFAULT)

    private fun token(): String? {
        // The notification client's header provider can be unset even while
        // Discord's authentication store has a valid signed-in session.
        return StoreAuthentication
            .`access$getAuthState$p`(StoreStream.getAuthentication())
            ?.token
            ?.takeIf { it.isNotEmpty() }
            ?: RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf { it.isNotEmpty() }
    }

    private fun isActive(active: Session): Boolean =
        !closed && token() == active.token && synchronized(lock) { session === active }

    private fun reason(error: Throwable): String = error.message?.take(180) ?: error.javaClass.simpleName

    private fun hasText(value: String): Boolean {
        // Kotlin's isBlank() also iterates an IntRange internally. Its standard
        // IntIterator cannot consume Discord's renamed progression iterator.
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun publish(active: Session, flags: Int) {
        if (!isActive(active)) return
        val subject = ReflectUtils.getField(StoreStream.getUserSettings(), "friendSourceFlagsSubject")
            as SerializedSubject<ModelUserSettings.FriendSourceFlags, ModelUserSettings.FriendSourceFlags>
        // Only server values enter the store. Pending choices belong to the UI.
        subject.onNext(model(flags))
    }

    private fun render() {
        main.post {
            if (closed) return@post
            val iterator = views.entries.iterator()
            while (iterator.hasNext()) {
                val (widget, radios) = iterator.next()
                if (widget.view == null) iterator.remove() else guardHook { bind(radios, 0) }
            }
        }
    }

    private fun flags(value: ModelUserSettings.FriendSourceFlags): Int =
        SettingsProto.flags(value.isAll, value.isMutualGuilds, value.isMutualFriends)

    private fun model(flags: Int): ModelUserSettings.FriendSourceFlags = ModelUserSettings.FriendSourceFlags().apply {
        ReflectUtils.setField(this, "all", flags and 8 != 0)
        ReflectUtils.setField(this, "mutualGuilds", flags and 4 != 0)
        ReflectUtils.setField(this, "mutualFriends", flags and 2 != 0)
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        // Restore Discord's handlers on a page that is already open.
        views.forEach { (widget, radios) ->
            val actual = synchronized(lock) { session?.state?.confirmed }
            radios.forEachIndexed { index, radio ->
                if (actual != null) {
                    radio.isChecked = actual and (
                        when (index) {
                            0 -> 8
                            1 -> 2
                            else -> 4
                        }
                    ) != 0
                }
                radio.setSubtext(null)
                radio.setOnCheckedListener { checked ->
                    WidgetSettingsPrivacy.`access$updateFriendSourceFlags`(widget, index, checked)
                }
            }
        }
        views.clear()
        synchronized(lock) { session = null }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L
}
