package com.github.yutaplug.messageapps

import android.os.SystemClock
import com.aliucord.Http
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.rest.RestAPI
import org.json.JSONObject

// commandType is the application command type: 2 for user commands, 3 for message commands.
internal data class CommandTarget(
    val channelId: Long,
    val guildId: Long,
    val targetId: Long,
    val commandType: Int,
) {
    companion object {
        fun message(channelId: Long, guildId: Long, messageId: Long) = CommandTarget(channelId, guildId, messageId, 3)

        fun user(channelId: Long, guildId: Long, userId: Long) = CommandTarget(channelId, guildId, userId, 2)
    }
}

internal class CommandRateLimit(val retryAt: Long?) : Exception("Discord is temporarily rate limiting apps.")

internal class CommandApiFailure(val status: Int, message: String) : Exception(message)

internal data class MessageCommand(val raw: JSONObject, val app: JSONObject) {
    val id: String = raw.getString("id")
    val appId: String = raw.getString("application_id")
    val appName: String = app.optString("name").ifEmpty { "Unknown app" }
    val name: String = raw.optString("name_localized").ifEmpty { raw.getString("name") }
    val iconUrl: String? = app.optString("icon").takeIf { it.isNotEmpty() && it != "null" }?.let {
        "https://cdn.discordapp.com/app-icons/$appId/$it.png?size=128"
    }
}

internal class CommandApi(private val expectedToken: String, private val enabled: () -> Boolean) {
    fun load(target: CommandTarget): List<MessageCommand> {
        val indexes = mutableListOf<Pair<Boolean, JSONObject>>()
        if (target.guildId != 0L) {
            indexes.add(false to request("/guilds/${target.guildId}/application-command-index"))
            indexes.add(true to request("/users/@me/application-command-index"))
        } else {
            // Ordinary user DMs have no channel command index. Account apps are independent of it.
            indexes.add(true to request("/users/@me/application-command-index"))
            val channel = StoreStream.getChannels().getChannel(target.channelId)
            val hasChannelApps = channel?.D() == 3 || channel?.z()?.any { it.e() == true } == true
            if (hasChannelApps) {
                try {
                    indexes.add(false to request("/channels/${target.channelId}/application-command-index"))
                } catch (error: CommandApiFailure) {
                    // Some private channels have no integrations; retain the account app index.
                    if (error.status != 404) throw error
                }
            }
        }
        val commands = linkedMapOf<String, MessageCommand>()
        indexes.forEach { (userInstalled, index) ->
            val apps = linkedMapOf<String, JSONObject>()
            val appArray = index.optJSONArray("applications") ?: error("Discord returned an invalid app index.")
            val commandArray =
                index.optJSONArray("application_commands") ?: error("Discord returned an invalid command index.")
            for (i in 0 until appArray.length()) {
                val app = appArray.getJSONObject(i)
                apps[app.getString("id")] = app
            }
            for (i in 0 until commandArray.length()) {
                val command = commandArray.getJSONObject(i)
                if (command.optInt("type", 1) != target.commandType) continue
                val app = apps[command.getString("application_id")] ?: continue
                val installations = command.optJSONArray("integration_types")
                if (userInstalled &&
                    installations != null &&
                    (0 until installations.length()).none { installations.optInt(it) == 1 }
                ) {
                    continue
                }
                if (available(command, app, target, userInstalled)) {
                    val item = MessageCommand(command, app)
                    commands.putIfAbsent(item.id, item)
                }
            }
        }
        // A guild command overrides a global command with the same name for that app.
        return commands.values
            .groupBy { it.appId to it.raw.optString("name_default", it.raw.getString("name")) }
            .values
            .map { group -> group.firstOrNull { it.raw.optLong("guild_id") != 0L } ?: group.first() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }

    private fun available(
        command: JSONObject,
        app: JSONObject,
        target: CommandTarget,
        userInstalled: Boolean,
    ): Boolean {
        val channel = StoreStream.getChannels().getChannel(target.channelId) ?: return false
        val context = if (target.guildId != 0L) {
            0
        } else {
            val botId = app.optLong("bot_id", app.optJSONObject("bot")?.optLong("id") ?: 0L)
            val isBotDm = channel.D() == 1 &&
                botId != 0L &&
                (channel.y()?.contains(botId) == true || channel.z()?.any { it.id == botId } == true)
            if (isBotDm) 1 else 2
        }
        val contexts = command.optJSONArray("contexts")
        if (contexts != null && (0 until contexts.length()).none { contexts.optInt(it) == context }) return false
        if (contexts == null && target.guildId == 0L && !command.optBoolean("dm_permission", true)) return false
        val parent = StoreStream.getChannels().getChannel(channel.u())
        if (command.optBoolean("nsfw") && !channel.r() && parent?.r() != true) return false
        if (target.guildId == 0L) return true
        val permissions = StoreStream.getPermissions().permissionsByChannel[target.channelId] ?: 0L
        if (permissions and 8L != 0L) return true
        // Account installations do not use a server app's command permission overwrites.
        if (userInstalled) return permissions and (1L shl 10) != 0L
        if (permissions and (1L shl 31) == 0L) return false
        val commandPermissions = command.optJSONObject("permissions")
        val appPermissions = app.optJSONObject("permissions")
        val required = command.optString("default_member_permissions").toLongOrNull()
        val defaultAllowed = required == null || (required != 0L && permissions and required == required)
        val appAllowed = allowed(appPermissions, target, parent?.k(), defaultAllowed)
        return allowed(commandPermissions, target, parent?.k(), appAllowed)
    }

    private fun allowed(overrides: JSONObject?, target: CommandTarget, parentId: Long?, fallback: Boolean): Boolean {
        if (overrides == null) return fallback
        val channels = overrides.optJSONObject("channels")
        val channelAllowed = when {
            channels == null -> true
            channels.has(target.channelId.toString()) -> channels.optBoolean(target.channelId.toString())
            parentId != null && channels.has(parentId.toString()) -> channels.optBoolean(parentId.toString())
            else -> channels.optBoolean((target.guildId - 1).toString(), true)
        }
        if (!channelAllowed) return false
        if (overrides.has("user")) return overrides.optBoolean("user")
        val roles = overrides.optJSONObject("roles") ?: return fallback
        val memberRoles = StoreStream
            .getGuilds()
            .getMember(
                target.guildId,
                StoreStream.getUsers().me.id,
            )?.roles
            .orEmpty()
        val explicit = memberRoles.filter { roles.has(it.toString()) }
        if (explicit.isNotEmpty()) return explicit.any { roles.optBoolean(it.toString()) }
        return roles.optBoolean(target.guildId.toString(), fallback)
    }

    fun execute(target: CommandTarget, command: MessageCommand) {
        val session = StoreApplicationInteractions.`access$getSessionId$p`(StoreStream.getInteractions())
        check(!session.isNullOrEmpty()) { "Discord is reconnecting. Try again when connected." }
        val data = JSONObject()
            .put("id", command.id)
            .put("version", command.raw.getString("version"))
            .put("name", command.raw.optString("name_default", command.raw.getString("name")))
            .put("type", target.commandType)
            .put("target_id", target.targetId.toString())
            .put("application_command", command.raw)
        val payload = JSONObject()
            .put("type", 2)
            .put("application_id", command.appId)
            .put("channel_id", target.channelId.toString())
            .put("session_id", session)
            .put(
                "nonce",
                (
                    SnowflakeUtils.fromTimestamp(
                        System.currentTimeMillis(),
                    ) +
                        (System.nanoTime() and 0x3fffff)
                ).toString(),
            ).put("data", data)
        if (target.guildId != 0L) payload.put("guild_id", target.guildId.toString())
        request("/interactions", payload)
    }

    private fun request(route: String, payload: JSONObject? = null): JSONObject = synchronized(requestLock) {
        check(enabled()) { "MessageApps is disabled." }
        check(token() == expectedToken) { "Discord account changed. Reopen Apps." }
        if (cacheToken != expectedToken) {
            cacheToken = expectedToken
            indexes.clear()
            cooldowns.clear()
            globalCooldown = 0L
        }
        val now = SystemClock.elapsedRealtime()
        if (payload == null) {
            indexes[route]?.let {
                if (now - it.loadedAt < 300_000L) return@synchronized it.body
            }
        }
        val cooldown = maxOf(globalCooldown, cooldowns[route] ?: 0L)
        if (cooldown > now) throw CommandRateLimit(cooldown)
        Http.Request.newDiscordRequest(route, if (payload == null) "GET" else "POST").use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", expectedToken)
            request.setHeader("Content-Type", "application/json")
            val response = if (payload == null) request.execute() else request.executeWithBody(payload.toString())
            response.use {
                if (!it.ok()) {
                    val failure = runCatching {
                        request.conn.errorStream?.bufferedReader()?.use { reader ->
                            JSONObject(reader.readText().take(16_384))
                        }
                    }.getOrNull()
                    if (it.statusCode == 429) {
                        val seconds = maxOf(
                            failure?.optDouble("retry_after", 0.0) ?: 0.0,
                            request.conn.getHeaderField("Retry-After")?.toDoubleOrNull() ?: 0.0,
                        )
                        val retryAt = if (seconds.isFinite() && seconds > 0.0) {
                            SystemClock.elapsedRealtime() + kotlin.math.ceil(seconds * 1000).toLong() + 250L
                        } else {
                            null
                        }
                        if (retryAt != null) {
                            if (failure?.optBoolean("global") == true ||
                                request.conn.getHeaderField("X-RateLimit-Global") == "true"
                            ) {
                                globalCooldown = retryAt
                            } else {
                                cooldowns[route] = retryAt
                            }
                        }
                        throw CommandRateLimit(retryAt)
                    }
                    val message = failure?.optString("message")
                    throw CommandApiFailure(
                        it.statusCode,
                        message?.takeIf { value -> value.isNotEmpty() } ?: "Discord returned HTTP ${it.statusCode}.",
                    )
                }
                val text = it.text()
                val body = if (hasText(text)) JSONObject(text) else JSONObject()
                if (payload == null && body.has("applications") && body.has("application_commands")) {
                    if (indexes.size >= 32) indexes.remove(indexes.keys.first())
                    indexes[route] = CachedIndex(body, SystemClock.elapsedRealtime())
                }
                val reset = request.conn.getHeaderField("X-RateLimit-Reset-After")?.toDoubleOrNull()
                if (request.conn.getHeaderField("X-RateLimit-Remaining") == "0" &&
                    reset != null &&
                    reset.isFinite() &&
                    reset > 0.0
                ) {
                    cooldowns[route] = SystemClock.elapsedRealtime() + kotlin.math.ceil(reset * 1000).toLong() + 250L
                }
                body
            }
        }
    }

    companion object {
        private data class CachedIndex(val body: JSONObject, val loadedAt: Long)

        private val requestLock = Any()
        private var cacheToken: String? = null
        private val indexes = linkedMapOf<String, CachedIndex>()
        private val cooldowns = mutableMapOf<String, Long>()
        private var globalCooldown = 0L

        fun token(): String? = RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)

        // Discord's obfuscated IntRange iterator is incompatible with stdlib isBlank().
        private fun hasText(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
            }
            return false
        }
    }
}
