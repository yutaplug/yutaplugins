package com.github.yutaplug.bettermessagelogger

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedVideo
import com.discord.models.message.Message
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class MediaKind { IMAGE, VIDEO, AUDIO, FILE }

/** One downloadable file of a message: an uploaded attachment, or an image or video of a link embed. */
internal class MediaItem(
    val key: String,
    val kind: MediaKind,
    val urls: List<String>,
    val size: Long?,
    val extension: String?,
    val uploaded: Boolean,
) {
    val fileName get() = if (extension == null) key else "$key.$extension"
}

/** Which media gets saved, from the Media settings. */
internal class MediaOptions(settings: SettingsAPI) {
    private val uploaded = settings.getBool(Prefs.MEDIA_ATTACHMENTS, true)
    private val embedded = settings.getBool(Prefs.MEDIA_EMBEDS, true)
    private val images = settings.getBool(Prefs.MEDIA_IMAGES, true)
    private val videos = settings.getBool(Prefs.MEDIA_VIDEOS, true)
    private val audio = settings.getBool(Prefs.MEDIA_AUDIO, false)
    private val files = settings.getBool(Prefs.MEDIA_FILES, false)
    val maxBytes = settings.getInt(Prefs.MEDIA_MAX_MB, 25).coerceIn(1, 500) * 1024L * 1024L

    fun allows(item: MediaItem): Boolean {
        val source = if (item.uploaded) uploaded else embedded
        val kind = when (item.kind) {
            MediaKind.IMAGE -> images
            MediaKind.VIDEO -> videos
            MediaKind.AUDIO -> audio
            MediaKind.FILE -> files
        }
        return source && kind && (item.size == null || item.size <= maxBytes)
    }
}

/**
 * Local copies of media, one folder per message under [root]. Discord's CDN drops deleted attachments within
 * moments, so files can be fetched as messages arrive ("pre-download") and are simply kept if the message is
 * then deleted. Folders of messages that aren't part of the kept log are removed at the next start, and
 * pre-downloads are trimmed to a size budget while the app runs.
 */
internal class MediaStore(
    private val root: File,
    private val reportError: (String, Throwable) -> Unit,
    private val changed: () -> Unit,
) {
    private val saver = thread("BetterMessageLogger-Media")
    private val prefetcher = thread("BetterMessageLogger-Prefetch")
    private val rootUri = Uri.fromFile(root).toString() + "/"
    private val startedAt = System.currentTimeMillis()

    // Guarded by `this`: messages whose media belongs to the log this session, and files being or already fetched.
    private val logged = HashSet<Long>()
    private val claimed = HashSet<String>()
    private var stopped = false

    /** Fetches media of a new message in case it gets deleted later. */
    fun prefetchAsync(message: Message, options: MediaOptions) {
        val items = items(message).filter(options::allows)
        if (items.isNotEmpty()) fetchAsync(prefetcher, message.id, items, options.maxBytes, keep = false)
    }

    /** Keeps media of a deleted message, or attachments an edit removed, fetching what isn't here yet. */
    fun saveAsync(id: Long, items: List<MediaItem>, maxBytes: Long) {
        synchronized(this) { logged.add(id) }
        fetchAsync(saver, id, items, maxBytes, keep = true)
    }

    private fun fetchAsync(executor: ExecutorService, id: Long, items: List<MediaItem>, maxBytes: Long, keep: Boolean) {
        val todo = synchronized(this) { items.filter { claimed.add("$id/${it.key}") } }
        if (todo.isEmpty()) {
            if (keep) changed()
            return
        }
        run(executor, "save media of message $id") {
            var saved = false
            for (item in todo) {
                val target = File(folder(id), item.fileName)
                try {
                    fetch(item, target, maxBytes)
                    if (item.kind == MediaKind.VIDEO) writeThumbnail(target)
                    saved = true
                } catch (error: Exception) {
                    // Let a later delete or reload try again. Deleted attachments are often gone from the CDN.
                    synchronized(this) { claimed.remove("$id/${item.key}") }
                    if (keep) reportError("save media of message $id", error)
                }
            }
            if (saved && synchronized(this) { id in logged }) changed()
            if (!keep) trimPrefetched()
        }
    }

    fun hasMedia(id: Long) = folder(id).isDirectory

    fun localFile(id: Long, item: MediaItem): File? = File(folder(id), item.fileName).takeIf { it.isFile }

    /** The saved file a local URL points at; null for every other URL. */
    fun fileOf(url: String?): File? {
        if (url == null || !url.startsWith(rootUri)) return null
        return Uri.parse(url).path?.let(::File)?.takeIf { it.isFile }
    }

    /** Points a message's media at saved copies. Only for private copies, never a message Discord holds. */
    fun localize(message: Message): Message {
        message.attachments?.forEachIndexed { position, attachment ->
            val item = attachmentItem(attachment) ?: return@forEachIndexed
            // Versions before 1.1.0 named saved attachments by their position, like "0.png".
            val local = localFile(message.id, item)
                ?: File(folder(message.id), "$position.${item.extension}").takeIf { item.extension != null && it.isFile }
                ?: return@forEachIndexed
            URL_FIELDS[MessageAttachment::class.java]?.forEach { write(it, attachment, Uri.fromFile(local).toString()) }
        }
        message.embeds?.forEach { embed ->
            for (media in listOfNotNull(embed.f(), embed.h(), embed.m())) {
                val item = embedItem(media) ?: continue
                val local = localFile(message.id, item) ?: continue
                URL_FIELDS[media.javaClass]?.forEach { write(it, media, Uri.fromFile(local).toString()) }
            }
        }
        return message
    }

    /** Discord asks for resized previews through its media proxy; saved files are previewed as they are. */
    fun previewUrls(url: String): List<String>? {
        val file = fileOf(url) ?: return null
        val frame = thumbnail(file)
        return listOf(Uri.fromFile(if (frame.isFile) frame else file).toString())
    }

    fun removeAsync(id: Long) = run(saver, "remove media of message $id") {
        synchronized(this) {
            logged.remove(id)
            claimed.removeAll { it.startsWith("$id/") }
        }
        folder(id).deleteRecursively()
    }

    fun clearAsync() = run(saver, "clear saved media") {
        synchronized(this) {
            logged.clear()
            claimed.clear()
        }
        root.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
    }

    /**
     * Deletes folders of messages that aren't in [keep] or logged this session. Folders touched since the app
     * started are left alone, so pre-downloads of this session survive until trimmed.
     */
    fun cleanUpAsync(keep: Set<Long>) = run(saver, "clean up saved media") {
        val active = synchronized(this) { HashSet(logged) }
        root.listFiles()?.forEach { folder ->
            val id = folder.name.toLongOrNull()
            if (id == null) {
                // Folders of versions before 1.1.0 (.pending, .session) are no longer used.
                if (folder.isDirectory && folder.name.startsWith(".")) folder.deleteRecursively()
                return@forEach
            }
            if (id !in keep && id !in active && folder.lastModified() < startedAt) folder.deleteRecursively()
        }
        hideFromGallery()
    }

    fun stop() {
        synchronized(this) { stopped = true }
        saver.shutdownNow()
        prefetcher.shutdownNow()
    }

    private fun thread(name: String): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    private fun folder(id: Long) = File(root, id.toString())

    /** Drops the oldest pre-downloads that weren't needed once they pass the size budget. */
    private fun trimPrefetched() {
        val active = synchronized(this) { HashSet(logged) }
        val folders = root.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull()?.let { id -> id !in active } == true }
            ?: return
        val sizes = folders.map { folder -> folder to (folder.listFiles()?.sumOf { it.length() } ?: 0L) }
        var total = sizes.sumOf { it.second }
        var count = sizes.size
        for ((folder, size) in sizes.sortedBy { it.first.lastModified() }) {
            if (total <= PREFETCH_BUDGET && count <= PREFETCH_FOLDERS) break
            val id = folder.name.toLong()
            synchronized(this) {
                if (id in logged) return@synchronized
                claimed.removeAll { it.startsWith("$id/") }
                folder.deleteRecursively()
            }
            total -= size
            count--
        }
    }

    private fun fetch(item: MediaItem, target: File, maxBytes: Long) {
        if (target.isFile) return
        check(item.size == null || item.size <= maxBytes) { "Larger than the size limit" }
        val folder = requireNotNull(target.parentFile)
        check(folder.isDirectory || folder.mkdirs()) { "Could not create ${folder.name}" }
        hideFromGallery()
        var lastError: Exception? = null
        for (url in item.urls) {
            try {
                download(url, target, maxBytes)
                return
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No source")
    }

    private fun download(url: String, target: File, maxBytes: Long) {
        val partial = File(target.parentFile, ".${target.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            check(connection.contentLength.toLong() <= maxBytes) { "Larger than the size limit" }
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        written += read
                        check(written <= maxBytes) { "Larger than the size limit" }
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
            check(partial.renameTo(target)) { "Could not finish ${target.name}" }
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun thumbnail(file: File) = File(file.parentFile, ".${file.nameWithoutExtension}.frame.jpg")

    /** Saves a video's first frame, which Discord shows as its preview. */
    private fun writeThumbnail(video: File) {
        val output = thumbnail(video)
        if (output.isFile) return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.frameAtTime ?: return
            FileOutputStream(output).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame.recycle()
        } catch (_: Exception) {
            output.delete()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** A .nomedia file keeps the gallery and Discord's file picker from listing saved media. */
    private fun hideFromGallery() {
        val marker = File(root, ".nomedia")
        if (marker.exists() || !(root.isDirectory || root.mkdirs())) return
        try {
            marker.createNewFile()
            // Files indexed before the marker existed only leave the gallery once rescanned.
            val files = root.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.map { it.path }.toList()
            if (files.isNotEmpty()) MediaScannerConnection.scanFile(Utils.appContext, files.toTypedArray(), null, null)
        } catch (error: Exception) {
            reportError("hide saved media from the gallery", error)
        }
    }

    private fun run(executor: ExecutorService, action: String, work: () -> Unit) = synchronized(this) {
        if (stopped) return@synchronized
        executor.execute {
            try {
                work()
            } catch (_: InterruptedException) {
            } catch (error: Exception) {
                reportError(action, error)
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val PREFETCH_BUDGET = 256L * 1024 * 1024
        private const val PREFETCH_FOLDERS = 1000
        private val AUDIO = setOf("mp3", "ogg", "oga", "opus", "wav", "m4a", "flac", "aac", "weba", "wma", "amr", "mka")

        private fun field(owner: Class<*>, name: String): Field? = try {
            owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: Exception) {
            null
        }

        private val ATTACHMENT_ID = field(MessageAttachment::class.java, "id")
        private val URL_FIELDS: Map<Class<*>, List<Field>> = listOf(
            MessageAttachment::class.java,
            EmbedImage::class.java,
            EmbedThumbnail::class.java,
            EmbedVideo::class.java,
        ).associateWith { owner -> listOfNotNull(field(owner, "url"), field(owner, "proxyUrl")) }

        private fun write(field: Field, owner: Any, value: String) {
            try {
                field.set(owner, value)
            } catch (_: Exception) {
            }
        }

        /** Every media file of a message, whatever the settings allow. */
        fun items(message: Message): List<MediaItem> {
            val result = LinkedHashMap<String, MediaItem>()
            message.attachments?.forEach { attachment -> attachmentItem(attachment)?.let { result[it.key] = it } }
            message.embeds?.forEach { embed ->
                for (media in listOfNotNull(embed.f(), embed.h(), embed.m())) embedItem(media)?.let { result[it.key] = it }
            }
            return result.values.toList()
        }

        fun attachmentItem(attachment: MessageAttachment): MediaItem? {
            val url = attachment.f() ?: return null
            if (!url.startsWith("http")) return null
            val id = try {
                ATTACHMENT_ID?.getLong(attachment)
            } catch (_: Exception) {
                null
            }
            val extension = extension(attachment.a()) ?: extension(Uri.parse(url).lastPathSegment)
            val kind = when {
                attachment.e() == MessageAttachmentType.IMAGE -> MediaKind.IMAGE
                attachment.e() == MessageAttachmentType.VIDEO -> MediaKind.VIDEO
                extension in AUDIO -> MediaKind.AUDIO
                else -> MediaKind.FILE
            }
            val key = "a" + (id?.takeIf { it != 0L }?.toString() ?: Integer.toHexString(url.hashCode()))
            return MediaItem(key, kind, listOfNotNull(url, attachment.c()).distinct(), attachment.d().takeIf { it > 0 }, extension, true)
        }

        /** Only media Discord's own proxy serves, which is all the app loads itself; never a third-party host. */
        private fun embedItem(media: Any): MediaItem? {
            val proxy = when (media) {
                is EmbedImage -> media.b()
                is EmbedThumbnail -> media.b()
                is EmbedVideo -> media.b()
                else -> null
            }
            if (proxy == null || !proxy.startsWith("http")) return null
            val kind = if (media is EmbedVideo) MediaKind.VIDEO else MediaKind.IMAGE
            val key = "e" + Integer.toHexString(proxy.hashCode())
            return MediaItem(key, kind, listOf(proxy), null, extension(Uri.parse(proxy).lastPathSegment), false)
        }

        private fun extension(name: String?): String? {
            val dot = name?.lastIndexOf('.') ?: -1
            if (name == null || dot < 0) return null
            val value = name.substring(dot + 1).toLowerCase(Locale.ROOT)
            if (value.isEmpty() || value.length > 8) return null
            for (char in value) if (char !in 'a'..'z' && char !in '0'..'9') return null
            return value
        }
    }
}
