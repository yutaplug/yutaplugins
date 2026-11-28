package com.github.yutaplug.profileeffects

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.IOUtils
import com.discord.utilities.rest.RestAPI
import com.google.gson.JsonElement
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil

/** Owns its workers and caches; closing it prevents callbacks from an earlier plugin session. */
internal class ProfileRepository(private val log: (String, Throwable) -> Unit) {
    private val workers = Executors.newFixedThreadPool(2) { Thread(it, "ProfileEffectsRequests") }
    private val profiles = RequestCache<ProfileKey, Profile>(128, ::now)
    private val products = RequestCache<ProductKey, Product>(128, ::now)
    private val roleGuilds = HashSet<Long>()

    @Volatile private var closed = false

    @Volatile private var retryAt = 0L

    fun load(key: ProfileKey, callback: (LoadResult<Profile>) -> Unit) {
        profiles.request(key, { finish ->
            execute<Profile>(key.route(), { failure -> finish(failure) }) { response ->
                val body = response as? Map<*, *> ?: error("Invalid profile response")
                val info = ProfileData.products(body)
                if (info.hasUserStyle) NameStyles.setUser(key.userId, info.userStyle)
                if (info.hasMemberStyle && key.guildId != null) {
                    NameStyles.setMember(key.guildId, key.userId, info.memberStyle)
                }
                val base = Profile(theme = info.theme)
                val keys = listOfNotNull(info.effect, info.frame)
                if (keys.isEmpty()) {
                    finish(LoadResult(base))
                    return@execute
                }
                // Show the theme right away while collectibles load.
                profiles.publish(key, base)
                loadProducts(key, base, keys, finish)
            }
        }, callback)
    }

    private fun loadProducts(
        key: ProfileKey,
        base: Profile,
        keys: List<ProductKey>,
        finish: (LoadResult<Profile>) -> Unit,
    ) {
        val lock = Any()
        var remaining = keys.size
        var profile = base
        var failed = false
        var ttl = CACHE_TTL
        for (productKey in keys) {
            products.request(productKey, { complete ->
                execute<Product>("/collectibles-products/${productKey.sku}", complete) { response ->
                    val body = response as? Map<*, *> ?: error("Invalid collectible response")
                    val product = ProfileData.product(productKey, body)
                    if (productKey.type == 3) reportFrame(productKey, product, body)
                    complete(LoadResult(product))
                }
            }) { result ->
                synchronized(lock) {
                    when (val product = result.value) {
                        is Product.Effect -> profile = profile.copy(effect = product)
                        is Product.Frame -> profile = profile.copy(frame = product)
                        null -> {}
                    }
                    failed = failed || result.failed
                    ttl = minOf(ttl, result.ttl)
                    if (--remaining == 0) {
                        finish(LoadResult(profile, failed, ttl))
                    } else {
                        // Show whichever product arrives first while the other request finishes.
                        profiles.publish(key, profile)
                    }
                }
            }
        }
    }

    /**
     * Fetches a guild's roles once per session, for roles whose colors no gateway payload carried
     * since the plugin started (for example, guilds Discord restored from its cache).
     */
    fun loadRoles(guildId: Long, done: () -> Unit) {
        synchronized(roleGuilds) { if (!roleGuilds.add(guildId)) return }
        execute<Unit>("/guilds/$guildId/roles", { }) { response ->
            for (raw in response as? List<*> ?: return@execute) {
                val role = raw as? Map<*, *> ?: continue
                val id = Json.long(role["id"]) ?: continue
                NameStyles.setRole(id, role["colors"] as? Map<*, *>)
            }
            done()
        }
    }

    private fun <T> execute(route: String, failure: (LoadResult<T>) -> Unit, success: (Any?) -> Unit) {
        try {
            workers.execute {
                if (closed) return@execute
                try {
                    val body = requestJson(route)
                    if (!closed) success(body)
                } catch (error: Exception) {
                    if (!closed) {
                        log("Could not load $route", error)
                        failure(LoadResult(null, true, (error as? RateLimit)?.delay ?: FAILURE_TTL))
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!closed) failure(LoadResult(null, true, FAILURE_TTL))
        }
    }

    /** Logs a frame that would draw nothing, with Discord's data, so it shows up in the debug log. */
    private fun reportFrame(key: ProductKey, product: Product?, body: Map<*, *>) {
        val frame = product as? Product.Frame
        val reason = when {
            frame == null -> "has no layers the plugin could read"
            frame.layers.all { it.anchor == Anchor.BOTTOM } -> "only has bottom layers, which profile sheets don't show"
            else -> return
        }
        val json = try {
            GsonUtils.toJson(body)
        } catch (_: Exception) {
            body.toString()
        }
        log("Profile frame ${key.sku} $reason", IllegalStateException(json.take(4000)))
    }

    fun close() {
        closed = true
        profiles.close()
        products.close()
        workers.shutdownNow()
    }

    private class RateLimit(val delay: Long) : Exception("Discord rate limited profile requests")

    private fun requestJson(route: String): Any? {
        val cooldown = retryAt - now()
        if (cooldown > 0) throw RateLimit(cooldown)
        return Http.Request.newDiscordRNRequest(route).use { request ->
            request.setRequestTimeout(15_000)
            RestAPI.AppHeadersProvider.INSTANCE.getFingerprint()?.let { request.setHeader("X-Fingerprint", it) }
            request.execute().use { response ->
                if (response.statusCode == 429) {
                    val errorBody = request.conn.errorStream?.use { IOUtils.readAsText(it) }
                    val body = try {
                        errorBody?.let { Json.value(GsonUtils.gson.fromJson(it, JsonElement::class.java)) as? Map<*, *> }
                    } catch (_: Exception) {
                        null
                    }
                    val seconds = maxOf(
                        request.conn.getHeaderField("Retry-After")?.toDoubleOrNull() ?: 0.0,
                        body?.get("retry_after")?.toString()?.toDoubleOrNull() ?: 0.0,
                    )
                    val delay = if (seconds.isFinite() && seconds > 0) {
                        ceil(seconds.coerceAtMost(86_400.0) * 1000).toLong() + 100
                    } else {
                        FAILURE_TTL
                    }
                    synchronized(this) { retryAt = maxOf(retryAt, now() + delay) }
                    throw RateLimit(delay)
                }
                check(response.ok()) { "HTTP ${response.statusCode}" }
                Json.value(GsonUtils.gson.fromJson(response.text(), JsonElement::class.java))
            }
        }
    }

    private fun now() = System.nanoTime() / 1_000_000L
}
