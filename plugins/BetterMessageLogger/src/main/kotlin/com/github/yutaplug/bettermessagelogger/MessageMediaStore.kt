package com.github.yutaplug.bettermessagelogger

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import com.aliucord.Utils
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.models.message.Message
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps local copies of image and video attachments of deleted messages. Discord purges deleted
 * attachments from its CDN almost immediately and signed URLs expire, so media is downloaded into a
 * bounded pending cache when messages arrive and moved into permanent storage once they are deleted.
 */
internal class MessageMediaStore(
    private val root: File,
    private val reportError: (String, Throwable) -> Unit,
    private val changed: () -> Unit,
) {
    private val executor = executor("BetterMessageLogger-Media")
    private val prefetchExecutor = executor("BetterMessageLogger-Prefetch")
    private val pending = File(root, PENDING_DIR)

    // Media of messages deleted while the database is off; it lasts only for this session.
    private val session = File(root, SESSION_DIR)
    private val rootUri = Uri.fromFile(root).toString() + "/"
    private val moveLock = Any()

    // Deleted messages handled this session, mapped to whether their media is kept permanently.
    // Also protects fresh downloads from a concurrent sweep. Guarded by itself.
    private val attempted = HashMap<Long, Boolean>()

    // Guarded by attempted.
    private val prefetched = LinkedHashSet<Long>()
    private var stopped = false

    init {
        // Pending media of messages that were not deleted while the app ran is no longer useful.
        enqueue(prefetchExecutor, "clear pending media") { pending.deleteRecursively() }
        enqueue(executor, "clear session media") { synchronized(moveLock) { session.deleteRecursively() } }
        // Existing installs already have the folder; hide it from the gallery right away.
        // Decided before any download can create the marker, so a fast prefetch can't skip the migration.
        val migrate = root.isDirectory && !File(root, NO_MEDIA).exists()
        if (migrate) {
            enqueue(executor, "hide media from gallery") {
                hideFromGallery()
                removeFromGallery()
            }
        }
    }

    /** Downloads media of a live message into the pending cache, in case it gets deleted later. */
    fun prefetchAsync(message: Message) {
        val attachments = message.attachments
        if (attachments == null || attachments.isEmpty()) return
        val id = message.id
        synchronized(attempted) {
            if (attempted.containsKey(id) || !prefetched.add(id)) return
            if (prefetched.size > MAX_PREFETCHED_IDS) prefetched.remove(prefetched.first())
        }
        enqueue(prefetchExecutor, "pre-download media") {
            val directory = File(pending, id.toString())
            var index = 0
            while (index < attachments.size) {
                val attachment = attachments[index]
                val name = name(index, attachment)
                if (name != null && attachment.d() <= MAX_PREFETCH_BYTES) {
                    try {
                        save(attachment, File(directory, name), MAX_PREFETCH_BYTES)
                    } catch (_: Exception) {
                        // Best effort; the deletion handler tries again.
                    }
                }
                index++
            }
            // The message may have been deleted while its media was downloading.
            val persistent = synchronized(attempted) { attempted[id] }
            if (persistent != null) {
                promote(directory, target(id, persistent))
                changed()
            } else {
                directory.setLastModified(now())
            }
            trimPending()
        }
    }

    /**
     * Stores media of a deleted message: permanently when [persistent], otherwise for this session.
     * Each message is handled once per mode. Never blocks the calling thread.
     */
    fun saveAsync(record: MessageRecord, persistent: Boolean) {
        if (!record.deleted) return
        synchronized(attempted) {
            val previous = attempted[record.id]
            if (previous == true || (previous == false && !persistent)) return
            attempted[record.id] = persistent
        }
        val directory = target(record.id, persistent)
        enqueue(executor, "save logged media") {
            promote(File(pending, record.id.toString()), directory)
            if (persistent) promote(File(session, record.id.toString()), directory)
            val attachments = record.toMessage().attachments
            if (attachments == null || attachments.isEmpty()) return@enqueue
            changed()
            var index = 0
            while (index < attachments.size) {
                val attachment = attachments[index]
                val target = name(index, attachment)?.let { File(directory, it) }
                if (target != null) {
                    try {
                        save(attachment, target, MAX_BYTES)
                    } catch (error: Exception) {
                        // The CDN often purges deleted attachments; the message still shows its text.
                        reportError("save attachment ${attachment.a()}", error)
                    }
                }
                index++
            }
            changed()
        }
    }

    fun hasMedia(id: Long) = File(root, id.toString()).isDirectory || File(session, id.toString()).isDirectory

    /** Points attachments of a freshly parsed message at their local copies. Never pass a live message. */
    fun localize(message: Message): Message {
        val attachments = message.attachments ?: return message
        val urlField = urlField ?: return message
        val proxyUrlField = proxyUrlField ?: return message
        var index = 0
        while (index < attachments.size) {
            val attachment = attachments[index]
            val name = name(index, attachment)
            val local = name?.let { File(target(message.id, true), it).takeIf { file -> file.isFile } }
                ?: name?.let { File(target(message.id, false), it).takeIf { file -> file.isFile } }
            if (local != null) {
                val uri = Uri.fromFile(local).toString()
                urlField.set(attachment, uri)
                proxyUrlField.set(attachment, uri)
            }
            index++
        }
        return message
    }

    /** Discord appends media-proxy parameters to preview URLs; local files need the plain path. */
    fun previewUrls(url: String): List<String>? {
        if (!url.startsWith(rootUri)) return null
        val file = File(Uri.parse(url).path ?: return null)
        val thumbnail = thumbnail(file)
        return listOf(if (thumbnail.isFile) Uri.fromFile(thumbnail).toString() else url)
    }

    fun removeAsync(id: Long) = enqueue(executor, "remove logged media") {
        synchronized(attempted) { attempted.remove(id) }
        synchronized(moveLock) {
            File(root, id.toString()).deleteRecursively()
            File(session, id.toString()).deleteRecursively()
            File(pending, id.toString()).deleteRecursively()
        }
    }

    fun clearAsync() = enqueue(executor, "clear logged media") {
        synchronized(attempted) { attempted.clear() }
        synchronized(moveLock) { root.deleteRecursively() }
    }

    /** Deletes media of messages that are no longer in the database. */
    fun retainAsync(ids: Set<Long>) = enqueue(executor, "clean up logged media") {
        val protected = synchronized(attempted) { HashSet(attempted.keys) }
        synchronized(moveLock) {
            root.listFiles()?.forEach { directory ->
                if (directory.name == PENDING_DIR || directory.name == SESSION_DIR || directory.name == NO_MEDIA) {
                    return@forEach
                }
                val id = directory.name.toLongOrNull()
                if (id == null || (id !in ids && id !in protected)) directory.deleteRecursively()
            }
        }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        executor.shutdownNow()
        prefetchExecutor.shutdownNow()
    }

    private fun target(id: Long, persistent: Boolean) = File(if (persistent) root else session, id.toString())

    /** Moves completed downloads from [source] into [target]. */
    private fun promote(source: File, target: File) = synchronized(moveLock) {
        val files = source.listFiles() ?: return@synchronized
        if (!target.isDirectory && !target.mkdirs()) return@synchronized
        hideFromGallery()
        var downloading = false
        for (file in files) {
            if (file.name.endsWith(PART)) {
                downloading = true
                continue
            }
            val destination = File(target, file.name)
            if (destination.exists()) file.delete() else file.renameTo(destination)
        }
        // A running prefetch promotes its remaining file when it finishes.
        if (!downloading) source.deleteRecursively()
    }

    private fun trimPending() {
        val directories = pending.listFiles()?.filter { it.isDirectory } ?: return
        var total = 0L
        val sizes = HashMap<File, Long>()
        for (directory in directories) {
            val size = directory.listFiles()?.sumOf { it.length() } ?: 0L
            sizes[directory] = size
            total += size
        }
        var count = directories.size
        for (directory in directories.sortedBy { it.lastModified() }) {
            if (total <= MAX_PENDING_BYTES && count <= MAX_PREFETCHED_IDS) break
            synchronized(moveLock) { directory.deleteRecursively() }
            total -= sizes[directory] ?: 0L
            count--
        }
    }

    private fun name(index: Int, attachment: MessageAttachment): String? {
        val url = attachment.f() ?: return null
        val type = attachment.e()
        if (type != MessageAttachmentType.IMAGE && type != MessageAttachmentType.VIDEO) return null
        // Keep the original extension; Discord derives the attachment type from it.
        val extension = Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "").toLowerCase(Locale.ROOT)
        if (extension.isEmpty() || extension.length > 5 || !extension.all { it in 'a'..'z' || it in '0'..'9' }) {
            return null
        }
        return "$index.$extension"
    }

    /**
     * Keeps the media scanner from indexing logged attachments, which otherwise show up in the gallery
     * and Discord's attachment picker. Covers every subfolder of [root], and is recreated after a clear.
     */
    private fun hideFromGallery() {
        val marker = File(root, NO_MEDIA)
        if (marker.exists()) return
        try {
            marker.createNewFile()
        } catch (error: Exception) {
            reportError("create $NO_MEDIA", error)
        }
    }

    /**
     * One-time migration for media saved before [NO_MEDIA] existed: those files are already indexed.
     * Rescanning a file that now sits under a .nomedia folder makes the media scanner drop it.
     */
    private fun removeFromGallery() {
        if (!File(root, NO_MEDIA).exists()) return
        val paths = root.walkTopDown()
            .filter { it.isFile && it.name != NO_MEDIA && !it.name.endsWith(PART) }
            .map { it.absolutePath }
            .toList()
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(Utils.appContext, paths.toTypedArray(), null, null)
    }

    private fun thumbnail(file: File) = File(file.parentFile, file.nameWithoutExtension + ".thumb.jpg")

    private fun save(attachment: MessageAttachment, target: File, limit: Long) {
        download(attachment, target, limit)
        if (attachment.e() == MessageAttachmentType.VIDEO) createThumbnail(target)
    }

    private fun download(attachment: MessageAttachment, target: File, limit: Long) {
        if (target.isFile) return
        if (attachment.d() > limit) return
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create media folder" }
        hideFromGallery()
        val sources = listOfNotNull(attachment.f(), attachment.c()).distinct()
        var failure: Exception? = null
        for (source in sources) {
            try {
                fetch(source, target, limit)
                return
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun fetch(source: String, target: File, limit: Long) {
        val connection = URL(source).openConnection() as HttpURLConnection
        val partial = File(target.parentFile, target.name + PART)
        try {
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= limit) { "Attachment is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(partial.renameTo(target)) { "Could not save attachment" }
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }

    private fun createThumbnail(video: File) {
        val output = thumbnail(video)
        if (output.isFile || !video.isFile) return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(0) ?: return
            val partial = File(output.parentFile, output.name + PART)
            try {
                FileOutputStream(partial).use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                check(partial.renameTo(output)) { "Could not save video thumbnail" }
            } finally {
                partial.delete()
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    @Synchronized
    private fun enqueue(target: ExecutorService, action: String, work: () -> Unit) {
        if (stopped) return
        target.execute {
            try {
                work()
            } catch (error: Exception) {
                reportError(action, error)
            }
        }
    }

    private fun executor(name: String) = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val PENDING_DIR = ".pending"
        private const val SESSION_DIR = ".session"
        private const val PART = ".part"
        private const val NO_MEDIA = ".nomedia"
        private const val MAX_BYTES = 100L * 1024 * 1024
        private const val MAX_PREFETCH_BYTES = 25L * 1024 * 1024
        private const val MAX_PENDING_BYTES = 256L * 1024 * 1024
        private const val MAX_PREFETCHED_IDS = 1024
        private const val TIMEOUT = 30_000

        private val urlField = attachmentField("url")
        private val proxyUrlField = attachmentField("proxyUrl")

        private fun attachmentField(name: String) =
            runCatching { MessageAttachment::class.java.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
    }
}
