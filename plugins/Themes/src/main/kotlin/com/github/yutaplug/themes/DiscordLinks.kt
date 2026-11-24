package com.github.yutaplug.themes

import android.net.Uri
import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.regex.Pattern

/**
 * Discord CDN and media proxy links in themes. Attachment links are signed and expire after about a
 * day, so they are renewed through Discord's `attachments/refresh-urls` endpoint (which also signs old,
 * unsigned links) right before downloading. Other CDN paths, like emojis, don't expire.
 */
internal object DiscordLinks {
    private val discordHost = Pattern.compile(
        "^https?://(www\\.)?(cdn\\.discordapp\\.com|media\\.discordapp\\.net|cdn\\.discord\\.com|media\\.discord\\.net)/",
        Pattern.CASE_INSENSITIVE,
    )

    /** The endpoint takes at most this many links per request. */
    private const val BATCH = 50
    private const val TIMEOUT = 15_000

    /** Renewed links are refreshed this long before they expire. */
    private const val MARGIN = 10 * 60 * 1000L
    private const val DEFAULT_LIFETIME = 12 * 60 * 60 * 1000L

    private class Renewed(val url: String, val expiresAt: Long)

    private val renewed = HashMap<String, Renewed>()

    /** Links Discord reported as deleted, by the link as written in the theme. */
    private val gone = Collections.synchronizedSet(HashSet<String>())

    fun isDiscordUrl(url: String) = discordHost.matcher(url.trim()).find()

    /** Whether downloading [url] showed that the file was deleted from Discord. */
    fun isGone(url: String) = url.trim() in gone

    /** "https://cdn.discordapp.com/attachments/..." without the signature, or null if not an attachment. */
    private fun attachment(url: String): String? {
        if (!isDiscordUrl(url)) return null
        val path = Uri.parse(url.trim()).encodedPath.orEmpty().removePrefix("/")
        if (!path.startsWith("attachments/") && !path.startsWith("ephemeral-attachments/")) return null
        return "https://cdn.discordapp.com/$path"
    }

    private fun cached(base: String): String? = synchronized(renewed) {
        renewed[base]?.takeIf { it.expiresAt - MARGIN > System.currentTimeMillis() }?.url
    }

    /**
     * Renews every attachment link in [urls] that isn't renewed yet, with one request per [BATCH]
     * links so a theme full of Discord links doesn't run into rate limits. Blocking.
     */
    fun renew(urls: Collection<String>) {
        val bases = urls.mapNotNull { attachment(it) }.distinct().filter { cached(it) == null }
        var start = 0
        while (start < bases.size) {
            val chunk = bases.subList(start, minOf(start + BATCH, bases.size))
            start += BATCH
            val body = JSONObject().put("attachment_urls", JSONArray(chunk)).toString()
            val text = Http.Request.newDiscordRNRequest("/attachments/refresh-urls", "POST").use { request ->
                request.setRequestTimeout(TIMEOUT)
                request.setHeader("Content-Type", "application/json")
                val response = request.executeWithBody(body)
                response.assertOk()
                response.text()
            }
            val list = JSONObject(text).optJSONArray("refreshed_urls") ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val refreshed = item.optString("refreshed")
                if (refreshed.isEmpty()) continue
                // Fall back to order if Discord normalized the original link.
                val original = attachment(item.optString("original"))?.takeIf { it in chunk }
                    ?: chunk.getOrNull(i)?.takeIf { list.length() == chunk.size }
                    ?: continue
                synchronized(renewed) { renewed[original] = Renewed(refreshed, expiry(refreshed)) }
            }
        }
    }

    /** Signed links carry their expiry as hex Unix seconds in "ex". */
    private fun expiry(url: String): Long {
        val ex = runCatching { Uri.parse(url).getQueryParameter("ex") }.getOrNull()
        val seconds = ex?.let { runCatching { java.lang.Long.parseLong(it, 16) }.getOrNull() }
        return seconds?.times(1000) ?: (System.currentTimeMillis() + DEFAULT_LIFETIME)
    }

    /** The link to download [url] from right now: attachment links are renewed first. Blocking. */
    fun resolve(url: String): String {
        val link = url.trim()
        val base = attachment(link)
            // Old themes sometimes use http, which newer Android refuses.
            ?: return if (link.startsWith("http://", ignoreCase = true)) "https://" + link.substring(7) else link
        cached(base)?.let { return it }
        renew(listOf(link))
        return cached(base) ?: run {
            gone += link
            throw IllegalStateException("Discord didn't renew the link, the file was probably deleted: $link")
        }
    }

    /**
     * Downloads [url] into [target], renewing it first when it's an attachment link. Remembers links
     * whose file was deleted from Discord, see [isGone]. Blocking.
     */
    fun download(url: String, target: File) {
        val link = url.trim()
        val source = resolve(link)
        try {
            Http.Request(source).use { it.execute().apply { assertOk() }.saveToFile(target) }
        } catch (error: Http.HttpException) {
            if (error.statusCode == 403 || error.statusCode == 404) {
                gone += link
                throw IllegalStateException("The file was deleted from Discord: $link")
            }
            throw error
        }
    }
}
