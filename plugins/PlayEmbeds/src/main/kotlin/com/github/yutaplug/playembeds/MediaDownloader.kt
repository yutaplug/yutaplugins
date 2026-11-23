package com.github.yutaplug.playembeds

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.aliucord.Utils
import com.discord.app.AppActivity
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves what a player is playing to the public Downloads folder. Sources are
 * the URIs the players use: Discord's media URLs, googlevideo URLs (fetched in
 * ranges, as YouTube throttles whole-file requests), cached files and the local
 * [StreamServer], which merges a YouTube stream into one MP4 as it's read.
 */
internal class MediaDownloader(private val context: Context) {
    private val workers = Executors.newFixedThreadPool(MAX_PARALLEL)
    private val active = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var closed = false

    /** [activity] is the screen showing the player; Aliucord's appActivity can be a closed screen. */
    fun download(uri: String, title: String?, audioOnly: Boolean, activity: AppActivity?) {
        if (closed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            start(uri, title, audioOnly)
            return
        }
        // Before Android 10 writing to Downloads needs the storage permission; ask like Discord's media viewer.
        try {
            (activity ?: Utils.appActivity).x.requestMediaDownload { start(uri, title, audioOnly) }
        } catch (_: Throwable) {
            start(uri, title, audioOnly)
        }
    }

    private fun start(uri: String, title: String?, audioOnly: Boolean) {
        if (closed) return
        if (!active.add(uri)) {
            Utils.showToast("Already downloading")
            return
        }
        Utils.showToast(if (audioOnly) "Downloading audio…" else "Downloading video…")
        try {
            workers.execute {
                val name = try {
                    save(uri, title, audioOnly)
                } catch (_: Throwable) {
                    null
                } finally {
                    active.remove(uri)
                }
                if (closed) return@execute
                Utils.mainThread.post {
                    if (name != null) {
                        Utils.showToast("Saved $name to Downloads")
                    } else {
                        Utils.showToast("Couldn't download this ${if (audioOnly) "audio" else "video"}", true)
                    }
                }
            }
        } catch (_: Throwable) {
            active.remove(uri)
        }
    }

    /** Returns the saved file's name, or null when the source isn't a single downloadable file. */
    private fun save(uri: String, title: String?, audioOnly: Boolean): String? {
        open(uri).use { raw ->
            val input = BufferedInputStream(raw, BUFFER_BYTES)
            input.mark(SNIFF_BYTES)
            val head = ByteArray(SNIFF_BYTES)
            var count = 0
            while (count < head.size) {
                val read = input.read(head, count, head.size - count)
                if (read < 0) break
                count += read
            }
            input.reset()
            if (count == 0) return null
            val extension = sniffExtension(head, count, audioOnly, pathExtension(uri))
                ?: pathExtension(uri)?.takeIf { it in MEDIA_EXTENSIONS }
                ?: if (audioOnly) "mp3" else "mp4"
            if (extension in PLAYLIST_EXTENSIONS) return null
            val base = fileBase(title, uri, audioOnly)
            val mime = mimeType(extension, audioOnly)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(input, "$base.$extension", mime)
            } else {
                saveToFolder(input, base, extension, mime)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(input: InputStream, name: String, mime: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val output = resolver.openOutputStream(item) ?: throw IOException("No output stream")
            output.use { copy(input, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        } catch (error: Throwable) {
            try {
                resolver.delete(item, null, null)
            } catch (_: Throwable) {
                // Left pending; Android removes pending items after a while.
            }
            throw error
        }
        // A name that's taken gets a number added; report the one actually used.
        return try {
            resolver.query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        } ?: name
    }

    @Suppress("DEPRECATION")
    private fun saveToFolder(input: InputStream, base: String, extension: String, mime: String): String {
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        folder.mkdirs()
        var file = File(folder, "$base.$extension")
        var number = 1
        while (file.exists()) file = File(folder, "$base (${number++}).$extension")
        try {
            FileOutputStream(file).use { copy(input, it) }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
        return file.name
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            if (closed) throw IOException("Stopped")
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun open(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        return when {
            parsed.scheme == "file" -> FileInputStream(File(parsed.path ?: throw IOException("No path")))
            isGoogleVideo(parsed.host) -> RangedStream(uri)
            else -> connect(uri) ?: throw IOException("Request failed")
        }
    }

    fun close() {
        closed = true
        workers.shutdownNow()
        active.clear()
    }

    /** Reads a googlevideo URL in [CHUNK_BYTES] pieces with its `range` parameter. */
    private class RangedStream(private val url: String) : InputStream() {
        private var offset = 0L
        private var chunkRead = 0L
        private var current: InputStream? = null
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (!finished) {
                val stream = current ?: openChunk() ?: return -1
                val read = stream.read(buffer, off, len)
                if (read > 0) {
                    chunkRead += read
                    return read
                }
                stream.close()
                current = null
                // A short chunk is the last one.
                if (chunkRead < CHUNK_BYTES) finished = true
                offset += chunkRead
                chunkRead = 0L
            }
            return -1
        }

        private fun openChunk(): InputStream? {
            var attempt = 0
            while (true) {
                val stream = connect("$url&range=$offset-${offset + CHUNK_BYTES - 1}")
                if (stream != null) {
                    current = stream
                    return stream
                }
                // Past the end of a file whose size is a multiple of the chunk size.
                if (offset > 0 || ++attempt >= CHUNK_ATTEMPTS) {
                    finished = true
                    if (offset > 0) return null
                    throw IOException("Request failed")
                }
            }
        }

        override fun close() {
            finished = true
            current?.close()
            current = null
        }
    }

    /** An open connection's body that disconnects when closed. */
    private class ConnectionStream(private val connection: HttpURLConnection) :
        FilterInputStream(connection.inputStream) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        const val MAX_PARALLEL = 2
        const val BUFFER_BYTES = 64 * 1024
        const val SNIFF_BYTES = 12
        const val CHUNK_BYTES = 8L * 1024 * 1024
        const val CHUNK_ATTEMPTS = 3
        const val TIMEOUT_MS = 30_000
        const val MAX_NAME_LENGTH = 80
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        val PLAYLIST_EXTENSIONS = setOf("m3u8", "mpd")
        val MEDIA_EXTENSIONS = setOf(
            "3gp", "aac", "avi", "flac", "flv", "m4a", "m4v", "mkv", "mov", "mp3",
            "mp4", "ogg", "ogv", "opus", "ts", "wav", "webm",
        )
        val INVALID_NAME_CHARS = "\\/:*?\"<>|"

        /** GET [url]; the body on a 2xx response, otherwise null. */
        fun connect(url: String): InputStream? {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            return try {
                val code = connection.responseCode
                if (code < 200 || code > 299) {
                    connection.disconnect()
                    null
                } else {
                    ConnectionStream(connection)
                }
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
        }

        fun isGoogleVideo(host: String?): Boolean {
            val name = host?.lowercase(Locale.ROOT) ?: return false
            return name == "googlevideo.com" || name.endsWith(".googlevideo.com")
        }

        /** Whether [uri] points at one file (not an HLS/DASH playlist). */
        fun canDownload(uri: String): Boolean = pathExtension(uri) !in PLAYLIST_EXTENSIONS

        fun pathExtension(uri: String): String? {
            val segment = Uri.parse(uri).lastPathSegment ?: return null
            val dot = segment.lastIndexOf('.')
            if (dot < 0 || dot == segment.length - 1) return null
            val extension = segment.substring(dot + 1).lowercase(Locale.ROOT)
            return extension.takeIf { it.length <= 5 && it.all(Char::isLetterOrDigit) }
        }

        /** The container from the file's first bytes, so cached and streamed sources get the right extension. */
        fun sniffExtension(head: ByteArray, count: Int, audioOnly: Boolean, fromPath: String?): String? {
            fun at(index: Int): Int = if (index < count) head[index].toInt() and 0xFF else -1
            fun text(from: Int, value: String): Boolean {
                var index = 0
                while (index < value.length) {
                    if (at(from + index) != value[index].code) return false
                    index++
                }
                return true
            }
            return when {
                text(4, "ftyp") -> when {
                    text(8, "qt  ") -> "mov"
                    audioOnly -> "m4a"
                    else -> "mp4"
                }
                at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 ->
                    if (fromPath == "mkv") "mkv" else "webm"
                text(0, "OggS") -> "ogg"
                text(0, "ID3") -> "mp3"
                at(0) == 0xFF && (at(1) and 0xF6) == 0xF0 -> "aac"
                at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
                text(0, "#EXTM3U") -> "m3u8"
                text(0, "<?xml") || text(0, "<MPD") -> "mpd"
                text(0, "FLV") -> "flv"
                text(0, "RIFF") && text(8, "AVI ") -> "avi"
                at(0) == 0x47 -> "ts"
                else -> null
            }
        }

        fun mimeType(extension: String, audioOnly: Boolean): String = when (extension) {
            "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "mov" -> "video/quicktime"
            "webm" -> if (audioOnly) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            "ogg" -> if (audioOnly) "audio/ogg" else "video/ogg"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "ts" -> "video/mp2t"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }

        /** The embed title, else the URL's file name, made safe for a file name. */
        fun fileBase(title: String?, uri: String, audioOnly: Boolean): String {
            // Cached files and local streams have generated names; don't use those.
            val parsed = Uri.parse(uri)
            val fromUri = parsed.lastPathSegment
                ?.takeUnless { parsed.scheme == "file" || parsed.host == StreamServer.LOOPBACK_HOST }
                ?.substringBeforeLast('.')
            for (candidate in listOf(title, fromUri)) {
                val clean = sanitize(candidate ?: continue)
                if (clean.isNotEmpty()) return clean
            }
            return if (audioOnly) "audio" else "video"
        }

        fun sanitize(name: String): String {
            val builder = StringBuilder()
            for (char in name) {
                builder.append(if (char.code < 32 || INVALID_NAME_CHARS.indexOf(char) >= 0) '_' else char)
            }
            var result = builder.toString().trim().trim('.').trim()
            if (result.length > MAX_NAME_LENGTH) result = result.substring(0, MAX_NAME_LENGTH).trim()
            return result
        }
    }
}
