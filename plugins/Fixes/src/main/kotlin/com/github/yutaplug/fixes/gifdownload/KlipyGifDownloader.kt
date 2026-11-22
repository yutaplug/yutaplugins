package com.github.yutaplug.fixes.gifdownload

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Uses the embed's actual video when Klipy does not supply a GIF rendition. */
internal object KlipyGifDownloader {
    interface Progress {
        fun update(stage: String, current: Int = 0, total: Int = 0)
    }

    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    private const val MAX_FRAME_EDGE = 480
    private const val MAX_FRAMES = 600
    private const val MAX_DURATION_MS = 120_000L
    private const val FRAMES_PER_SECOND = 12

    fun download(context: Context, mediaUri: Uri, gifUri: Uri?, fileName: String, directUri: Uri, progress: Progress): String {
        val source = File.createTempFile("klipy-source-", ".bin", context.cacheDir)
        val encoded = File.createTempFile("klipy-output-", ".gif", context.cacheDir)
        try {
            val candidates = listOfNotNull(gifUri, mediaUri, directUri).distinct()
            var lastError: IOException? = null
            for ((index, candidate) in candidates.withIndex()) {
                try {
                    progress.update(if (index == 0) "Fetching media" else "Retrying source media")
                    fetch(candidate, source)
                    prepareGif(source, encoded, progress)
                    lastError = null
                    break
                } catch (error: IOException) {
                    if (Thread.currentThread().isInterrupted) throw error
                    lastError?.let { error.addSuppressed(it) }
                    lastError = error
                }
            }
            lastError?.let { throw it }
            checkInterrupted()
            progress.update("Saving to Downloads")
            return publish(context, encoded, fileName)
        } finally {
            source.delete()
            encoded.delete()
        }
    }

    private fun fetch(uri: Uri, destination: File) {
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Aliucord GifDownloadFix")
            connection.setRequestProperty("Accept", "image/gif,video/mp4,video/webm;q=0.9,*/*;q=0.5")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Klipy media returned HTTP $status")
            if (connection.contentLength.toLong() > MAX_SOURCE_BYTES) throw IOException("Klipy media is too large")
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkInterrupted()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SOURCE_BYTES) throw IOException("Klipy media is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun prepareGif(source: File, destination: File, progress: Progress) {
        val header = ByteArray(12)
        val length = source.inputStream().use { it.read(header) }
        if (length >= 6 && String(header, 0, 6, Charsets.US_ASCII) in listOf("GIF87a", "GIF89a")) {
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            return
        }
        val isMp4 = length >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
        val isWebm = length >= 4 && header.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)
        if (!isMp4 && !isWebm) throw IOException("Klipy returned neither a GIF nor a video")
        convertVideo(source, destination, progress)
    }

    private fun convertVideo(source: File, destination: File, progress: Progress) {
        progress.update("Reading video")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("Klipy video duration could not be read")
            if (durationMs !in 1..MAX_DURATION_MS) throw IOException("Klipy video must be at most two minutes long")
            val first = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw IOException("Klipy video frames could not be decoded")
            val scale = minOf(1.0, MAX_FRAME_EDGE.toDouble() / max(first.width, first.height))
            val width = max(1, (first.width * scale).roundToInt())
            val height = max(1, (first.height * scale).roundToInt())
            val frameCount = ceil(durationMs * FRAMES_PER_SECOND / 1000.0).toInt().coerceIn(2, MAX_FRAMES)
            val totalCentiseconds = max(frameCount, (durationMs / 10.0).roundToInt())
            val pixels = IntArray(width * height)
            try {
                destination.outputStream().buffered().use { output ->
                    val encoder = GifEncoder(output, width, height)
                    for (index in 0 until frameCount) {
                        checkInterrupted()
                        if (index % FRAMES_PER_SECOND == 0) progress.update("Converting to GIF", index, frameCount)
                        val timeUs = index.toLong() * durationMs * 1000 / frameCount
                        val decoded = if (index == 0) {
                            first
                        } else if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        }
                        val bitmap = decoded ?: throw IOException("Could not decode Klipy video frame $index")
                        var scaled: Bitmap? = null
                        try {
                            scaled = if (bitmap.width == width && bitmap.height == height) {
                                bitmap
                            } else {
                                Bitmap.createScaledBitmap(bitmap, width, height, true)
                            }
                            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                            val start = index.toLong() * totalCentiseconds / frameCount
                            val end = (index + 1L) * totalCentiseconds / frameCount
                            encoder.addFrame(pixels, (end - start).toInt())
                        } finally {
                            if (scaled != null && scaled !== bitmap) scaled.recycle()
                            if (bitmap !== first) bitmap.recycle()
                        }
                    }
                    encoder.finish()
                    progress.update("Converting to GIF", frameCount, frameCount)
                }
            } finally {
                first.recycle()
            }
        } catch (error: RuntimeException) {
            throw IOException("Could not convert Klipy video to an animated GIF", error)
        } finally {
            retriever.release()
        }
    }

    private fun publish(context: Context, gif: File, fileName: String): String {
        val safeName = fileName
            .dropLast(4)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(160)
            .ifEmpty { "Klipy" } +
            ".gif"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the GIF in Downloads")
            try {
                val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open the GIF in Downloads")
                output.use { stream -> gif.inputStream().use { it.copyTo(stream) } }
                val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, complete, null, null) < 1) throw IOException("Could not publish the GIF")
                return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else safeName
                } ?: safeName
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
        @Suppress("DEPRECATION")
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create Downloads")
        var destination = File(directory, safeName)
        var suffix = 1
        while (!destination.createNewFile()) {
            destination = File(directory, "${safeName.dropLast(4)} (${suffix++}).gif")
        }
        try {
            destination.outputStream().use { output -> gif.inputStream().use { it.copyTo(output) } }
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("image/gif"), null)
            return destination.name
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Klipy download cancelled")
    }
}
