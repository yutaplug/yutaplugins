package com.github.yutaplug.customrichpresence

import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityTimestamps
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.stores.StoreStream

internal object PresenceController {
    var settings: SettingsAPI? = null
    var logger: Logger? = null

    private var applied = false

    fun startTimerIfNeeded() {
        val current = settings ?: return
        if (current.getLong(KEY_START_TIME, 0L) == 0L) {
            current.setLong(KEY_START_TIME, System.currentTimeMillis())
        }
    }

    fun apply(showErrors: Boolean = false) {
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

        val type = ACTIVITY_TYPES.getOrElse(current.getInt(KEY_TYPE, 0)) { ActivityType.PLAYING }
        val details = current.getString(KEY_DETAILS, "").trim().limit().ifEmpty { null }
        val state = current.getString(KEY_STATE, "").trim().limit().ifEmpty { null }
        val largeImage = current.getString(KEY_LARGE_IMAGE, "").trim().ifEmpty { null }
        val largeText = current.getString(KEY_LARGE_TEXT, "").trim().limit().ifEmpty { null }
        val smallImage = current.getString(KEY_SMALL_IMAGE, "").trim().ifEmpty { null }
        val smallText = current.getString(KEY_SMALL_TEXT, "").trim().limit().ifEmpty { null }
        val assets = if (largeImage != null || largeText != null || smallImage != null || smallText != null) {
            ActivityAssets(largeImage, largeText, smallImage, smallText)
        } else {
            null
        }
        val timestamps = if (current.getBool(KEY_ELAPSED, true)) {
            startTimerIfNeeded()
            ActivityTimestamps(current.getLong(KEY_START_TIME, System.currentTimeMillis()).toString(), null)
        } else {
            null
        }

        val activity = Activity(
            name,
            type,
            null,
            System.currentTimeMillis(),
            timestamps,
            applicationId,
            details,
            state,
            null,
            null,
            assets,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        try {
            val localStatus = StoreStream.getPresences().localPresence?.status ?: ClientStatus.ONLINE
            val sent = StoreStream.getGatewaySocket().presenceUpdate(localStatus, null, listOf(activity), false)
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
        if (!applied) return
        try {
            val localStatus = StoreStream.getPresences().localPresence?.status ?: ClientStatus.ONLINE
            StoreStream.getGatewaySocket().presenceUpdate(localStatus, null, emptyList(), false)
            applied = false
            if (showToast) Utils.showToast("Custom Rich Presence cleared")
        } catch (error: Throwable) {
            logger?.error("CustomRichPresence: failed to clear presence", error)
            if (showToast) Utils.showToast("Could not clear Rich Presence")
        }
    }

    private fun String.limit(): String = take(MAX_TEXT_LENGTH)

    private val ACTIVITY_TYPES = mapOf(
        0 to ActivityType.PLAYING,
        1 to ActivityType.STREAMING,
        2 to ActivityType.LISTENING,
        3 to ActivityType.WATCHING,
        5 to ActivityType.COMPETING,
    )
}

private const val MAX_TEXT_LENGTH = 128
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
