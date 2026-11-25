package com.github.yutaplug.logcat

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Removes tokens, credentials, emails, signed URL parameters and IP addresses from log text.
 * Uses java.util.regex because Discord ships a stripped kotlin.text.Regex.
 */
object Redactor {
    private val AUTH_HEADER = Pattern.compile(
        "(?i)\\b((?:proxy-)?authorization|cookie|set-cookie|x-super-properties|x-fingerprint|x-captcha-key)" +
            "(\"?\\s*[:=]\\s*\"?)(?:(?:bot|bearer|basic)\\s+)?[^\\s\",;}]+",
    )
    private val SECRET_FIELD = Pattern.compile(
        "(?i)(\"?\\b(?:token|access_token|refresh_token|password|new_password|ticket|captcha_key|secret)\"?" +
            "\\s*[:=]\\s*\"?)[^\\s\",}&]+",
    )
    private val MFA_TOKEN = Pattern.compile("mfa\\.[A-Za-z0-9_-]{20,}")
    private val TOKEN = Pattern.compile(
        "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{18,32}\\.[A-Za-z0-9_-]{5,8}\\.[A-Za-z0-9_-]{25,}(?![A-Za-z0-9_-])",
    )
    private val EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val URL_QUERY = Pattern.compile("(?i)\\b((?:https?|wss?)://[^\\s?#\"'<>]+)\\?[^\\s\"'<>]*")
    private val SIGNED_PARAM = Pattern.compile("(?i)(?<![A-Za-z0-9_])(upload_id|upload_filename|ex|is|hm|signature)=[^\\s&\"']+")
    private val IPV4 = Pattern.compile("(?<![\\w.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\w]|\\.\\d)")
    private val IPV6 = Pattern.compile("(?<![\\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{1,4}|\\d{1,3}(?:\\.\\d{1,3}){3})?(?![\\w:])")

    fun redact(input: String): String {
        var s = input
        s = AUTH_HEADER.matcher(s).replaceAll("$1$2[redacted]")
        s = MFA_TOKEN.matcher(s).replaceAll("[token]")
        s = TOKEN.matcher(s).replaceAll("[token]")
        s = SECRET_FIELD.matcher(s).replaceAll("$1[redacted]")
        s = URL_QUERY.matcher(s).replaceAll("$1?[query]")
        s = SIGNED_PARAM.matcher(s).replaceAll("$1=[redacted]")
        s = EMAIL.matcher(s).replaceAll("[email]")
        s = replace(IPV4, s) { m -> if (isPublicIpv4(m)) "[ip]" else null }
        s = replace(IPV6, s) { m -> if (isRedactableIpv6(m.group())) "[ip]" else null }
        return s
    }

    private fun isPublicIpv4(m: Matcher): Boolean {
        var allZero = true
        for (i in 1..4) {
            val octet = m.group(i)?.toIntOrNull() ?: return false
            if (octet > 255) return false // version numbers like 1.2.300.4
            if (octet != 0) allZero = false
        }
        if (m.group(1) == "127") return false // loopback
        return !allZero
    }

    private fun isRedactableIpv6(text: String): Boolean {
        val doubleColon = text.indexOf("::")
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return false
        val colons = text.count { it == ':' }
        // Times like 12:34:56 have no "::" and fewer than 7 colons, so only accept compressed or full addresses.
        if (doubleColon < 0 && colons < 7) return false
        if (text.none { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        val lower = text.lowercase()
        if (lower == "::1" || lower == "0:0:0:0:0:0:0:1" || lower == "::") return false
        if (lower.startsWith("::ffff:127.")) return false
        // Groups must be at most 4 hex digits (the regex allows an empty group around "::").
        val last = lower.substringAfterLast(':')
        if (last.contains('.')) return true
        return lower.split(':').all { it.length <= 4 }
    }

    private inline fun replace(pattern: Pattern, input: String, fn: (Matcher) -> String?): String {
        val m = pattern.matcher(input)
        if (!m.find()) return input
        val sb = StringBuilder(input.length)
        var last = 0
        do {
            val replacement = fn(m)
            if (replacement != null) {
                sb.append(input, last, m.start()).append(replacement)
                last = m.end()
            }
        } while (m.find())
        sb.append(input, last, input.length)
        return sb.toString()
    }
}
