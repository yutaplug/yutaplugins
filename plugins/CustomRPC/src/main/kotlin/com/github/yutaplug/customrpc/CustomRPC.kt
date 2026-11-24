package com.github.yutaplug.customrpc

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityMetadata
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityTimestamps
import com.discord.api.activity.ActivityType
import com.discord.app.AppActivity
import com.discord.models.domain.ModelPayload
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import com.discord.utilities.icon.IconUtils
import java.util.UUID

@AliucordPlugin
class CustomRPC : Plugin() {
    @Volatile private var running = false

    // Only touched on Discord's store thread.
    private var owned: Activity? = null
    private var applying = false
    private val displaced = HashMap<ActivityType, Activity?>()

    /** Set when the settings page saves, so image errors from that save are shown once. */
    @Volatile private var reportImageErrors = false

    internal val images = ImageResolver(logger) { errors -> onImagesResolved(errors) }
    private val wake = Runnable { refresh() }

    init {
        settingsTab = SettingsTab(CustomRPCSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    internal fun config() = RpcConfig(settings)

    internal fun text(key: String) = settings.getString(key, "")?.trim().orEmpty()

    override fun start(context: Context) {
        running = true
        migrateSettings()

        // While CustomRPC owns an activity type, keep other sources (Spotify, game detection) from replacing it.
        // What they set is remembered and restored when CustomRPC is turned off or switches type.
        patcher.patch(
            StoreUserPresence::class.java.getDeclaredMethod(
                "updateActivity",
                ActivityType::class.java,
                Activity::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            PreHook { frame ->
                if (applying) return@PreHook
                val active = owned ?: return@PreHook
                val type = frame.args[0] as? ActivityType ?: return@PreHook
                if (type != active.p()) return@PreHook
                displaced[type] = frame.args[1] as? Activity
                frame.result = null
            },
        )

        // A new gateway session starts without any activity, and Discord only resends presence when it changes.
        patcher.patch(
            StoreUserPresence::class.java.getDeclaredMethod("handleConnectionOpen", ModelPayload::class.java),
            Hook { frame ->
                val active = owned ?: return@Hook
                val store = frame.thisObject as StoreUserPresence
                applying = true
                try {
                    store.updateActivity(active.p(), active, true)
                } finally {
                    applying = false
                }
                Utils.mainThread.post { refresh() }
            },
        )

        refresh()
    }

    override fun stop(context: Context) {
        running = false
        Utils.mainThread.removeCallbacks(wake)
        patcher.unpatchAll()
        images.clear()
        refresh()
    }

    private fun migrateSettings() {
        for ((old, new) in listOf(
            Keys.OLD_LARGE_IMAGE_URL to Keys.LARGE_IMAGE,
            Keys.OLD_SMALL_IMAGE_URL to Keys.SMALL_IMAGE,
        )) {
            if (!settings.exists(old)) continue
            val url = settings.getString(old, "")?.trim().orEmpty()
            if (url.isNotEmpty()) settings.setString(new, url)
            settings.remove(old)
        }
        for (key in Keys.REMOVED) if (settings.exists(key)) settings.remove(key)
        if (!settings.exists(Keys.STARTED_AT)) settings.setLong(Keys.STARTED_AT, System.currentTimeMillis())
        // A random party ID, so Discord doesn't group different CustomRPC users into one party.
        if (text(Keys.PARTY_ID).isEmpty()) settings.setString(Keys.PARTY_ID, "customrpc-" + UUID.randomUUID())
    }

    internal fun setEnabled(enabled: Boolean, host: AppActivity?) {
        if (enabled == settings.getBool(Keys.ENABLED, false)) return
        if (enabled) {
            settings.setLong(Keys.STARTED_AT, System.currentTimeMillis())
            // Make sure Discord's "Display current activity" privacy setting is on, or nobody sees the activity.
            try {
                StoreStream.getUserSettings().setIsShowCurrentGameEnabled(host ?: Utils.appActivity, true)
            } catch (e: Throwable) {
                logger.error("Failed to turn on activity sharing", e)
            }
        }
        settings.setBool(Keys.ENABLED, enabled)
        refresh()
    }

    /** Called on the main thread whenever the preview card should be redrawn. Set by the settings page. */
    internal var onPreviewChanged: (() -> Unit)? = null

    internal fun setText(key: String, value: String) = edit { settings.setString(key, value.trim()) }

    internal fun setType(type: ActivityType, streamUrl: String? = null) = edit {
        settings.setString(Keys.TYPE, type.name)
        if (streamUrl != null) settings.setString(Keys.STREAM_URL, streamUrl.trim())
    }

    /** [duration] restarts the custom elapsed/remaining timer from now. */
    internal fun setTimestamp(mode: TimestampMode, durationText: String? = null) = edit {
        settings.setString(Keys.TIMESTAMP_MODE, mode.name)
        val duration = durationText?.let { Validation.parseDuration(it) }
        if (mode.needsDuration && duration != null) {
            settings.setString(Keys.TIMESTAMP_DURATION, durationText.trim())
            val now = System.currentTimeMillis()
            settings.setLong(Keys.TIMESTAMP_ANCHOR, if (mode == TimestampMode.ELAPSED) now - duration else now + duration)
        }
    }

    /** Applies a settings change right away and updates the presence and the preview. */
    internal fun edit(block: () -> Unit) {
        block()
        images.forgetErrors()
        reportImageErrors = true
        refresh()
        onPreviewChanged?.invoke()
    }

    /** What the activity looks like with the current settings, whether or not it's shown. */
    internal fun preview(): Activity = build(config())

    /** A URL the preview card can load for an image field: the resolved asset, or the raw link while resolving. */
    internal fun previewImage(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val appId = config().applicationId ?: return null
        images.path(appId, v)?.let { return IconUtils.INSTANCE.getAssetImage(appId, it, 160) }
        val url = if (v.startsWith("www.")) "https://$v" else v
        return url.takeIf { Validation.isWebUrl(it) }
    }

    /** Re-reads the settings, resolves images that need it and updates the presence. */
    internal fun refresh() {
        Utils.mainThread.removeCallbacks(wake)
        val config = config()
        val active = running && config.enabled
        val appId = config.applicationId
        // Images are resolved even while hidden, so the settings preview can show them.
        val requested = running && appId != null && images.request(appId, config.imageValues)
        // Nothing to wait for, so there are no new errors to report.
        if (!requested) reportImageErrors = false

        StoreStream.getDispatcherYesThisIsIntentional().schedule {
            try {
                val next = if (running) config().takeIf { it.enabled }?.let { build(it) } else null
                apply(next)
            } catch (e: Exception) {
                logger.error("Failed to update the CustomRPC activity", e)
            }
        }
        if (active) scheduleWake(config)
    }

    private fun scheduleWake(config: RpcConfig) {
        val now = System.currentTimeMillis()
        var next = config.applicationId?.let { images.nextWake(it, config.imageValues) } ?: Long.MAX_VALUE
        if (config.timestampMode == TimestampMode.REMAINING && config.timestampAnchor > now) {
            next = minOf(next, config.timestampAnchor + 1000)
        }
        if (next == Long.MAX_VALUE) return
        Utils.mainThread.postDelayed(wake, (next - now).coerceIn(5_000L, 6 * 60 * 60 * 1000L))
    }

    private fun onImagesResolved(errors: Map<String, String>) {
        if (!running) return
        if (reportImageErrors) {
            reportImageErrors = false
            val config = config()
            val messages = ArrayList<String>()
            config.largeImage?.let { v -> errors[v]?.let { messages += "Large image: $it" } }
            config.smallImage?.let { v -> errors[v]?.let { messages += "Small image: $it" } }
            if (messages.isNotEmpty()) Utils.showToast(messages.joinToString("\n"), true)
        }
        refresh()
        onPreviewChanged?.invoke()
    }

    /** The error for an image field, if resolving it failed. */
    internal fun imageError(value: String): String? {
        val appId = config().applicationId ?: return null
        return value.trim().takeIf { it.isNotEmpty() }?.let { images.error(appId, it) }
    }

    // Runs on the store thread.
    private fun apply(next: Activity?) {
        val store = StoreStream.getPresences()
        val previous = owned
        applying = true
        try {
            if (previous != null && previous.p() != next?.p()) {
                store.updateActivity(previous.p(), displaced.remove(previous.p()), true)
            }
            if (next != null) {
                if (previous?.p() != next.p()) {
                    displaced[next.p()] = store.`getLocalPresence$app_productionGoogleRelease`()
                        .activities
                        ?.firstOrNull { it.p() == next.p() }
                }
                store.updateActivity(next.p(), next, false)
            }
            owned = next
        } finally {
            applying = false
        }
    }

    private fun build(config: RpcConfig): Activity {
        val appId = config.applicationId
        val large = if (appId != null) config.largeImage?.let { images.path(appId, it) } else null
        // Discord only shows the small image on top of a large one.
        val small = if (appId != null && large != null) config.smallImage?.let { images.path(appId, it) } else null
        val assets = if (large != null) ActivityAssets(large, config.largeText, small, config.smallText.takeIf { small != null }) else null

        val now = System.currentTimeMillis()
        val timestamps = when (config.timestampMode) {
            TimestampMode.SINCE_ENABLED -> ActivityTimestamps(config.startedAt.toString(), null)
            TimestampMode.ELAPSED -> config.timestampAnchor.takeIf { it > 0 }?.let { ActivityTimestamps(it.toString(), null) }
            TimestampMode.REMAINING -> config.timestampAnchor.takeIf { it > now }?.let { ActivityTimestamps(null, it.toString()) }
            TimestampMode.OFF -> null
        }

        // Buttons need an application: other clients fetch the button links through it.
        val buttons = if (appId != null) config.buttons else emptyList()

        // A party shows "(2 of 4)" after the state.
        val party = config.partySizes?.let { ActivityParty(config.partyId ?: "customrpc", it) }

        val activity = Activity(
            config.name,
            config.type,
            null,
            config.startedAt,
            timestamps,
            appId,
            config.details,
            config.state,
            null,
            party,
            assets,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )
        // Discord's constructor drops these, so set the fields directly. Gson sends them as url, buttons and
        // metadata.button_urls.
        config.streamUrl?.let { setField(activity, "url", it) }
        if (buttons.isNotEmpty()) {
            setField(activity, "buttons", buttons.map { it.label })
            setField(activity, "metadata", metadata(buttons.map { it.url }))
        }
        return activity
    }

    private fun setField(target: Any, name: String, value: Any?) {
        try {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        } catch (e: Exception) {
            logger.error("Failed to set Activity.$name", e)
        }
    }

    private fun metadata(buttonUrls: List<String>): ActivityMetadata? {
        val metadata = newInstance(ActivityMetadata::class.java) as? ActivityMetadata ?: return null
        setField(metadata, "buttonUrls", buttonUrls)
        return metadata
    }

    private fun newInstance(clazz: Class<*>): Any? {
        for (constructor in clazz.declaredConstructors) {
            try {
                constructor.isAccessible = true
                val args = constructor.parameterTypes.map { defaultValue(it) }.toTypedArray()
                return constructor.newInstance(*args)
            } catch (_: Throwable) {
            }
        }
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)
        } catch (e: Throwable) {
            logger.error("Failed to create ${clazz.simpleName}", e)
            null
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> Character.MIN_VALUE
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
