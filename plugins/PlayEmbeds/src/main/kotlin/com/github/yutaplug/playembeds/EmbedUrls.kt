package com.github.yutaplug.playembeds

import android.net.Uri
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.utilities.embed.EmbedResourceUtils
import java.util.Locale

internal sealed class EmbedLink {
    data class InlineVideo(val embed: MessageEmbed) : EmbedLink()
    data class Hosted(val embed: HostedEmbed) : EmbedLink()
}

internal data class HostedEmbed(
    val url: String,
    val provider: HostedProvider,
    val title: String? = null,
    val sourceEmbed: MessageEmbed? = null,
)

internal enum class HostedProvider { YOUTUBE, SOUNDCLOUD, SPOTIFY, TWITCH, TIKTOK }

/** Decides which URLs can play locally and which provider resolves them. */
internal object EmbedUrls {
    var fxVideoResolver: FxVideoResolver? = null
    var tikTokResolver: TikTokResolver? = null
    private const val MAX_REDIRECT_DEPTH = 4

    private val VIDEO_EXTENSIONS = setOf(
        "3gp", "avi", "flv", "m3u8", "m4v", "mkv", "mov", "mp4",
        "mpd", "mpeg", "mpg", "ogv", "ts", "webm",
    )
    private val AUDIO_EXTENSIONS = setOf(
        "aac", "aiff", "amr", "flac", "m4a", "mid", "midi", "mp3",
        "oga", "ogg", "opus", "wav", "weba",
    )
    private val DIRECT_MEDIA_HOSTS = setOf(
        "cdn.discordapp.com", "cdn.discordapp.net", "media.discordapp.com",
        "media.discordapp.net", "googlevideo.com", "cf-media.sndcdn.com", "video.twimg.com",
    )
    private val GIF_EXTENSIONS = setOf("gif", "gifv")
    private val GIF_HOSTS = setOf("giphy.com", "klipy.com", "tenor.com")
    private val GIF_PROVIDER_NAMES = setOf("giphy", "klipy", "tenor")
    private val HOSTED_PROVIDER_NAMES = setOf("youtube", "soundcloud", "spotify", "twitch", "tiktok")

    fun embedLink(embed: MessageEmbed): EmbedLink? {
        if (isGifEmbed(embed) || isDirectLinkedMediaEmbed(embed)) return null
        if (genericVideoUrl(embed) != null) return EmbedLink.InlineVideo(embed)
        return hostedEmbed(embed)?.let { EmbedLink.Hosted(it) }
    }

    fun genericVideoUrl(embed: MessageEmbed): String? {
        if (isGifEmbed(embed)) return null
        if (isDirectLinkedMediaEmbed(embed) || EmbedResourceUtils.INSTANCE.isInlineEmbed(embed)) return null
        fxLookupUrl(embed)?.let { return fxVideoResolver?.cached(it)?.takeIf(FxVideoResolver::isVideo) }
        val video = embed.m() ?: return null
        val providerName = embed.g()?.a()?.lowercase(Locale.ROOT)
        if (providerName in HOSTED_PROVIDER_NAMES) return null

        val urls = listOfNotNull(embed.l(), video.c(), video.b()).filter(::isHttpUrl)
        if (urls.isEmpty() || urls.any(::requiresHostedPlayer)) return null
        // Discord 126.21's EmbedVideo has no contentType. A separate VIDEO
        // source URL is progressive media; use its proxy as Discord does.
        val allowExtensionless = embed.k() == EmbedType.VIDEO
        return listOfNotNull(video.b(), video.c()).firstNotNullOfOrNull { url ->
            resolveEmbeddedMediaUrl(url, allowExtensionless && url != embed.l())
        }
    }

    /**
     * The URL [FxVideoResolver] looks the video up from: the embed's link when it has
     * no video, or X's HTML player page Discord now puts in `video.url` (with no
     * proxy URL), which ExoPlayer can't read. Null when the embed's video plays as is.
     */
    fun fxLookupUrl(embed: MessageEmbed): String? {
        val resolver = fxVideoResolver ?: return null
        val video = embed.m()
        val url = if (video == null) embed.l() else video.c()?.takeIf { video.b() == null }
        return url?.takeIf(resolver::handles)
    }

    private fun resolveEmbeddedMediaUrl(url: String, allowExtensionless: Boolean, depth: Int = 0): String? {
        if (!isHttpUrl(url)) return null
        if (isMediaUrl(url, hasEmbedVideo = true)) return url
        if (depth < MAX_REDIRECT_DEPTH) {
            val uri = Uri.parse(url)
            try {
                uri.queryParameterNames.asSequence()
                    .mapNotNull(uri::getQueryParameter)
                    .filter(::isHttpUrl)
                    .firstNotNullOfOrNull { nestedUrl ->
                        resolveEmbeddedMediaUrl(nestedUrl, allowExtensionless, depth + 1)
                    }
                    ?.let { return it }
            } catch (_: UnsupportedOperationException) {
                // Keep the original URL if Android cannot inspect its query.
            }
        }
        return if (allowExtensionless) url else null
    }

    fun hostedEmbed(embed: MessageEmbed): HostedEmbed? {
        if (isGifEmbed(embed) || isDirectLinkedMediaEmbed(embed)) return null
        if (genericVideoUrl(embed) != null) return null
        val candidates = listOfNotNull(embed.l(), embed.m()?.c(), embed.m()?.b(), embed.g()?.b())
        for (url in candidates) {
            if (!isHttpUrl(url)) continue
            val provider = hostedProvider(url) ?: continue
            return HostedEmbed(url, provider, embed.j(), embed)
        }
        return null
    }

    fun hostedEmbed(url: String): HostedEmbed? {
        if (!isHttpUrl(url) || isGifUrl(url) || isMediaLink(url)) return null
        val provider = hostedProvider(url) ?: return null
        return HostedEmbed(url, provider)
    }

    fun isMediaLink(url: String): Boolean =
        isHttpUrl(url) && !isGifUrl(url) &&
            isMediaUrl(url, hasEmbedVideo = isHost(Uri.parse(url).host, "video.twimg.com"))

    private fun isGifEmbed(embed: MessageEmbed): Boolean {
        if (embed.k() == EmbedType.GIFV) return true
        val providerName = embed.g()?.a()?.lowercase(Locale.ROOT)
        if (providerName in GIF_PROVIDER_NAMES) return true
        return listOfNotNull(embed.l(), embed.m()?.c(), embed.m()?.b(), embed.g()?.b()).any(::isGifUrl)
    }

    private fun isDirectLinkedMediaEmbed(embed: MessageEmbed): Boolean {
        val url = embed.l() ?: return false
        return isHttpUrl(url) && isMediaUrl(url, hasEmbedVideo = false)
    }

    private fun isGifUrl(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        val uri = Uri.parse(url)
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in GIF_EXTENSIONS || GIF_HOSTS.any { isHost(uri.host, it) }
    }

    /**
     * Twitch and TikTok links only count when they're something that can play (a clip,
     * a video); channels, live streams and profiles keep Discord's own behavior.
     */
    private fun hostedProvider(url: String): HostedProvider? {
        val host = Uri.parse(url).host
        return when {
            isHost(host, "youtube.com") || isHost(host, "youtu.be") -> HostedProvider.YOUTUBE
            isHost(host, "soundcloud.com") -> HostedProvider.SOUNDCLOUD
            isHost(host, "spotify.com") || isHost(host, "spotify.link") -> HostedProvider.SPOTIFY
            TwitchClipResolver.clipSlug(url) != null -> HostedProvider.TWITCH
            tikTokResolver?.handles(url) == true -> HostedProvider.TIKTOK
            else -> null
        }
    }

    /** URLs that are web players, never media files, whether or not they can be played. */
    private fun requiresHostedPlayer(url: String): Boolean {
        val host = Uri.parse(url).host
        return hostedProvider(url) != null || isHost(host, "twitch.tv") || isHost(host, "tiktok.com")
    }

    private fun isMediaUrl(url: String, hasEmbedVideo: Boolean): Boolean {
        val uri = Uri.parse(url)
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return false
        return extension in VIDEO_EXTENSIONS || extension in AUDIO_EXTENSIONS ||
            (hasEmbedVideo && DIRECT_MEDIA_HOSTS.any { isHost(uri.host, it) })
    }

    fun isHttpUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    fun isHost(host: String?, domain: String): Boolean {
        val normalized = host?.lowercase(Locale.ROOT) ?: return false
        return normalized == domain || normalized.endsWith(".$domain")
    }

    fun youtubeVideoId(uri: Uri): String? {
        val host = uri.host?.lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().trim('/')
        return when {
            isHost(host, "youtu.be") -> path.substringBefore('/')
            path.startsWith("embed/") -> path.removePrefix("embed/").substringBefore('/')
            path.startsWith("shorts/") -> path.removePrefix("shorts/").substringBefore('/')
            path.startsWith("live/") -> path.removePrefix("live/").substringBefore('/')
            path == "watch" -> uri.getQueryParameter("v")
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }
}
