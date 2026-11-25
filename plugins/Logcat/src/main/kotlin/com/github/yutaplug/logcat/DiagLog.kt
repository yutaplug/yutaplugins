package com.github.yutaplug.logcat

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.regex.Pattern

/**
 * Discord network timings and their totals. Lines go to [sink] (Logcat's own buffer, tagged
 * [NetworkTimings.TAG]). Only Discord hosts are recorded, URLs are reduced to their shape (no query strings,
 * IDs, tokens or file names), and errors keep only their type and errno code, since their messages can contain
 * the device's own IP address.
 */
internal object DiagLog {
    @Volatile var sink: ((String) -> Unit)? = null

    /** Discord IPs seen in DNS answers, so connects to bare IPs can still be matched to Discord. */
    private val discordIps = HashMap<String, String>()

    val stats = Stats()

    class Family {
        var connects = 0
        var failures = 0
        var totalMs = 0L
        var failedMs = 0L
    }

    class Stats {
        val v4 = Family()
        val v6 = Family()
        var dnsLookups = 0
        var dnsTotalMs = 0L
        var uploads = 0
        var uploadBytes = 0L
        var uploadMs = 0L
        var downloads = 0
        var downloadBytes = 0L
        var downloadMs = 0L
        var requestFailures = 0
    }

    fun add(line: String) {
        sink?.invoke(line)
    }

    @Synchronized
    fun clear() {
        val s = stats
        for (f in listOf(s.v4, s.v6)) {
            f.connects = 0
            f.failures = 0
            f.totalMs = 0
            f.failedMs = 0
        }
        s.dnsLookups = 0
        s.dnsTotalMs = 0
        s.uploads = 0
        s.uploadBytes = 0
        s.uploadMs = 0
        s.downloads = 0
        s.downloadBytes = 0
        s.downloadMs = 0
        s.requestFailures = 0
    }

    @Synchronized
    fun rememberIps(host: String, addresses: Array<InetAddress>) {
        for (a in addresses) a.hostAddress?.let { discordIps[it] = host }
    }

    @Synchronized
    fun hostForIp(ip: String?): String? = ip?.let { discordIps[it] }

    fun isDiscordHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.toLowerCase(Locale.US)
        return h == UPLOAD_HOST || DISCORD_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /** Google Cloud Storage bucket that Aliucord's UploadSize core plugin uploads attachments to. */
    const val UPLOAD_HOST = "discord-attachments-uploads-prd.storage.googleapis.com"

    private val DISCORD_DOMAINS = listOf(
        "discord.com",
        "discordapp.com",
        "discordapp.net",
        "discord.gg",
        "discord.media",
        "discordcdn.com",
    )

    private val CDN_HOSTS = listOf("cdn.discordapp.com", "media.discordapp.net", "cdn.discord.com")

    /** Segments after these are user-chosen codes or secrets (invite codes, webhook tokens and so on). */
    private val SECRET_PARENTS = setOf("invites", "invite", "webhooks", "interactions", "templates", "gifts")

    private val WORD = Pattern.compile("^(?:[a-z@_-]{1,24}|v\\d{1,2})$")
    private val DIGITS = Pattern.compile("^\\d+$")
    private val EXTENSION = Pattern.compile("^[A-Za-z0-9]{1,5}$")

    /** Reduces a URL path to its shape, e.g. `/attachments/:id/:id/(file).png` (written with a star) or `/api/v9/channels/:id/messages`. */
    fun pathShape(host: String, segments: List<String>): String {
        val cdn = CDN_HOSTS.any { host.equals(it, ignoreCase = true) } || host.startsWith("images-ext-") ||
            host.equals(UPLOAD_HOST, ignoreCase = true)
        if (host.equals(UPLOAD_HOST, ignoreCase = true)) {
            // Upload URLs are made of the upload's own IDs and file name; keep only the extension.
            val name = segments.lastOrNull { it.isNotEmpty() } ?: return "/"
            val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
            return if (EXTENSION.matcher(ext).matches()) "/(upload).${ext.toLowerCase(Locale.US)}" else "/(upload)"
        }
        val out = ArrayList<String>()
        var previous = ""
        for ((i, raw) in segments.withIndex()) {
            if (raw.isEmpty()) continue
            val last = i == segments.size - 1
            val shaped = when {
                previous == "external" -> {
                    // Media proxy URLs embed the original (non-Discord) URL; drop all of it.
                    out.add("*")
                    break
                }
                DIGITS.matcher(raw).matches() -> ":id"
                last && raw.contains('.') -> {
                    val ext = raw.substringAfterLast('.')
                    if (EXTENSION.matcher(ext).matches()) "*." + ext.toLowerCase(Locale.US) else "*"
                }
                previous in SECRET_PARENTS -> "*"
                cdn && out.isNotEmpty() -> "*"
                WORD.matcher(raw).matches() -> raw
                else -> "*"
            }
            out.add(shaped)
            previous = raw
        }
        return "/" + out.joinToString("/")
    }

    private val ERRNO = Pattern.compile("\\b(E[A-Z]{3,})\\b")

    /** Exception type plus errno code only; the message itself can include the device's IP. */
    fun describe(error: Throwable?): String {
        if (error == null) return "unknown error"
        var e: Throwable = error
        // Unwrap reflection/OkHttp wrappers to the real cause.
        while (e.cause != null && e.cause !== e && (e.javaClass.name.contains("Invocation") || e.message == null)) {
            e = e.cause!!
        }
        val errno = e.message?.let { msg -> ERRNO.matcher(msg).let { if (it.find()) it.group(1) else null } }
        return if (errno != null) "${e.javaClass.simpleName} $errno" else e.javaClass.simpleName
    }

    fun family(address: InetAddress?): String = if (address is Inet6Address) "IPv6" else "IPv4"

    fun size(bytes: Long): String = when {
        bytes < 0 -> "? bytes"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    }

    fun speed(bytes: Long, ms: Long): String {
        if (bytes <= 0 || ms <= 0) return ""
        return " (" + size(bytes * 1000 / ms) + "/s)"
    }
}
