package com.github.yutaplug.fixes.gifdownload

import java.net.URI
import java.util.Locale

/** Giphy's rendition filenames describe the format, rather than the GIF's title. */
internal object GiphyFileNames {
    fun fromEmbed(title: String?, pageUrl: String?): String? = meaningfulName(title) ?: pageName(pageUrl)

    fun choose(fileName: String?, cachedTitle: String?, pageUrl: String?, mediaUrl: String): String =
        meaningfulName(fileName)
            ?: meaningfulName(cachedTitle)
            ?: pageName(pageUrl)
            ?: mediaId(mediaUrl)?.let { "giphy_$it" }
            ?: "giphy"

    private fun meaningfulName(value: String?): String? {
        val name = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.startsWith("https://", true) || name.startsWith("http://", true)) return null
        val stem = name.replace(MEDIA_EXTENSION, "")
        if (GENERIC_RENDITION.matches(stem) || stem.lowercase(Locale.ROOT) in GENERIC_NAMES) return null
        return safeName(stem)
    }

    private fun pageName(url: String?): String? {
        val page = parse(url) ?: return null
        if (!isGiphyHost(page.host)) return null
        val segments = page.path?.trim('/')?.split('/') ?: return null
        if (segments.size != 2 || segments[0] !in listOf("gifs", "stickers")) return null
        val slug = segments[1]
        val separator = slug.lastIndexOf('-')
        if (separator < 1) return null // An ID-only URL has no human-readable name.
        val title = slug.substring(0, separator).replace('-', ' ')
        return meaningfulName(title)
    }

    private fun mediaId(url: String): String? {
        val media = parse(url) ?: return null
        if (!isGiphyHost(media.host)) return null
        val segments = media.path?.trim('/')?.split('/') ?: return null
        if (segments.firstOrNull() != "media" || segments.size < 3) return null
        return segments[segments.size - 2].takeIf { GIF_ID.matches(it) }
    }

    private fun parse(url: String?): URI? = try {
        url?.let(::URI)
    } catch (_: Exception) {
        null
    }

    private fun isGiphyHost(host: String?): Boolean = host.equals("giphy.com", true) ||
        host?.lowercase(Locale.ROOT)?.endsWith(".giphy.com") == true

    private fun safeName(value: String): String? = value
        .replace(INVALID_FILENAME_CHARACTER, "_")
        .trim()
        .trimEnd('.')
        .take(160)
        .takeIf { it.isNotEmpty() }

    private val MEDIA_EXTENSION = Regex("(?i)\\.(?:gif|mp4|webm|webp|png|jpe?g)$")
    private val GENERIC_RENDITION = Regex("(?i)(?:giphy(?:[-_].*)?|[0-9]+w?(?:_[sd])?)")
    private val GIF_ID = Regex("[A-Za-z0-9]+")
    private val INVALID_FILENAME_CHARACTER = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val GENERIC_NAMES = setOf("gif", "image", "original", "source", "giphy gif", "giphy.com")
}
