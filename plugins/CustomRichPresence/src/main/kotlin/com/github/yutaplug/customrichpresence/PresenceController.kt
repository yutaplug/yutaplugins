package com.github.yutaplug.customrichpresence

import android.os.Handler
import android.os.Looper
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.api.presence.ClientStatus
import com.discord.gateway.GatewaySocket
import com.discord.gateway.io.Outgoing
import com.discord.gateway.opcodes.Opcode
import com.discord.stores.StoreGatewayConnection
import com.discord.stores.StoreStream
import com.google.gson.Gson
import java.lang.reflect.Method
import java.util.Locale

internal object PresenceController {
    var settings: SettingsAPI? = null
    var logger: Logger? = null

    private var applied = false
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable {
        if (settings?.getBool(KEY_ENABLED, false) == true) apply()
    }

    fun startTimerIfNeeded() {
        val current = settings ?: return
        if (current.getLong(KEY_START_TIME, 0L) == 0L) {
            current.setLong(KEY_START_TIME, System.currentTimeMillis())
        }
    }

    fun apply(showErrors: Boolean = false) {
        cancelSync()
        val current = settings ?: return
        if (!current.getBool(KEY_ENABLED, false)) return

        val name = current.getString(KEY_NAME, "Custom Activity").trim().limit()
        if (name.isEmpty()) {
            if (showErrors) Utils.showToast("Activity name is required")
            return
        }

        val applicationId = current.getString(KEY_APPLICATION_ID, "").trim().let { value ->
            if (value.isEmpty()) null else value.toLongOrNull()
        }
        if (current.getString(KEY_APPLICATION_ID, "").trim().isNotEmpty() && applicationId == null) {
            if (showErrors) Utils.showToast("Application ID must contain only numbers")
            return
        }

        val type = ACTIVITY_TYPES[current.getInt(KEY_TYPE, 0)] ?: 0
        val details = current.getString(KEY_DETAILS, "").trim().limit().ifEmpty { null }
        val state = current.getString(KEY_STATE, "").trim().limit().ifEmpty { null }
        val largeImage = current.getString(KEY_LARGE_IMAGE, "").trim().ifEmpty { null }
        val largeText = current.getString(KEY_LARGE_TEXT, "").trim().limit().ifEmpty { null }
        val smallImage = current.getString(KEY_SMALL_IMAGE, "").trim().ifEmpty { null }
        val smallText = current.getString(KEY_SMALL_TEXT, "").trim().limit().ifEmpty { null }
        val embedded = current.getBool(KEY_EMBEDDED, true)
        val activity = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to type,
            "created_at" to System.currentTimeMillis(),
        )
        applicationId?.let { activity["application_id"] = it }
        details?.let { activity["details"] = it }
        state?.let { activity["state"] = it }

        if (largeImage != null || largeText != null || smallImage != null || smallText != null) {
            val assets = linkedMapOf<String, Any?>()
            largeImage?.let { assets["large_image"] = it }
            largeText?.let { assets["large_text"] = it }
            smallImage?.let { assets["small_image"] = it }
            smallText?.let { assets["small_text"] = it }
            activity["assets"] = assets
        }

        if (current.getBool(KEY_ELAPSED, true)) {
            startTimerIfNeeded()
            activity["timestamps"] = linkedMapOf(
                "start" to current.getLong(KEY_START_TIME, System.currentTimeMillis()).toString(),
            )
        }

        if (embedded) {
            activity["flags"] = EMBEDDED_ACTIVITY_FLAG
            activity["platform"] = "embedded"
        }

        try {
            val localStatus = StoreStream.getPresences().localPresence?.status ?: ClientStatus.ONLINE
            val sent = sendRawPresence(localStatus, activity)
            if (sent) {
                applied = true
                if (showErrors) Utils.showToast("Custom Rich Presence applied")
            } else if (showErrors) {
                Utils.showToast("Discord is not connected yet")
            }
        } catch (error: Throwable) {
            logger?.error("CustomRichPresence: failed to apply presence", error)
            if (showErrors) Utils.showToast("Could not apply Rich Presence")
        }
    }

    fun clear(showToast: Boolean = false) {
        cancelSync()
        if (!applied) return
        try {
            val localStatus = StoreStream.getPresences().localPresence?.status ?: ClientStatus.ONLINE
            if (sendRawPresence(localStatus, null)) {
                applied = false
                if (showToast) Utils.showToast("Custom Rich Presence cleared")
            } else if (showToast) {
                Utils.showToast("Discord is not connected yet")
            }
        } catch (error: Throwable) {
            logger?.error("CustomRichPresence: failed to clear presence", error)
            if (showToast) Utils.showToast("Could not clear Rich Presence")
        }
    }

    private fun String.limit(): String = take(MAX_TEXT_LENGTH)

    fun scheduleSync() {
        if (settings?.getBool(KEY_ENABLED, false) != true) return
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.postDelayed(syncRunnable, SYNC_DELAY_MS)
    }

    private fun cancelSync() {
        syncHandler.removeCallbacks(syncRunnable)
    }

    private fun sendRawPresence(status: ClientStatus, activity: Map<String, Any?>?): Boolean {
        val store = StoreStream.getGatewaySocket()
        val socket = SOCKET_FIELD.get(store) as? GatewaySocket ?: return false
        if (!socket.isSessionEstablished) return false

        val payload = linkedMapOf<String, Any?>(
            "status" to status.name.lowercase(Locale.ROOT),
            "since" to null,
            "activities" to if (activity == null) emptyList<Any>() else listOf(activity),
            "afk" to false,
        )
        val outgoing = Outgoing(Opcode.PRESENCE_UPDATE, payload)
        RAW_SEND.invoke(null, socket, outgoing, false, null, 6, null)
        return true
    }

    private val ACTIVITY_TYPES = mapOf(
        0 to 0,
        1 to 1,
        2 to 2,
        3 to 3,
        5 to 5,
    )

    private val SOCKET_FIELD = StoreGatewayConnection::class.java.getDeclaredField("socket").apply {
        isAccessible = true
    }

    private val RAW_SEND: Method = GatewaySocket::class.java.getDeclaredMethod(
        "send\$default",
        GatewaySocket::class.java,
        Outgoing::class.java,
        Boolean::class.javaPrimitiveType,
        Gson::class.java,
        Int::class.javaPrimitiveType,
        Any::class.java,
    )
}

private const val MAX_TEXT_LENGTH = 128
private const val EMBEDDED_ACTIVITY_FLAG = 1 shl 8
private const val SYNC_DELAY_MS = 500L
internal const val KEY_ENABLED = "enabled"
internal const val KEY_APPLICATION_ID = "applicationId"
internal const val KEY_NAME = "name"
internal const val KEY_TYPE = "type"
internal const val KEY_DETAILS = "details"
internal const val KEY_STATE = "state"
internal const val KEY_ELAPSED = "elapsed"
internal const val KEY_START_TIME = "startTime"
internal const val KEY_LARGE_IMAGE = "largeImage"
internal const val KEY_LARGE_TEXT = "largeText"
internal const val KEY_SMALL_IMAGE = "smallImage"
internal const val KEY_SMALL_TEXT = "smallText"
internal const val KEY_EMBEDDED = "embedded"
