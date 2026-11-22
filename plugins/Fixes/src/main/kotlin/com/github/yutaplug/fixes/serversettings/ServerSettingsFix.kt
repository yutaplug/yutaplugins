package com.github.yutaplug.fixes.serversettings

import android.content.Context
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.patcher.Hook
import com.aliucord.patcher.InsteadHook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.ReflectUtils
import com.discord.models.domain.ModelAuditLog
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.ModelWebhook
import com.discord.stores.StoreAuditLog
import com.discord.stores.StoreBans
import com.discord.stores.StoreInviteSettings
import com.github.yutaplug.fixes.Fix
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Ported from pilotbellyt-spec's ServerSettingsFix: https://github.com/pilotbellyt-spec/AliucordPlugins */
internal class ServerSettingsFix : Fix(
    "ServerSettingsFix",
    "Server settings",
    "Fixes the bans list and audit log not loading in server settings, and shows invite durations properly.",
    author = "pilotbellyt-spec",
) {
    private val pendingBanGuilds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override fun start(context: Context) {
        migrateSettings()
        try {
            if (option(FIX_BANS)) bans()
            if (option(FIX_AUDIT_LOG)) auditLog()
            if (option(SHORT_INVITES)) inviteDefaults()
            inviteDurationText()
        } catch (error: Throwable) {
            patcher.unpatchAll()
            throw error
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pendingBanGuilds.clear()
    }

    fun option(option: Option): Boolean = settings.getBool(option.key, option.default)

    private fun bans() {
        patcher.patch(
            StoreBans::class.java,
            "observeBans",
            arrayOf(Long::class.javaPrimitiveType!!),
            Hook { param -> fetchBansOnce(param.thisObject as StoreBans, param.args[0] as Long) },
        )
    }

    private fun inviteDurationText() {
        // A Kotlin file facade, which Kotlin code cannot name directly.
        patcher.patch(
            Class.forName("com.discord.utilities.resources.DurationUtilsKt"),
            "formatInviteExpireAfterString",
            arrayOf(Context::class.java, Int::class.javaPrimitiveType!!),
            InsteadHook { param -> formatInviteDuration(param.args[1] as Int) },
        )
    }

    private fun formatInviteDuration(seconds: Int): CharSequence {
        if (seconds == 0) return "Never"
        val (amount, unit) = when {
            seconds % SECONDS_PER_MONTH == 0 -> seconds / SECONDS_PER_MONTH to "month"
            seconds % SECONDS_PER_WEEK == 0 -> seconds / SECONDS_PER_WEEK to "week"
            seconds % SECONDS_PER_DAY == 0 -> seconds / SECONDS_PER_DAY to "day"
            seconds % SECONDS_PER_HOUR == 0 -> seconds / SECONDS_PER_HOUR to "hour"
            seconds % SECONDS_PER_MINUTE == 0 -> seconds / SECONDS_PER_MINUTE to "minute"
            else -> seconds to "second"
        }
        return if (amount == 1) "$amount $unit" else "$amount ${unit}s"
    }

    private fun auditLog() {
        patcher.patch(
            StoreAuditLog::class.java,
            "fetchAuditLogs",
            arrayOf(Long::class.javaPrimitiveType!!, StoreAuditLog.AuditLogFilter::class.java),
            InsteadHook { param ->
                val store = param.thisObject as StoreAuditLog
                val guildId = param.args[0] as Long
                val filter = param.args[1] as StoreAuditLog.AuditLogFilter
                val before = auditLogState(store).entries?.lastOrNull()?.id
                if (!prepareAuditLogLoad(store, guildId, filter, before)) return@InsteadHook null

                Utils.threadPool.execute {
                    val auditLog = try {
                        fetchAuditLog(guildId, filter, before).also { ensureAuditEntries(it) }
                    } catch (error: Throwable) {
                        logger.warn("Could not load the audit log for $guildId", error)
                        showToast("Could not load audit log.")
                        blankAuditLog()
                    }
                    // Schedules the update on the store's dispatcher, like Discord's own fetch.
                    StoreAuditLog.`access$handleFetchSuccess`(store, guildId, auditLog)
                }
                null
            },
        )
    }

    private fun inviteDefaults() {
        patcher.patch(
            StoreInviteSettings::class.java,
            "getInviteSettings",
            arrayOf(Long::class.javaPrimitiveType!!),
            Hook { param -> param.result = withShortMaxAge(param.result as? ModelInvite.Settings) },
        )
        patcher.patch(
            StoreInviteSettings::class.java,
            "generateInvite",
            arrayOf(Long::class.javaPrimitiveType!!, ModelInvite.Settings::class.java),
            PreHook { param -> param.args[1] = withShortMaxAge(param.args[1] as? ModelInvite.Settings) },
        )
    }

    private fun withShortMaxAge(settings: ModelInvite.Settings?) =
        settings?.mergeMaxAge(INVITE_MAX_AGE_SECONDS) ?: ModelInvite.Settings(INVITE_MAX_AGE_SECONDS)

    private fun fetchBansOnce(store: StoreBans, guildId: Long) {
        if (!pendingBanGuilds.add(guildId)) return
        Utils.threadPool.execute {
            val bans = try {
                fetchAllBans(guildId)
            } catch (error: Throwable) {
                pendingBanGuilds.remove(guildId)
                logger.warn("Could not load bans for $guildId", error)
                showToast("Could not load bans.")
                emptyList()
            }
            putBans(store, guildId, bans)
        }
    }

    private fun fetchAllBans(guildId: Long): List<ModelBan> {
        val bans = ArrayList<ModelBan>()
        var after: Long? = null
        var pages = 0
        do {
            var route = "/guilds/$guildId/bans?limit=$BANS_PAGE_SIZE"
            if (after != null) route += "&after=$after"
            val page = Http.Request.newDiscordRNRequest(route).execute().use { response ->
                response.assertOk()
                response.json(GsonUtils.gsonRestApi, Array<ModelBan>::class.java).toList()
            }
            page.filterTo(bans) { it.user != null }
            after = page.lastOrNull()?.user?.id
            pages++
        } while (page.size == BANS_PAGE_SIZE && after != null && pages < MAX_BAN_PAGES)
        return bans
    }

    private fun putBans(store: StoreBans, guildId: Long, bans: List<ModelBan>) {
        StoreBans.`access$getDispatcher$p`(store).schedule {
            val guildBans = HashMap<Long, ModelBan>()
            for (ban in bans) {
                val user = ban.user ?: continue
                guildBans[user.id] = ban
            }
            StoreBans.`access$getBannedUsers$p`(store)[guildId] = guildBans
            store.markChanged()
        }
    }

    private fun auditLogState(store: StoreAuditLog) = ReflectUtils.getField(store, "state") as StoreAuditLog.AuditLogState

    /** Marks the log as loading, or returns false if this page was already requested. */
    private fun prepareAuditLogLoad(
        store: StoreAuditLog,
        guildId: Long,
        filter: StoreAuditLog.AuditLogFilter,
        before: Long?,
    ): Boolean {
        val state = auditLogState(store)
        if (ReflectUtils.getField(store, "cutoffTimestamp") == null) {
            ReflectUtils.setField(store, "cutoffTimestamp", 0L)
        }
        if (before == ReflectUtils.getField(store, "cutoffTimestamp") as Long?) return false

        ReflectUtils.setField(store, "cutoffTimestamp", before)
        ReflectUtils.setField(
            store,
            "state",
            state.copy(
                guildId,
                state.users,
                state.entries,
                state.webhooks,
                state.integrations,
                state.guildScheduledEvents,
                state.threads,
                state.selectedItemId,
                filter,
                state.deletedTargets,
                true,
            ),
        )
        store.markChanged()
        return true
    }

    private fun fetchAuditLog(guildId: Long, filter: StoreAuditLog.AuditLogFilter, before: Long?): ModelAuditLog {
        var route = "/guilds/$guildId/audit-logs?limit=$AUDIT_LOG_PAGE_SIZE"
        if (before != null) route += "&before=$before"
        if (filter.userFilter != 0L) route += "&user_id=${filter.userFilter}"
        if (filter.actionFilter != 0) route += "&action_type=${filter.actionFilter}"
        val body = Http.Request.newDiscordRNRequest(route).execute().use { response ->
            response.assertOk()
            response.text()
        }
        return GsonUtils.gsonRestApi.fromJson(sanitizeAuditLogJson(body), ModelAuditLog::class.java)
    }

    /** Discord 126.21 only reads plain values in audit-log changes; newer object values break parsing. */
    private fun sanitizeAuditLogJson(body: String): String {
        val json = JSONObject(body)
        val entries = json.optJSONArray("audit_log_entries") ?: return body
        for (i in 0 until entries.length()) {
            val changes = entries.optJSONObject(i)?.optJSONArray("changes") ?: continue
            for (j in 0 until changes.length()) {
                val change = changes.optJSONObject(j) ?: continue
                sanitizeChangeValue(change, "old_value")
                sanitizeChangeValue(change, "new_value")
            }
        }
        return json.toString()
    }

    private fun sanitizeChangeValue(change: JSONObject, key: String) {
        if (!change.has(key)) return
        when (val value = change.opt(key)) {
            is JSONObject -> change.put(key, labelFor(value) ?: value.toString())
            is JSONArray -> change.put(key, readableArray(value))
        }
    }

    private fun readableArray(values: JSONArray): JSONArray {
        val sanitized = JSONArray()
        for (i in 0 until values.length()) {
            when (val value = values.opt(i)) {
                is JSONObject, is String, is Number -> sanitized.put(value)
            }
        }
        return sanitized
    }

    private fun labelFor(value: JSONObject): String? =
        value.optString("name").takeIf { it.trim().isNotEmpty() }
            ?: value.optString("id").takeIf { it.trim().isNotEmpty() }

    private fun ensureAuditEntries(auditLog: ModelAuditLog) {
        if (auditLog.auditLogEntries == null) {
            ReflectUtils.setField(auditLog, "auditLogEntries", emptyList<ModelAuditLogEntry>())
        }
    }

    private fun blankAuditLog() = ModelAuditLog().apply {
        ReflectUtils.setField(this, "auditLogEntries", emptyList<ModelAuditLogEntry>())
        ReflectUtils.setField(this, "users", emptyList<com.discord.api.user.User>())
        ReflectUtils.setField(this, "webhooks", emptyList<ModelWebhook>())
        ReflectUtils.setField(this, "integrations", emptyList<ModelGuildIntegration>())
        ReflectUtils.setField(this, "guildScheduledEvents", emptyList<Any>())
        ReflectUtils.setField(this, "threads", emptyList<Any>())
    }

    private fun showToast(message: String) {
        if (option(SHOW_TOASTS)) Utils.mainThread.post { Utils.showToast(message) }
    }

    /** Carries over the options of the standalone ServerSettingsFix plugin once. */
    private fun migrateSettings() {
        if (settings.getBool(MIGRATED, false)) return
        try {
            val old = SettingsAPI("ServerSettingsFix")
            for (option in options) {
                if (old.exists(option.oldKey) && !settings.exists(option.key)) {
                    settings.setBool(option.key, old.getBool(option.oldKey, option.default))
                }
            }
        } catch (error: Throwable) {
            logger.error("Could not import ServerSettingsFix settings", error)
        }
        settings.setBool(MIGRATED, true)
    }

    class Option(val title: String, val description: String, val oldKey: String, val default: Boolean) {
        val key = "serverSettings_$oldKey"
    }

    companion object {
        val FIX_BANS = Option("Bans list", "Loads the bans list with the current API.", "fixBans", true)
        val FIX_AUDIT_LOG = Option("Audit log", "Loads the audit log with the current API.", "fixAuditLog", true)
        val SHORT_INVITES = Option(
            "30-minute invites",
            "Makes new invite links expire after 30 minutes.",
            "shortInvites",
            true,
        )
        val SHOW_TOASTS = Option(
            "Failure toasts",
            "Shows a short message when loading bans or the audit log fails.",
            "showToasts",
            true,
        )
        val options = listOf(FIX_BANS, FIX_AUDIT_LOG, SHORT_INVITES, SHOW_TOASTS)

        private const val MIGRATED = "serverSettingsMigrated"
        private const val INVITE_MAX_AGE_SECONDS = 30 * 60
        private const val BANS_PAGE_SIZE = 1000
        private const val MAX_BAN_PAGES = 5
        private const val AUDIT_LOG_PAGE_SIZE = 50
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
        private const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR
        private const val SECONDS_PER_WEEK = 7 * SECONDS_PER_DAY
        private const val SECONDS_PER_MONTH = 30 * SECONDS_PER_DAY
    }
}
