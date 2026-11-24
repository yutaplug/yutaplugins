package com.github.yutaplug.fixes.gifdownload

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import com.aliucord.Utils
import com.aliucord.patcher.PreHook
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.models.gifpicker.dto.GifDto
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.utilities.io.NetworkUtils
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import com.github.yutaplug.fixes.Fix
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.Function1

/** Downloads the GIF image URL for hosted GIFs and keeps its filename in sync. */
internal class GifDownloadFix :
    Fix(
        "GifDownloadFix",
        "GIF downloads",
        "Fixes downloading GIFs not working.",
    ) {
    private val pendingGifUrls = LinkedHashMap<Uri, Uri>(32, 0.75f, true)
    private val giphyNames = LinkedHashMap<Uri, String>(32, 0.75f, true)
    private val klipyAliases = LinkedHashMap<Uri, Boolean>(32, 0.75f, true)
    private var klipyDownloads = Executors.newSingleThreadExecutor()
    private val activeKlipyDownloads = HashSet<Uri>()
    private val downloadNotifications = HashSet<KlipyDownloadNotification>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Discord's bundled Unit singleton is named "a"; un-obfuscated Kotlin uses
    // "INSTANCE". Return the runtime singleton from replacement callbacks.
    private val callbackUnit: Unit by lazy {
        Unit::class.java.fields
            .first { field ->
                Modifier.isStatic(field.modifiers) && field.type == Unit::class.java
            }.get(null) as Unit
    }

    @Volatile
    private var running = false

    override fun start(context: Context) {
        running = true
        if (klipyDownloads.isShutdown) klipyDownloads = Executors.newSingleThreadExecutor()
        patchGifPickerResults()
        patchEmbedRows()
        patchEmbedLaunch()
        patchMediaViewerDownload()
        patchQuickDownload()

        val downloadFile = NetworkUtils::class.java.getDeclaredMethod(
            "downloadFile",
            Context::class.java,
            Uri::class.java,
            String::class.java,
            String::class.java,
            Function1::class.java,
            Function1::class.java,
        )

        patcher.patch(
            downloadFile,
            PreHook { frame ->
                val uri = frame.args.getOrNull(1) as? Uri ?: return@PreHook
                val pendingUri = cachedGifUrl(uri)
                val provider = providerFor(uri) ?: pendingUri?.let(::providerFor)
                    ?: synchronized(klipyAliases) {
                        if (klipyAliases[normalizedUri(uri)] == true) GifProvider.KLIPY else null
                    } ?: return@PreHook
                val downloadUri = pendingUri ?: resolveGifUri(provider, uri, null)
                if (provider == GifProvider.KLIPY) {
                    val downloadContext = frame.args.getOrNull(0) as? Context ?: return@PreHook

                    @Suppress("UNCHECKED_CAST")
                    val onSuccess = frame.args[4] as Function1<String, Unit>

                    @Suppress("UNCHECKED_CAST")
                    val onError = frame.args[5] as Function1<Throwable, Unit>
                    val fileName = withGifExtension(frame.args.getOrNull(2) as? String, downloadUri ?: uri)
                    frame.setResult(null)
                    downloadKlipyGif(downloadContext, uri, downloadUri, fileName, onSuccess, onError)
                    return@PreHook
                }
                if (downloadUri == null) return@PreHook
                frame.args[1] = downloadUri

                val fileName = frame.args.getOrNull(2) as? String
                frame.args[2] = if (provider == GifProvider.GIPHY) {
                    giphyFileName(fileName, uri, downloadUri)
                } else {
                    withGifExtension(fileName, downloadUri)
                }
            },
        )
        logger.info("Klipy direct download fix 3 active")
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        klipyDownloads.shutdownNow()
        synchronized(downloadNotifications) {
            downloadNotifications.forEach { it.cancel() }
            downloadNotifications.clear()
        }
        synchronized(activeKlipyDownloads) { activeKlipyDownloads.clear() }
        synchronized(klipyAliases) { klipyAliases.clear() }
        synchronized(giphyNames) { giphyNames.clear() }
    }

    private fun downloadKlipyGif(
        context: Context,
        originalUri: Uri,
        gifUri: Uri?,
        fileName: String,
        onSuccess: Function1<String, Unit>,
        onError: Function1<Throwable, Unit>,
    ) {
        if (!running) return
        val mediaUri = unwrapProviderUrl(originalUri, GifProvider.KLIPY)
        if (!synchronized(activeKlipyDownloads) { activeKlipyDownloads.add(mediaUri) }) {
            Utils.showToast("This Klipy GIF is already downloading", false)
            return
        }
        val notification = KlipyDownloadNotification(context.applicationContext, fileName) { error ->
            logger.error("Could not show the Klipy download notification", error)
            callbackUnit
        }
        synchronized(downloadNotifications) { downloadNotifications.add(notification) }
        notification.progress("Queued")
        try {
            klipyDownloads.execute {
                try {
                    logger.info("Starting Klipy GIF download: $mediaUri")
                    val progress = object : KlipyGifDownloader.Progress {
                        private var previousStage = ""

                        override fun update(stage: String, current: Int, total: Int) {
                            if (stage != previousStage) {
                                logger.info("Klipy GIF: $stage")
                                previousStage = stage
                            }
                            notification.progress(stage, current, total)
                        }
                    }
                    val savedName = KlipyGifDownloader.download(
                        context.applicationContext,
                        originalUri,
                        gifUri,
                        fileName,
                        mediaUri,
                        progress,
                    )
                    logger.info("Saved Klipy GIF to Downloads/$savedName")
                    notification.complete(savedName)
                    mainHandler.post { if (running) onSuccess.invoke(savedName) }
                } catch (error: Throwable) {
                    logger.error("Klipy GIF download failed for $mediaUri", error)
                    notification.failed()
                    mainHandler.post { if (running) onError.invoke(error) }
                } finally {
                    synchronized(downloadNotifications) { downloadNotifications.remove(notification) }
                    synchronized(activeKlipyDownloads) { activeKlipyDownloads.remove(mediaUri) }
                }
            }
        } catch (error: Exception) {
            synchronized(downloadNotifications) { downloadNotifications.remove(notification) }
            synchronized(activeKlipyDownloads) { activeKlipyDownloads.remove(mediaUri) }
            logger.error("Could not start Klipy GIF download", error)
            notification.failed()
            onError.invoke(error)
        }
    }

    private fun patchEmbedRows() {
        val onConfigure = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
            "onConfigure",
            Int::class.javaPrimitiveType,
            ChatListEntry::class.java,
        )

        patcher.patch(
            onConfigure,
            PreHook { frame ->
                val entry = frame.args.getOrNull(1) as? EmbedEntry ?: return@PreHook
                cacheEmbedUrls(entry.embed)
            },
        )
    }

    private fun patchGifPickerResults() {
        val createFromGifDto = ModelGif.Companion::class.java.getDeclaredMethod(
            "createFromGifDto",
            GifDto::class.java,
        )

        patcher.patch(
            createFromGifDto,
            PreHook { frame ->
                val dto = frame.args.getOrNull(0) as? GifDto ?: return@PreHook
                val pageUri = parseUri(dto.getUrl())
                val sourceUri = parseUri(dto.getSrc()) ?: return@PreHook
                val provider = pageUri?.let(::providerFor) ?: providerFor(sourceUri) ?: return@PreHook
                val gifUri = resolveGifUri(provider, sourceUri, null) ?: return@PreHook

                rememberGifUrl(sourceUri, gifUri, provider)
                pageUri?.let { rememberGifUrl(it, gifUri, provider) }
            },
        )
    }

    private fun providerFor(uri: Uri): GifProvider? {
        val host = uri.host?.lowercase(Locale.ROOT)
        val url = Uri.decode(uri.toString()).lowercase(Locale.ROOT)
        return GifProvider.all.firstOrNull { provider ->
            host == provider.domain ||
                host?.endsWith(".${provider.domain}") == true ||
                // Discord's external-media proxy can hide the provider in its path.
                url.contains(provider.domain)
        }
    }

    private fun patchEmbedLaunch() {
        val launch = WidgetMedia.Companion::class.java.getDeclaredMethod(
            "launch",
            Context::class.java,
            MessageEmbed::class.java,
        )

        patcher.patch(
            launch,
            PreHook { frame ->
                val embed = frame.args.getOrNull(1) as? MessageEmbed ?: return@PreHook
                cacheEmbedUrls(embed)
            },
        )
    }

    private fun cacheEmbedUrls(embed: MessageEmbed) {
        val videos = embed.m()?.let(::urlsFor).orEmpty()
        val thumbnails = embed.h()?.let(::urlsFor).orEmpty()
        val images = embed.f()?.let(::urlsFor).orEmpty()
        val assetUris = (thumbnails + images + videos).mapNotNull(::parseUri)
        val pageUri = embed.l()?.let(::parseUri)
        val allUris = assetUris + listOfNotNull(pageUri)
        val provider = allUris.firstNotNullOfOrNull(::providerFor)
            ?: GifProvider.all.firstOrNull { it.name.equals(embed.g()?.a(), ignoreCase = true) }
            ?: return
        if (provider == GifProvider.KLIPY) allUris.forEach(::rememberKlipyAlias)
        val giphyName = if (provider == GifProvider.GIPHY) {
            GiphyFileNames.fromEmbed(embed.j(), pageUri?.toString())
        } else {
            null
        }
        if (giphyName != null) allUris.forEach { rememberGiphyName(it, giphyName) }

        // Klipy's video and GIF renditions have different IDs, so only use an
        // actual GIF URL supplied by the embed; never change the video suffix.
        val gifUri = allUris.firstNotNullOfOrNull(::cachedGifUrl)
            ?: (thumbnails + images + videos)
                .mapNotNull(::parseUri)
                .firstNotNullOfOrNull { candidate -> resolveGifUri(provider, candidate, null) }
        if (gifUri == null) return

        allUris.forEach { rememberGifUrl(it, gifUri, provider) }
        if (giphyName != null) rememberGiphyName(gifUri, giphyName)
    }

    private fun urlsFor(image: EmbedImage): List<String> = listOfNotNull(image.c(), image.b())

    private fun urlsFor(thumbnail: EmbedThumbnail): List<String> = listOfNotNull(thumbnail.c(), thumbnail.b())

    private fun urlsFor(video: EmbedVideo): List<String> = listOfNotNull(video.c(), video.b())

    private fun parseUri(url: String): Uri? = try {
        Uri.parse(url).takeIf {
            it.scheme.equals("http", ignoreCase = true) ||
                it.scheme.equals("https", ignoreCase = true)
        }
    } catch (_: Throwable) {
        null
    }

    private fun rememberGifUrl(alias: Uri, gifUri: Uri, provider: GifProvider) {
        val unwrappedAlias = unwrapProviderUrl(alias, provider)
        val keys = listOf(alias, normalizedUri(alias), unwrappedAlias, normalizedUri(unwrappedAlias)).distinct()
        synchronized(pendingGifUrls) {
            keys.forEach { key ->
                if (!pendingGifUrls.containsKey(key)) pendingGifUrls[key] = gifUri
            }
            while (pendingGifUrls.size > MAX_CACHED_URLS) {
                val oldest = pendingGifUrls.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun cachedGifUrl(uri: Uri): Uri? = synchronized(pendingGifUrls) {
        pendingGifUrls[uri]?.let { return@synchronized it }
        val provider = providerFor(uri) ?: return@synchronized null
        val unwrapped = unwrapProviderUrl(uri, provider)
        listOf(normalizedUri(uri), unwrapped, normalizedUri(unwrapped))
            .firstNotNullOfOrNull(pendingGifUrls::get)
    }

    private fun normalizedUri(uri: Uri): Uri = uri.buildUpon().clearQuery().fragment(null).build()

    private fun rememberKlipyAlias(uri: Uri) {
        synchronized(klipyAliases) {
            klipyAliases[normalizedUri(uri)] = true
            while (klipyAliases.size > MAX_CACHED_URLS) {
                val oldest = klipyAliases.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun patchMediaViewerDownload() {
        val menuHandlers = Class
            .forName(
                "com.discord.widgets.media.WidgetMedia\$onViewBoundOrOnResume\$1",
            ).declaredMethods
            .filter { it.name == "call" && it.parameterTypes.size == 2 }
        check(menuHandlers.isNotEmpty()) { "Media download action was not found" }

        menuHandlers.forEach { menuHandler ->
            patcher.patch(
                menuHandler,
                PreHook { frame ->
                    val menuItem = frame.args.getOrNull(0) as? MenuItem ?: return@PreHook
                    if (menuItem.itemId != Utils.getResId("menu_media_download", "id") &&
                        menuItem.itemId != MEDIA_DOWNLOAD_ID_126
                    ) {
                        return@PreHook
                    }

                    val listener = frame.thisObject ?: return@PreHook
                    val mediaUri = readField(listener, "\$downloadUri") as? Uri ?: return@PreHook
                    val sourceUri = readField(listener, "\$sourceUri") as? Uri
                    val provider = downloadProvider(mediaUri) ?: sourceUri?.let(::providerFor) ?: return@PreHook
                    if (provider == GifProvider.KLIPY) rememberKlipyAlias(mediaUri)
                    val widgetMedia = readField(listener, "this\$0") as? WidgetMedia ?: return@PreHook
                    val previewUri = readField(widgetMedia, "imageUri") as? Uri
                    val cachedUri = cachedGifUrl(mediaUri)
                        ?: sourceUri?.let(::cachedGifUrl)
                        ?: previewUri?.let(::cachedGifUrl)

                    val gifUri = cachedUri ?: resolveGifUri(provider, mediaUri, previewUri)

                    if (provider == GifProvider.KLIPY) {
                        val context = frame.args.getOrNull(1) as? Context ?: return@PreHook
                        val title = readField(listener, "\$title") as? String
                        val fileName = withGifExtension(title, gifUri ?: mediaUri)
                        // Replace the action itself: a cached mapping still allows
                        // Discord's original MP4 request if its helper was inlined.
                        frame.setResult(null)
                        try {
                            logger.info("Intercepted Klipy media download: $mediaUri")
                            // MediaStore owns our output on Android 10+, so no
                            // legacy storage permission callback is necessary.
                            if (Build.VERSION.SDK_INT >= 29) {
                                downloadKlipyFromAction(context, mediaUri, gifUri, fileName)
                            } else {
                                widgetMedia.requestMediaDownload {
                                    downloadKlipyFromAction(context, mediaUri, gifUri, fileName)
                                    callbackUnit
                                }
                            }
                        } catch (error: Throwable) {
                            reportHookFailure("media action", error)
                        }
                        return@PreHook
                    }
                    if (gifUri == null) return@PreHook

                    rememberGifUrl(mediaUri, gifUri, provider)
                    sourceUri?.let { rememberGifUrl(it, gifUri, provider) }
                    previewUri?.let { rememberGifUrl(it, gifUri, provider) }
                    if (provider == GifProvider.GIPHY) {
                        val title = readField(listener, "\$title") as? String
                        val fileName = giphyFileName(title, mediaUri, gifUri, sourceUri)
                        rememberGiphyName(mediaUri, fileName)
                        rememberGiphyName(gifUri, fileName)
                        // Also update the action's captured title so the name is
                        // preserved if ART has inlined NetworkUtils.downloadFile.
                        writeField(listener, "\$title", fileName)
                    }
                },
            )
        }
    }

    private fun patchQuickDownload() {
        // These callbacks run after requestMediaDownload grants permission.
        // Patch both the typed function and its Object-returning bridge.
        val callbacks = Class
            .forName(
                "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler\$onQuickDownloadClicked\$1",
            ).declaredMethods
            .filter { it.name == "invoke" && it.parameterTypes.isEmpty() }
        check(callbacks.isNotEmpty()) { "Inline download callback was not found" }
        callbacks.forEach { callback ->
            patcher.patch(
                callback,
                PreHook { frame ->
                    val listener = frame.thisObject ?: return@PreHook
                    val mediaUri = readField(listener, "\$uri") as? Uri ?: return@PreHook
                    val provider = downloadProvider(mediaUri) ?: return@PreHook
                    if (provider == GifProvider.GIPHY) {
                        val name = readField(listener, "\$fileName") as? String
                        val gifUri = cachedGifUrl(mediaUri) ?: resolveGifUri(provider, mediaUri, null) ?: mediaUri
                        val fileName = giphyFileName(name, mediaUri, gifUri)
                        rememberGiphyName(mediaUri, fileName)
                        rememberGiphyName(gifUri, fileName)
                        writeField(listener, "\$fileName", fileName)
                        return@PreHook
                    }
                    if (provider != GifProvider.KLIPY) return@PreHook
                    val context = (readField(listener, "\$weakContext") as? WeakReference<*>)?.get() as? Context
                        ?: return@PreHook
                    val name = readField(listener, "\$fileName") as? String
                    frame.setResult(if (callback.returnType == Void.TYPE) null else callbackUnit)
                    try {
                        logger.info("Intercepted Klipy inline download: $mediaUri")
                        val gifUri = cachedGifUrl(mediaUri)
                        downloadKlipyFromAction(context, mediaUri, gifUri, withGifExtension(name, gifUri ?: mediaUri))
                    } catch (error: Throwable) {
                        reportHookFailure("inline action", error)
                    }
                },
            )
        }
    }

    private fun downloadProvider(uri: Uri): GifProvider? = providerFor(uri)
        ?: synchronized(klipyAliases) {
            if (klipyAliases[normalizedUri(uri)] == true) GifProvider.KLIPY else null
        }

    private fun downloadKlipyFromAction(context: Context, mediaUri: Uri, gifUri: Uri?, fileName: String) {
        downloadKlipyGif(
            context,
            mediaUri,
            gifUri,
            fileName,
            { savedName ->
                Utils.showToast("Saved GIF to Downloads/$savedName", true)
                callbackUnit
            },
            { error ->
                Utils.showToast("Klipy GIF download failed: ${error.message ?: error.javaClass.simpleName}", true)
                callbackUnit
            },
        )
    }

    private fun reportHookFailure(stage: String, error: Throwable) {
        logger.error("Klipy $stage failed", error)
        Utils.showToast("Could not start Klipy GIF download; check Debug Logs", true)
    }

    private fun resolveGifUri(provider: GifProvider, mediaUri: Uri, previewUri: Uri?): Uri? {
        val preview = previewUri?.let { unwrapProviderUrl(it, provider) }
        if (preview != null) {
            if (provider == GifProvider.GIPHY && isGiphyStill(preview)) {
                return rewritePath(preview) { path -> path.replace("_s.gif", ".gif", ignoreCase = true) }
            }
            if (isGifFile(preview)) return preview
        }

        val media = unwrapProviderUrl(mediaUri, provider)
        if (provider == GifProvider.GIPHY) {
            if (isGiphyStill(media)) {
                return rewritePath(media) { path -> path.replace("_s.gif", ".gif", ignoreCase = true) }
            }
            if (isGifFile(media)) return media

            // GIPHY's rendition URLs share the same path; its documented
            // original.mp4 and original GIF differ only by their extension.
            return rewritePath(media) { path ->
                val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in VIDEO_EXTENSIONS) path.substringBeforeLast('.') + ".gif" else path
            }?.takeIf(::isGifFile)
        }

        // Klipy gives each format a distinct media URL. When no actual GIF is
        // available, its MP4 is converted by the download hook.
        if (isGifFile(media)) return media
        return null
    }

    private fun unwrapProviderUrl(uri: Uri, provider: GifProvider): Uri {
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host == provider.domain || host?.endsWith(".${provider.domain}") == true) return uri

        val decoded = Uri.decode(uri.toString())
        val domainStart = decoded.indexOf(provider.domain, ignoreCase = true)
        if (domainStart < 0) return uri

        // Discord's media proxy path embeds the source host and path. Recover
        // that URL before editing GIPHY's still-image rendition.
        val hostStart = decoded.lastIndexOf('/', domainStart) + 1
        if (hostStart <= 0 || hostStart >= decoded.length) return uri
        val source = decoded.substring(hostStart)
        val end = source
            .indexOfAny(charArrayOf('?', '#', '&', ' ', '"', '\'', ')', ']'))
            .let { if (it < 0) source.length else it }
        val candidate = Uri.parse("https://${source.substring(0, end)}")
        return if (providerFor(candidate) == provider) candidate else uri
    }

    private fun isGiphyStill(uri: Uri): Boolean = uri.lastPathSegment?.endsWith("_s.gif", ignoreCase = true) == true

    private fun rewritePath(uri: Uri, transform: (String) -> String): Uri? {
        val path = uri.path ?: return null
        val updatedPath = transform(path)
        if (updatedPath == path) return null
        return uri.buildUpon().path(updatedPath).build()
    }

    private fun readField(instance: Any, name: String): Any? = try {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    } catch (_: Throwable) {
        null
    }

    private fun writeField(instance: Any, name: String, value: String) {
        try {
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
        } catch (error: Exception) {
            logger.error("Could not set the Giphy download filename", error)
        }
    }

    private fun rememberGiphyName(uri: Uri, name: String) {
        val unwrapped = unwrapProviderUrl(uri, GifProvider.GIPHY)
        synchronized(giphyNames) {
            giphyNames[normalizedUri(uri)] = name
            giphyNames[normalizedUri(unwrapped)] = name
            while (giphyNames.size > MAX_CACHED_URLS) {
                val oldest = giphyNames.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun cachedGiphyName(uri: Uri): String? {
        val unwrapped = unwrapProviderUrl(uri, GifProvider.GIPHY)
        return synchronized(giphyNames) {
            giphyNames[normalizedUri(uri)] ?: giphyNames[normalizedUri(unwrapped)]
        }
    }

    private fun giphyFileName(name: String?, mediaUri: Uri, gifUri: Uri, sourceUri: Uri? = null): String {
        val cachedName = cachedGiphyName(mediaUri) ?: cachedGiphyName(gifUri)
            ?: sourceUri?.let(::cachedGiphyName)
        val resolvedName = GiphyFileNames.choose(
            name,
            cachedName,
            sourceUri?.toString(),
            unwrapProviderUrl(gifUri, GifProvider.GIPHY).toString(),
        )
        return withGifExtension(resolvedName, gifUri)
    }

    private fun isGifFile(uri: Uri): Boolean {
        val pathIsGif = uri.lastPathSegment?.endsWith(".gif", ignoreCase = true) == true
        val formatIsGif = uri.getQueryParameter("format")?.equals("gif", ignoreCase = true) == true
        return pathIsGif || formatIsGif
    }

    private fun withGifExtension(fileName: String?, uri: Uri): String {
        val name = fileName
            ?.trim()
            ?.takeUnless { it.isEmpty() }
            ?: uri.lastPathSegment?.trim()?.takeUnless { it.isEmpty() }
            ?: "image"

        val cleanName = name.substringBefore('?')
        if (cleanName.endsWith(".gif", ignoreCase = true)) {
            return cleanName
        }

        val extension = cleanName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in MEDIA_EXTENSIONS) {
            cleanName.substringBeforeLast('.') + ".gif"
        } else {
            "$cleanName.gif"
        }
    }

    private companion object {
        // Verified in Discord 126.21's WidgetMedia menu listener.
        const val MEDIA_DOWNLOAD_ID_126 = 2131364396
        const val MAX_CACHED_URLS = 256
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "gifv")
        val MEDIA_EXTENSIONS = setOf("gifv", "mp4", "webm", "webp", "jpg", "jpeg", "png")
    }

    // Kotlin 2.x emits EnumEntriesKt calls even for enums accessed via values().
    // Use ordinary singleton instances to support Aliucord's Kotlin 1.5 runtime.
    private class GifProvider(val name: String, val domain: String) {
        companion object {
            val TENOR = GifProvider("TENOR", "tenor.com")
            val KLIPY = GifProvider("KLIPY", "klipy.com")
            val GIPHY = GifProvider("GIPHY", "giphy.com")
            val all = listOf(TENOR, KLIPY, GIPHY)
        }
    }
}
