package com.github.yutaplug.customrpc

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns what the user typed into an image field into a rich presence asset that other clients can load:
 * - an asset ID (digits) is used as is
 * - a Discord CDN or media proxy link becomes "mp:<path>"; attachment links are re-signed before they expire
 * - any other http(s) link goes through Discord's external asset proxy and becomes "mp:external/..."
 * - anything else is looked up by name in the application's Rich Presence art assets
 */
internal class ImageResolver(private val logger: Logger, private val onResolved: (Map<String, String>) -> Unit) {
    private sealed class Source {
        class Ready(val path: String) : Source()

        class Attachment(val cdnUrl: String, val expiresAt: Long) : Source()

        class External(val url: String) : Source()

        class AssetName(val name: String) : Source()

        class Invalid(val error: String) : Source()
    }

    private class Entry(val path: String?, val error: String?, val expiresAt: Long, val retryAt: Long)

    private val lock = Any()
    private val cache = HashMap<String, Entry>()
    private val inFlight = HashSet<String>()
    private var generation = 0

    private fun key(appId: Long, value: String) = "$appId\n$value"

    /** The asset path to send for [value], or null if it isn't available (yet). */
    fun path(appId: Long, value: String): String? {
        val now = System.currentTimeMillis()
        return when (val source = classify(value)) {
            is Source.Ready -> source.path
            is Source.Invalid -> null
            is Source.Attachment -> {
                val entry = synchronized(lock) { cache[key(appId, value)] }
                when {
                    entry?.path != null && entry.expiresAt > now -> entry.path
                    source.expiresAt > now -> mediaPath(source.cdnUrl)?.takeIf { it.length <= MAX_ASSET_LENGTH }
                    else -> null
                }
            }
            else -> synchronized(lock) { cache[key(appId, value)]?.path }
        }
    }

    /** Why [value] can't be shown, if resolving it failed. */
    fun error(appId: Long, value: String): String? = when (val source = classify(value)) {
        is Source.Invalid -> source.error
        is Source.Ready -> null
        else -> synchronized(lock) { cache[key(appId, value)]?.error }
    }

    /** When [request] should run again for these values: attachment re-signing or a retry after a failure. */
    fun nextWake(appId: Long, values: List<String>): Long {
        var next = Long.MAX_VALUE
        synchronized(lock) {
            for (value in values) {
                val source = classify(value)
                val entry = cache[key(appId, value)]
                val wake = when {
                    entry?.error != null -> entry.retryAt
                    source is Source.Attachment -> (entry?.expiresAt ?: source.expiresAt) - REFRESH_BEFORE_EXPIRY
                    else -> Long.MAX_VALUE
                }
                if (wake < next) next = wake
            }
        }
        return next
    }

    /** Forget failures so the next [request] tries again right away. */
    fun forgetErrors() = synchronized(lock) {
        val failed = cache.filterValues { it.error != null }.keys
        failed.forEach { cache.remove(it) }
    }

    fun clear() = synchronized(lock) {
        generation++
        cache.clear()
        inFlight.clear()
    }

    /**
     * Resolves every value that isn't usable yet in the background, then calls [onResolved] with the errors.
     * Returns whether anything is being resolved.
     */
    fun request(appId: Long, values: List<String>): Boolean {
        val now = System.currentTimeMillis()
        val attachments = LinkedHashMap<String, Source.Attachment>()
        val externals = LinkedHashMap<String, Source.External>()
        val names = LinkedHashMap<String, Source.AssetName>()
        val gen: Int
        synchronized(lock) {
            gen = generation
            for (value in values.distinct()) {
                val k = key(appId, value)
                if (k in inFlight) continue
                val entry = cache[k]
                if (entry?.error != null && now < entry.retryAt) continue
                when (val source = classify(value)) {
                    is Source.Attachment -> {
                        val expiresAt = if (entry?.path != null) entry.expiresAt else source.expiresAt
                        if (expiresAt - REFRESH_BEFORE_EXPIRY > now) continue
                        attachments[value] = source
                    }
                    is Source.External -> if (entry?.path == null) externals[value] = source else continue
                    is Source.AssetName -> if (entry?.path == null) names[value] = source else continue
                    else -> continue
                }
                inFlight += k
            }
        }
        if (attachments.isEmpty() && externals.isEmpty() && names.isEmpty()) {
            return synchronized(lock) { inFlight.any { it.startsWith("$appId\n") } }
        }

        Utils.threadPool.execute {
            val results = HashMap<String, Entry>()
            fun fail(value: String, error: String) {
                results[value] = Entry(null, error, 0, System.currentTimeMillis() + RETRY_DELAY)
            }

            fun succeed(value: String, path: String, expiresAt: Long = Long.MAX_VALUE) {
                if (path.length > MAX_ASSET_LENGTH) {
                    fail(value, "The link is too long for Discord. Try a shorter link.")
                } else {
                    results[value] = Entry(path, null, expiresAt, 0)
                }
            }

            if (attachments.isNotEmpty()) {
                try {
                    val refreshed = refreshAttachments(attachments.values.map { it.cdnUrl })
                    for ((value, source) in attachments) {
                        val url = refreshed[source.cdnUrl]
                        val path = url?.let { mediaPath(it) }
                        if (url == null || path == null) {
                            fail(value, "Discord didn't renew this image link. Upload the image again and copy a new link.")
                        } else {
                            succeed(value, path, attachmentExpiry(url) ?: (System.currentTimeMillis() + DEFAULT_LIFETIME))
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to renew attachment links", e)
                    attachments.keys.forEach { fail(it, describe(e, "renew the Discord image link")) }
                }
            }

            if (externals.isNotEmpty()) {
                // The endpoint takes at most 2 URLs per request.
                val entries = ArrayList(externals.entries)
                var start = 0
                while (start < entries.size) {
                    val chunk = entries.subList(start, minOf(start + 2, entries.size))
                    start += 2
                    try {
                        val proxied = proxyExternal(appId, chunk.map { it.value.url })
                        for ((value, source) in chunk) {
                            val path = proxied[source.url]
                            if (path == null) fail(value, "Discord couldn't load this image link.") else succeed(value, path)
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to proxy external images", e)
                        chunk.forEach { fail(it.key, describe(e, "load the image link")) }
                    }
                }
            }

            if (names.isNotEmpty()) {
                try {
                    val assets = applicationAssets(appId)
                    for ((value, source) in names) {
                        val id = assets[source.name] ?: assets.entries.firstOrNull {
                            it.key.equals(source.name, ignoreCase = true)
                        }?.value
                        if (id == null) {
                            fail(value, "The application has no art asset named \"${source.name}\".")
                        } else {
                            succeed(value, id)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to load application assets", e)
                    names.keys.forEach { fail(it, describe(e, "load the application's art assets")) }
                }
            }

            val errors = LinkedHashMap<String, String>()
            synchronized(lock) {
                if (gen != generation) return@execute
                for ((value, entry) in results) {
                    val k = key(appId, value)
                    inFlight -= k
                    val previous = cache[k]
                    // Keep a still-valid attachment link if renewing it failed.
                    if (entry.error != null && previous?.path != null && previous.expiresAt > System.currentTimeMillis()) {
                        cache[k] = Entry(previous.path, null, previous.expiresAt, 0)
                    } else {
                        cache[k] = entry
                    }
                    entry.error?.let { errors[value] = it }
                }
            }
            Utils.mainThread.post { onResolved(errors) }
        }
        return true
    }

    private fun classify(raw: String): Source {
        val value = raw.trim()
        if (value.isEmpty()) return Source.Invalid("Empty image")
        if (Validation.SNOWFLAKE.matcher(value).matches()) return Source.Ready(value)
        if (!Validation.looksLikeUrl(value)) return Source.AssetName(value)
        val url = if (value.startsWith("www.")) "https://$value" else value
        if (!Validation.isWebUrl(url)) return Source.Invalid("Enter a full link starting with https://")
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        if (!isDiscordMediaHost(host)) return Source.External(url)

        val path = uri.encodedPath.orEmpty().removePrefix("/")
        if (path.isEmpty()) return Source.Invalid("This Discord link doesn't point to an image.")
        if (path.startsWith("attachments/") || path.startsWith("ephemeral-attachments/")) {
            val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
            val cdnUrl = "https://cdn.discordapp.com/$path$query"
            return Source.Attachment(cdnUrl, attachmentExpiry(cdnUrl) ?: 0L)
        }
        val mp = mediaPath(url) ?: return Source.Invalid("This Discord link doesn't point to an image.")
        if (mp.length > MAX_ASSET_LENGTH) return Source.Invalid("The link is too long for Discord. Try a shorter link.")
        return Source.Ready(mp)
    }

    private fun isDiscordMediaHost(host: String) =
        host == "cdn.discordapp.com" || host == "media.discordapp.net" ||
            (host.startsWith("images-ext-") && host.endsWith(".discordapp.net"))

    /** "https://cdn.discordapp.com/a/b.png?x=1" -> "mp:a/b.png?x=1" (media.discordapp.net serves the same paths). */
    private fun mediaPath(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val path = uri.encodedPath.orEmpty().removePrefix("/")
        if (path.isEmpty()) return null
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        return "mp:$path$query"
    }

    /** Signed attachment links carry their expiry as hex Unix seconds in "ex". */
    private fun attachmentExpiry(url: String): Long? {
        val ex = runCatching { Uri.parse(url).getQueryParameter("ex") }.getOrNull() ?: return null
        return try {
            java.lang.Long.parseLong(ex, 16) * 1000
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun refreshAttachments(urls: List<String>): Map<String, String> {
        val body = JSONObject().put("attachment_urls", JSONArray(urls)).toString()
        val json = post("/attachments/refresh-urls", body)
        val list = JSONObject(json).optJSONArray("refreshed_urls") ?: JSONArray()
        val result = HashMap<String, String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val original = item.optString("original")
            val refreshed = item.optString("refreshed")
            if (original.isNotEmpty() && refreshed.isNotEmpty()) result[original] = refreshed
        }
        // Fall back to order if Discord normalized the original URL.
        if (result.size < urls.size && list.length() == urls.size) {
            for (i in 0 until urls.size) {
                if (urls[i] !in result) list.optJSONObject(i)?.optString("refreshed")?.takeIf { it.isNotEmpty() }?.let {
                    result[urls[i]] = it
                }
            }
        }
        return result
    }

    private fun proxyExternal(appId: Long, urls: List<String>): Map<String, String> {
        val body = JSONObject().put("urls", JSONArray(urls)).toString()
        val list = JSONArray(post("/applications/$appId/external-assets", body))
        val result = HashMap<String, String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            var path = item.optString("external_asset_path").trim()
            path = path.removePrefix("https://media.discordapp.net/").removePrefix("/")
            if (path.isEmpty()) continue
            val url = item.optString("url").takeIf { it in urls } ?: urls.getOrNull(i)?.takeIf { list.length() == urls.size }
            if (url != null) result[url] = if (path.startsWith("mp:")) path else "mp:$path"
        }
        return result
    }

    /** Art asset name -> asset ID. */
    private fun applicationAssets(appId: Long): Map<String, String> {
        val json = Http.Request.newDiscordRNRequest("/oauth2/applications/$appId/assets").use { request ->
            request.setRequestTimeout(TIMEOUT)
            val response = request.execute()
            if (!response.ok()) throw HttpError(response.statusCode)
            response.text()
        }
        val list = JSONArray(json)
        val result = LinkedHashMap<String, String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val id = item.optString("id")
            if (name.isNotEmpty() && id.isNotEmpty()) result[name] = id
        }
        return result
    }

    private fun post(path: String, body: String): String = Http.Request.newDiscordRNRequest(path, "POST").use { request ->
        request.setRequestTimeout(TIMEOUT)
        request.setHeader("Content-Type", "application/json")
        val response = request.executeWithBody(body)
        if (!response.ok()) throw HttpError(response.statusCode)
        response.text()
    }

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private fun describe(e: Exception, action: String) = when {
        e is HttpError && e.code == 404 -> "Application not found. Check the application ID."
        e is HttpError && e.code == 429 -> "Discord is rate limiting requests. Try again in a minute."
        e is HttpError && e.code == 400 -> "Discord couldn't $action. Check the link points straight to an image."
        e is HttpError -> "Discord couldn't $action (HTTP ${e.code})."
        else -> "Couldn't $action. Check your connection."
    }

    companion object {
        /** Discord rejects asset strings longer than this. */
        const val MAX_ASSET_LENGTH = 313
        private const val TIMEOUT = 10_000
        private const val RETRY_DELAY = 60_000L
        private const val REFRESH_BEFORE_EXPIRY = 60 * 60 * 1000L
        private const val DEFAULT_LIFETIME = 12 * 60 * 60 * 1000L
    }
}
