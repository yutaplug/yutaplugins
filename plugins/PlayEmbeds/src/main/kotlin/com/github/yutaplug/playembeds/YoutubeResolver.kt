package com.github.yutaplug.playembeds

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class LoadStage { RESOLVING, DOWNLOADING, MUXING }

/** Written by the resolver's worker threads, read on the main thread for the loading popup. */
internal class LoadProgress {
    @Volatile var stage = LoadStage.RESOLVING
    @Volatile var totalBytes = 0L
    @Volatile var stageStartMs = SystemClock.elapsedRealtime()
    val downloadedBytes = AtomicLong()
}

/**
 * Resolves YouTube videos through the InnerTube player API.
 *
 * Clients that still answer without a PO token (IOS_PRODUCER) return only
 * separate video and audio streams, and Discord's ExoPlayer has no
 * MergingMediaSource (R8 stripped it). So both streams are downloaded in byte
 * ranges (open-ended requests are throttled to playback speed) and remuxed
 * into one local MP4 with Android's MediaMuxer. A muxed progressive format
 * (itag 18) is used directly when a client still offers one.
 *
 * Client values follow yt-dlp's `INNERTUBE_CLIENTS` (yt_dlp/extractor/youtube/_base.py).
 * When YouTube starts rejecting a client, update or reorder [CLIENTS].
 */
internal class YoutubeResolver(private val cacheDir: File) : CachedResolver<ResolvedMedia>() {
    private class Client(
        val name: String,
        val id: Int,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private class Stream(val url: String, val length: Long, val width: Int, val height: Int)

    private val loads = ConcurrentHashMap<String, LoadProgress>()

    /** Throughput of the last finished load, so the next estimate can start right away. */
    @Volatile private var lastDownloadBytesPerSec = 0L
    @Volatile private var lastMuxBytesPerSec = 0L

    init {
        clearCache()
    }

    override fun key(url: String): String? {
        val uri = Uri.parse(url)
        if (!EmbedUrls.isHost(uri.host, "youtube.com") && !EmbedUrls.isHost(uri.host, "youtu.be")) return null
        EmbedUrls.youtubeVideoId(uri)?.let { return "v:$it" }
        val list = uri.getQueryParameter("list")?.takeIf { it.isNotEmpty() } ?: return null
        return "list:$list"
    }

    override fun isStillValid(value: ResolvedMedia): Boolean {
        val uri = Uri.parse(value.uri)
        return uri.scheme != "file" || File(uri.path.orEmpty()).exists()
    }

    override fun onClose() = clearCache()

    /** The running load for [url], or null when it's queued, finished or not started. */
    fun progress(url: String): LoadProgress? = key(url)?.let { loads[it] }

    /** Milliseconds left for [tracker], or null when there's nothing to base an estimate on yet. */
    fun estimateMs(tracker: LoadProgress): Long? {
        val now = SystemClock.elapsedRealtime()
        val total = tracker.totalBytes
        val muxRate = lastMuxBytesPerSec
        val muxMs = if (muxRate > 0 && total > 0) total * 1_000L / muxRate else null
        return when (tracker.stage) {
            LoadStage.RESOLVING -> null
            LoadStage.DOWNLOADING -> {
                val done = tracker.downloadedBytes.get()
                val elapsed = now - tracker.stageStartMs
                val rate = if (elapsed >= MIN_RATE_SAMPLE_MS && done > 0) {
                    done * 1_000L / elapsed
                } else {
                    lastDownloadBytesPerSec
                }
                if (rate <= 0) return null
                (total - done).coerceAtLeast(0L) * 1_000L / rate + (muxMs ?: 0L)
            }
            LoadStage.MUXING -> muxMs?.let { (it - (now - tracker.stageStartMs)).coerceAtLeast(0L) }
        }
    }

    override fun load(key: String): Pair<ResolvedMedia, Long>? {
        val tracker = LoadProgress()
        loads[key] = tracker
        try {
            for (client in CLIENTS) {
                tracker.stage = LoadStage.RESOLVING
                val data = try {
                    player(client, key)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val result = try {
                    streamFrom(data, tracker)
                } catch (_: Throwable) {
                    null
                }
                if (result != null) return result
            }
            return null
        } finally {
            loads.remove(key)
        }
    }

    private fun player(client: Client, key: String): JSONObject? {
        val clientJson = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("userAgent", client.userAgent)
            .put("hl", "en")
            .put("gl", "US")
        client.extra.forEach { (name, value) -> clientJson.put(name, value) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientJson))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (key.startsWith("v:")) {
            body.put("videoId", key.removePrefix("v:"))
        } else {
            body.put("playlistId", key.removePrefix("list:"))
        }

        val data = Http.Request(PLAYER_URL, "POST").use { request ->
            request.setRequestTimeout(TIMEOUT_MS)
            request.setHeader("Content-Type", "application/json")
            request.setHeader("User-Agent", client.userAgent)
            request.setHeader("X-YouTube-Client-Name", client.id.toString())
            request.setHeader("X-YouTube-Client-Version", client.version)
            request.setHeader("Origin", "https://www.youtube.com")
            request.executeWithBody(body.toString()).use { response ->
                if (!response.ok()) return null
                JSONObject(response.text())
            }
        }
        if (data.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        if (data.optJSONObject("videoDetails")?.optBoolean("isLive") == true) return null
        return data
    }

    private fun streamFrom(data: JSONObject, tracker: LoadProgress): Pair<ResolvedMedia, Long>? {
        val streaming = data.optJSONObject("streamingData") ?: return null
        val expiresSeconds = streaming.optLong("expiresInSeconds", 0L).takeIf { it > 0 } ?: 3_600L

        muxedFormat(streaming.optJSONArray("formats"))?.let { format ->
            val ttlMs = (expiresSeconds - 600L).coerceAtLeast(60L) * 1_000L
            val media = ResolvedMedia(
                format.optString("url"),
                format.optInt("width", 16),
                format.optInt("height", 9),
                audioOnly = false,
            )
            return media to ttlMs
        }

        val adaptive = streaming.optJSONArray("adaptiveFormats") ?: return null
        val audio = audioStream(adaptive) ?: return null
        val video = videoStream(adaptive, MAX_TOTAL_BYTES - audio.length) ?: return null
        val videoId = data.optJSONObject("videoDetails")?.optString("videoId")?.takeIf { it.isNotEmpty() }
            ?: return null
        val file = downloadAndMux(videoId, video, audio, tracker) ?: return null
        return ResolvedMedia(Uri.fromFile(file).toString(), video.width, video.height, audioOnly = false) to
            3_600_000L
    }

    private fun muxedFormat(formats: JSONArray?): JSONObject? {
        formats ?: return null
        var chosen: JSONObject? = null
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val url = format.optString("url")
            if (url.isEmpty() || !isGoogleVideo(url)) continue
            if (format.optInt("itag") == MUXED_ITAG) return format
            if (chosen == null && format.optString("mimeType").startsWith("video/mp4")) chosen = format
        }
        return chosen
    }

    /** The tallest H.264 MP4 stream up to 360p that fits in [maxBytes], else the smallest that fits. */
    private fun videoStream(formats: JSONArray, maxBytes: Long): Stream? {
        var best: Stream? = null
        var smallest: Stream? = null
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue
            val stream = stream(format) ?: continue
            if (stream.length > maxBytes) continue
            if (stream.height <= MAX_VIDEO_HEIGHT && (best == null || stream.height > best.height)) best = stream
            if (smallest == null || stream.height < smallest.height) smallest = stream
        }
        return best ?: smallest
    }

    private fun audioStream(formats: JSONArray): Stream? {
        var fallback: Stream? = null
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            if (!format.optString("mimeType").startsWith("audio/mp4")) continue
            val stream = stream(format) ?: continue
            if (format.optInt("itag") == AAC_ITAG) return stream
            if (fallback == null) fallback = stream
        }
        return fallback
    }

    private fun stream(format: JSONObject): Stream? {
        val url = format.optString("url")
        if (url.isEmpty() || !isGoogleVideo(url)) return null
        val length = format.optString("contentLength").toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Stream(url, length, format.optInt("width", 16), format.optInt("height", 9))
    }

    private fun downloadAndMux(videoId: String, video: Stream, audio: Stream, tracker: LoadProgress): File? {
        cacheDir.mkdirs()
        val safeId = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val target = File(cacheDir, "yt_$safeId$VIDEO_SUFFIX")
        if (target.length() > 0) return target
        val videoFile = File(cacheDir, "yt_$safeId.video.part")
        val audioFile = File(cacheDir, "yt_$safeId.audio.part")
        val muxFile = File(cacheDir, "yt_$safeId.mux.part")
        val totalBytes = video.length + audio.length
        try {
            tracker.totalBytes = totalBytes
            tracker.downloadedBytes.set(0L)
            val downloadStart = SystemClock.elapsedRealtime()
            tracker.stageStartMs = downloadStart
            tracker.stage = LoadStage.DOWNLOADING
            // Download audio on a second thread while this one fetches video.
            var audioError: Throwable? = null
            val audioThread = Thread {
                try {
                    download(audio, audioFile, tracker.downloadedBytes)
                } catch (error: Throwable) {
                    audioError = error
                }
            }
            audioThread.start()
            download(video, videoFile, tracker.downloadedBytes)
            audioThread.join()
            audioError?.let { throw it }
            val muxStart = SystemClock.elapsedRealtime()
            lastDownloadBytesPerSec = totalBytes * 1_000L / (muxStart - downloadStart).coerceAtLeast(1L)
            tracker.stageStartMs = muxStart
            tracker.stage = LoadStage.MUXING
            mux(videoFile, audioFile, muxFile)
            lastMuxBytesPerSec = totalBytes * 1_000L / (SystemClock.elapsedRealtime() - muxStart).coerceAtLeast(1L)
            if (!muxFile.renameTo(target)) return null
            trimCache()
            return target
        } catch (_: Throwable) {
            return null
        } finally {
            videoFile.delete()
            audioFile.delete()
            muxFile.delete()
        }
    }

    /** Fetch [stream] in ranges; an open-ended request is throttled to playback speed. */
    private fun download(stream: Stream, file: File, downloaded: AtomicLong) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        FileOutputStream(file).use { out ->
            var start = 0L
            while (start < stream.length) {
                val end = minOf(start + CHUNK_BYTES, stream.length) - 1
                Http.Request("${stream.url}&range=$start-$end").use { request ->
                    request.setRequestTimeout(TIMEOUT_MS)
                    request.execute().use { response ->
                        if (!response.ok()) throw IOException("HTTP ${response.statusCode}")
                        response.stream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                downloaded.addAndGet(read.toLong())
                            }
                        }
                    }
                }
                start = end + 1
            }
        }
    }

    private fun mux(videoFile: File, audioFile: File, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = selectTrack(videoExtractor, "video/")
            val audioTrack = selectTrack(audioExtractor, "audio/")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            val created = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = created
            val muxVideo = created.addTrack(videoFormat)
            val muxAudio = created.addTrack(audioFormat)
            created.start()

            val buffer = ByteBuffer.allocateDirect(maxOf(bufferSize(videoFormat), bufferSize(audioFormat)))
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            // Interleave by timestamp so the output plays smoothly from the start.
            while (!videoDone || !audioDone) {
                val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                val audioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime
                val useVideo = !videoDone && (audioDone || videoTime <= audioTime)
                val extractor = if (useVideo) videoExtractor else audioExtractor
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                created.writeSampleData(if (useVideo) muxVideo else muxAudio, buffer, info)
                extractor.advance()
            }
            created.stop()
        } finally {
            try {
                muxer?.release()
            } catch (_: Throwable) {
                // Already failed; the partial output is deleted by the caller.
            }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) {
                extractor.selectTrack(index)
                return index
            }
        }
        throw IOException("No $mimePrefix track")
    }

    private fun bufferSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), MIN_BUFFER_BYTES)
        } else {
            MIN_BUFFER_BYTES
        }

    private fun isGoogleVideo(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && EmbedUrls.isHost(uri.host, "googlevideo.com")
    }

    private fun trimCache() {
        cacheDir.listFiles()
            ?.filter { it.name.endsWith(VIDEO_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_FILES)
            ?.forEach { it.delete() }
    }

    private fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val MUXED_ITAG = 18
        const val AAC_ITAG = 140
        const val MAX_VIDEO_HEIGHT = 360
        const val MAX_TOTAL_BYTES = 150L * 1024L * 1024L
        const val CHUNK_BYTES = 4L * 1024L * 1024L
        const val MIN_BUFFER_BYTES = 1024 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MIN_RATE_SAMPLE_MS = 1_000L
        const val MAX_CACHED_FILES = 3
        const val VIDEO_SUFFIX = ".mp4"

        val CLIENTS = listOf(
            // The only client that answered without a bot check in testing (Nov 2026).
            Client(
                name = "IOS_PRODUCER",
                id = 92,
                version = "0.147.945",
                userAgent = "com.google.ios.youtubeproducer/0.147.945 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                extra = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82",
                ),
            ),
            Client(
                name = "VISIONOS",
                id = 101,
                version = "1.02",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                extra = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "RealityDevice17,1",
                    "osName" to "visionOS",
                    "osVersion" to "26.5.23O471",
                ),
            ),
            Client(
                name = "ANDROID_VR",
                id = 28,
                version = "1.65.10",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
                    "eureka-user Build/SQ3A.220605.009.A1) gzip",
                extra = mapOf(
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "androidSdkVersion" to 32,
                    "osName" to "Android",
                    "osVersion" to "12L",
                ),
            ),
        )
    }
}
