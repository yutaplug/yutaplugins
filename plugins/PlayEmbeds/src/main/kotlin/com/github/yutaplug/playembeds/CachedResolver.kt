package com.github.yutaplug.playembeds

import android.os.SystemClock
import com.aliucord.Http
import com.aliucord.Utils
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Kotlin's Regex resolves to Discord's obfuscated stdlib copy (no default
 * arguments), so resolvers use java.util.regex directly.
 */
internal fun Pattern.firstGroup(input: CharSequence, group: Int = 1): String? {
    val matcher = matcher(input)
    return if (matcher.find()) matcher.group(group) else null
}

internal fun Pattern.allGroups(input: CharSequence, group: Int = 1): List<String> {
    val matcher = matcher(input)
    val results = ArrayList<String>()
    while (matcher.find()) matcher.group(group)?.let { results.add(it) }
    return results
}

/** A stream Discord's ExoPlayer can play as one progressive source. */
internal data class ResolvedMedia(
    val uri: String,
    val width: Int,
    val height: Int,
    val audioOnly: Boolean,
)

/**
 * Resolves embed URLs on one background thread, merges concurrent requests for
 * the same item and caches results (including failures) until they expire.
 */
internal abstract class CachedResolver<V : Any>(private val failureTtlMs: Long = 60_000) {
    private class Entry<V>(val value: V?, val expires: Long)

    private val cache = LinkedHashMap<String, Entry<V>>()
    private val pending = HashMap<String, MutableList<(V?) -> Unit>>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    /** The cache key for [url], or null when this resolver doesn't handle it. */
    protected abstract fun key(url: String): String?

    /** Runs on the worker thread. Returns the value and how many ms it stays valid. */
    protected abstract fun load(key: String): Pair<V, Long>?

    /** Lets a subclass drop cached values whose backing data went away. */
    protected open fun isStillValid(value: V): Boolean = true

    protected open fun onClose() {}

    fun handles(url: String?): Boolean = url != null && key(url) != null

    fun cached(url: String?): V? {
        val key = url?.let(::key) ?: return null
        return synchronized(cache) { validEntry(key)?.value }
    }

    fun isPending(url: String?): Boolean {
        val key = url?.let(::key) ?: return false
        return synchronized(cache) { pending.containsKey(key) }
    }

    /** Returns false when [url] isn't handled; otherwise [done] runs later on the main thread. */
    fun resolve(url: String?, done: (V?) -> Unit): Boolean {
        val key = url?.let(::key) ?: return false
        synchronized(cache) {
            if (closed) return false
            validEntry(key)?.let { entry ->
                Utils.mainThread.post { done(entry.value) }
                return true
            }
            pending[key]?.let {
                it.add(done)
                return true
            }
            pending[key] = mutableListOf(done)
            worker.execute {
                val result = try {
                    load(key)
                } catch (_: Throwable) {
                    null
                }
                Utils.mainThread.post {
                    val callbacks = synchronized(cache) {
                        if (closed) return@post
                        val ttl = result?.second?.coerceAtLeast(1_000) ?: failureTtlMs
                        cache[key] = Entry(result?.first, SystemClock.elapsedRealtime() + ttl)
                        while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
                        pending.remove(key).orEmpty()
                    }
                    callbacks.forEach { it(result?.first) }
                }
            }
        }
        return true
    }

    private fun validEntry(key: String): Entry<V>? {
        val entry = cache[key] ?: return null
        val value = entry.value
        if (entry.expires <= SystemClock.elapsedRealtime() || (value != null && !isStillValid(value))) {
            cache.remove(key)
            return null
        }
        return entry
    }

    fun close() {
        synchronized(cache) {
            closed = true
            pending.clear()
            cache.clear()
            worker.shutdownNow()
        }
        onClose()
    }

    protected companion object {
        const val MAX_ENTRIES = 64
        const val TIMEOUT_MS = 15_000
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** GET [url] and return its body, or null on a non-2xx response. */
        fun getText(url: String, userAgent: String = BROWSER_USER_AGENT): String? =
            Http.Request(url).use { request ->
                request.setRequestTimeout(TIMEOUT_MS)
                request.setHeader("User-Agent", userAgent)
                request.execute().use { response -> if (response.ok()) response.text() else null }
            }

        /** Follow redirects from [url] and return the final URL. */
        fun finalUrl(url: String): String =
            Http.Request(url).use { request ->
                request.setRequestTimeout(TIMEOUT_MS)
                request.setHeader("User-Agent", BROWSER_USER_AGENT)
                request.setFollowRedirects(true)
                request.execute().use { request.conn.url.toString() }
            }
    }
}
