package com.github.yutaplug.playembeds

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

internal enum class LoadStage { RESOLVING, DOWNLOADING, MUXING }

/** Written by the resolver's worker threads, read on the main thread for the loading popup. */
internal class LoadProgress {
    @Volatile var stage = LoadStage.RESOLVING
    @Volatile var totalBytes = 0L
    @Volatile var stageStartMs = SystemClock.elapsedRealtime()
    val downloadedBytes = AtomicLong()
}

/** Downloaded YouTube videos, kept until the app restarts so replays start at once. */
internal object YoutubeCache {
    /** `yt_<id>.<quality>p.mp4`; the quality is the video's short side (YouTube's "720p"). */
    val VIDEO_FILE: Pattern = Pattern.compile("^yt_([A-Za-z0-9_-]+)\\.(\\d+)p\\.mp4$")

    /** `yt_<id>.qualities`: a JSON array of the qualities the video offers. */
    val QUALITIES_FILE: Pattern = Pattern.compile("^yt_([A-Za-z0-9_-]+)\\.qualities$")
}

/**
 * Resolves YouTube videos through the InnerTube player API.
 *
 * Clients that still answer without a PO token (IOS_PRODUCER) return only
 * separate video and audio streams, and Discord's ExoPlayer has no
 * MergingMediaSource (R8 stripped it). So both streams are downloaded in byte
 * ranges (open-ended requests are throttled to playback speed), several at a
 * time, and remuxed into one local MP4 with Android's MediaMuxer. A muxed
 * progressive format (itag 18) is streamed directly when a client still offers
 * one and the quality allows it.
 *
 * Player responses are cached (so switching quality skips the lookup), and
 * finished MP4s are kept on disk until the app restarts.
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

    /**
     * [quality] is the short side, which is what YouTube calls the quality (a 1080x1920 short is 1080p).
     * [initEnd] and [indexEnd] locate the DASH init segment and sidx (-1 when not given).
     */
    private class Stream(
        val url: String,
        val length: Long,
        val width: Int,
        val height: Int,
        val initEnd: Long,
        val indexStart: Long,
        val indexEnd: Long,
    ) {
        val quality: Int get() = minOf(width, height)
        val isDash: Boolean get() = initEnd > 0 && indexStart > initEnd && indexEnd > indexStart &&
            indexEnd < MAX_INDEX_BYTES
    }

    private class Chunk(val url: String, val file: File, val start: Long, val end: Long)

    /** A usable player API answer from `CLIENTS[clientIndex]`, valid until [expires] (elapsedRealtime). */
    private class PlayerResponse(val data: JSONObject, val clientIndex: Int, val expires: Long)

    private val loads = ConcurrentHashMap<String, LoadProgress>()
    private val responses = ConcurrentHashMap<String, FutureTask<PlayerResponse?>>()

    /** Caption tracks by cache key, with when (elapsedRealtime) their signed URLs should be looked up again. */
    private val captionCache = ConcurrentHashMap<String, Pair<Long, List<CaptionTrack>>>()

    /** The last refusal reason from YouTube's player API, by cache key (without quality). */
    private val refusals = ConcurrentHashMap<String, String>()
    private var streamServer: StreamServer? = null

    /** Throughput of the last finished load, so the next estimate can start right away. */
    @Volatile private var lastDownloadBytesPerSec = 0L
    @Volatile private var lastMuxBytesPerSec = 0L

    init {
        // Videos are only kept until the app restarts.
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
        return when {
            uri.scheme == "file" -> File(uri.path.orEmpty()).exists()
            uri.host == StreamServer.LOOPBACK_HOST -> streamServer?.serves(value.uri) == true
            else -> true
        }
    }

    override fun onClose() {
        responses.clear()
        captionCache.clear()
        synchronized(this) {
            streamServer?.close()
            streamServer = null
        }
        clearCache()
    }

    private fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /** Resolve [url] at [quality] (a height such as 720, or [AUTO_QUALITY]). */
    fun resolve(url: String, quality: Int, done: (ResolvedMedia?) -> Unit): Boolean =
        resolveKey(qualityKey(url, quality), done)

    fun cached(url: String, quality: Int): ResolvedMedia? = cachedKey(qualityKey(url, quality))

    /** The running load for [url] at [quality], or null when it's queued, finished or not started. */
    fun progress(url: String, quality: Int): LoadProgress? = qualityKey(url, quality)?.let { loads[it] }

    /**
     * YouTube's reason for refusing [url] the last time it was asked (such as its
     * bot check), or null when it didn't refuse or gave no reason.
     */
    fun refusal(url: String): String? = key(url)?.let { refusals[it] }

    /**
     * The caption tracks of [url]'s video, written and auto-generated. Blocks,
     * so call it off the main thread. Null when every lookup failed.
     *
     * IOS_PRODUCER, which the streams usually come from, lists no captions at
     * all, so [CAPTION_CLIENTS] and then the other stream clients are asked too.
     */
    fun captionTracks(url: String): List<CaptionTrack>? {
        val base = key(url)
        if (base == null) {
            DebugLog.log("captions: not a YouTube video URL: $url")
            return null
        }
        captionCache[base]?.takeIf { it.first > SystemClock.elapsedRealtime() }?.let {
            DebugLog.log("captions: $base: ${it.second.size} tracks (cached)")
            return it.second
        }
        val task = responseTask(base)
        task.run()
        val response = try {
            task.get()
        } catch (e: Throwable) {
            DebugLog.log("captions: $base: stream lookup failed: $e")
            null
        }
        var answered = response != null
        var tracks = Captions.tracks(response?.data?.optJSONObject("captions"))
        DebugLog.log(
            "captions: $base: stream answer from " +
                "${response?.clientIndex?.let { CLIENTS.getOrNull(it)?.name } ?: "none"} has ${tracks.size} tracks",
        )
        if (tracks.isEmpty()) {
            val streamClient = response?.clientIndex?.let { CLIENTS.getOrNull(it) }
            for (client in CAPTION_CLIENTS + CLIENTS) {
                if (client === streamClient) continue
                val data = try {
                    player(client, base, recordRefusal = false)
                } catch (e: Throwable) {
                    DebugLog.log("captions: $base: ${client.name} failed: $e")
                    null
                } ?: continue
                answered = true
                tracks = Captions.tracks(data.optJSONObject("captions"))
                if (tracks.isNotEmpty()) break
            }
        }
        DebugLog.log(
            "captions: $base: ${tracks.size} tracks" +
                tracks.joinToString(prefix = if (tracks.isEmpty()) "" else ": ") { "${it.name} [${it.languageCode}]" },
        )
        if (!answered) return null
        // An empty answer is looked up again next time, in case it was a passing refusal.
        if (tracks.isNotEmpty()) captionCache[base] = SystemClock.elapsedRealtime() + CAPTION_TTL_MS to tracks
        return tracks
    }

    private fun qualityKey(url: String, quality: Int): String? = key(url)?.let { "$it|$quality" }

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
        val base = key.substringBefore('|')
        val quality = key.substringAfter('|', "").toIntOrNull() ?: AUTO_QUALITY
        val tracker = LoadProgress()
        loads[key] = tracker
        try {
            if (base.startsWith("v:")) {
                fromDisk(base.removePrefix("v:"), quality)?.let { return it to FILE_TTL_MS }
            }
            val task = responseTask(base)
            // Runs here unless another load for this video already started (or finished) it.
            task.run()
            var response = try {
                task.get()
            } catch (_: Throwable) {
                null
            }
            var fromCache = true
            while (response != null) {
                val result = try {
                    streamFrom(response.data, quality, tracker)
                } catch (_: Throwable) {
                    null
                }
                if (result != null) {
                    if (!fromCache) responses[base] = completedTask(response)
                    return result
                }
                // This client's streams didn't work out; try the next one.
                tracker.stage = LoadStage.RESOLVING
                fromCache = false
                response = try {
                    fetchResponse(base, response.clientIndex + 1)
                } catch (_: Throwable) {
                    null
                }
            }
            responses.remove(base)
            return null
        } finally {
            loads.remove(key)
        }
    }

    /** The shared lookup for [base]: a running or still valid one, else a new (not yet started) task. */
    private fun responseTask(base: String): FutureTask<PlayerResponse?> {
        while (true) {
            val existing = responses[base]
            if (existing != null) {
                if (!existing.isDone || validResponse(existing) != null) return existing
                responses.remove(base, existing)
                continue
            }
            val task = FutureTask(Callable<PlayerResponse?> { fetchResponse(base, 0) })
            if (responses.putIfAbsent(base, task) == null) {
                trimResponses()
                return task
            }
        }
    }

    private fun completedTask(response: PlayerResponse): FutureTask<PlayerResponse?> =
        FutureTask(Callable<PlayerResponse?> { response }).also { it.run() }

    private fun validResponse(task: FutureTask<PlayerResponse?>): PlayerResponse? = try {
        task.get()?.takeIf { it.expires > SystemClock.elapsedRealtime() }
    } catch (_: Throwable) {
        null
    }

    private fun trimResponses() {
        if (responses.size <= MAX_RESPONSES) return
        val removable = responses.entries.filter { it.value.isDone }
        for (index in 0 until minOf(removable.size, responses.size - MAX_RESPONSES)) {
            val entry = removable[index]
            responses.remove(entry.key, entry.value)
        }
    }

    /** The first client from [fromClient] on whose answer has streams, trimmed to what's needed. */
    private fun fetchResponse(base: String, fromClient: Int): PlayerResponse? {
        for (index in fromClient until CLIENTS.size) {
            val data = try {
                player(CLIENTS[index], base)
            } catch (_: Throwable) {
                null
            } ?: continue
            val streaming = data.optJSONObject("streamingData") ?: continue
            val hasStreams = (streaming.optJSONArray("formats")?.length() ?: 0) > 0 ||
                (streaming.optJSONArray("adaptiveFormats")?.length() ?: 0) > 0
            if (!hasStreams) continue
            val expiresSeconds = streaming.optLong("expiresInSeconds", 0L).takeIf { it > 0 } ?: 3_600L
            val videoId = data.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
            val trimmed = JSONObject()
                .put("streamingData", streaming)
                .put("videoDetails", JSONObject().put("videoId", videoId))
            data.optJSONObject("captions")?.let { trimmed.put("captions", it) }
            val ttlMs = (expiresSeconds - URL_EXPIRY_MARGIN_S).coerceAtLeast(60L) * 1_000L
            return PlayerResponse(trimmed, index, SystemClock.elapsedRealtime() + ttlMs)
        }
        return null
    }

    /** [recordRefusal] false keeps a caption lookup's refusal from replacing the reason a video can't play. */
    private fun player(client: Client, key: String, recordRefusal: Boolean = true): JSONObject? {
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
                if (!response.ok()) {
                    DebugLog.log("youtube: ${client.name} for $key: HTTP ${response.statusCode}")
                    return null
                }
                JSONObject(response.text())
            }
        }
        val playability = data.optJSONObject("playabilityStatus")
        if (playability?.optString("status") != "OK") {
            DebugLog.log(
                "youtube: ${client.name} for $key: ${playability?.optString("status")} " +
                    "(${playability?.optString("reason").orEmpty()})",
            )
            if (recordRefusal) {
                playability?.optString("reason")?.takeIf { it.trim().isNotEmpty() }?.let { refusals[key] = it }
            }
            return null
        }
        if (recordRefusal) refusals.remove(key)
        val captionCount = data.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")?.length() ?: 0
        DebugLog.log("youtube: ${client.name} for $key: OK, $captionCount caption tracks")
        if (data.optJSONObject("videoDetails")?.optBoolean("isLive") == true) return null
        return data
    }

    private fun streamFrom(data: JSONObject, quality: Int, tracker: LoadProgress): Pair<ResolvedMedia, Long>? {
        val streaming = data.optJSONObject("streamingData") ?: return null
        val expiresSeconds = streaming.optLong("expiresInSeconds", 0L).takeIf { it > 0 } ?: 3_600L
        val videoId = data.optJSONObject("videoDetails")?.optString("videoId")?.takeIf { it.isNotEmpty() }

        val adaptive = streaming.optJSONArray("adaptiveFormats")
        val audio = adaptive?.let(::audioStream)
        val videos = if (adaptive != null && audio != null) videoStreams(adaptive) else emptyList()
        val muxed = muxedFormat(streaming.optJSONArray("formats"))
        val muxedWidth = muxed?.optInt("width", 640) ?: 0
        val muxedHeight = muxed?.optInt("height", 360) ?: 0
        val muxedQuality = if (muxed != null) minOf(muxedWidth, muxedHeight) else 0
        val qualities = (videos.map { it.quality } + listOf(muxedQuality))
            .filter { it > 0 }
            .distinct()
            .sorted()

        val muxedMedia = muxed?.let {
            ResolvedMedia(it.optString("url"), muxedWidth, muxedHeight, false, muxedQuality, qualities) to
                (expiresSeconds - URL_EXPIRY_MARGIN_S).coerceAtLeast(60L) * 1_000L
        }
        // Streaming the muxed format starts at once, so Auto takes it when there is one.
        if (muxedMedia != null && (quality == AUTO_QUALITY || quality == muxedQuality)) return muxedMedia

        if (audio == null || videoId == null) return muxedMedia
        pickVideo(videos, quality)?.let { video ->
            try {
                startStream(videoId, video, audio, qualities, expiresSeconds)
            } catch (_: Throwable) {
                null
            }?.let { return it }
        }
        // Without streaming, the whole video is downloaded and remuxed first, so it must fit.
        val fitting = videos.filter { it.length + audio.length <= MAX_TOTAL_BYTES }
        val video = pickVideo(fitting, quality) ?: return muxedMedia
        val file = downloadAndMux(videoId, video, audio, qualities, tracker) ?: return null
        val media = ResolvedMedia(
            Uri.fromFile(file).toString(),
            video.width,
            video.height,
            audioOnly = false,
            quality = video.quality,
            qualities = qualities,
        )
        return media to FILE_TTL_MS
    }

    /** The best stream up to [quality] (360p for Auto), else the lowest one. */
    private fun pickVideo(videos: List<Stream>, quality: Int): Stream? {
        val target = if (quality == AUTO_QUALITY) AUTO_MAX_QUALITY else quality
        return videos.filter { it.quality <= target }.maxByOrNull { it.quality }
            ?: videos.minByOrNull { it.quality }
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

    /** H.264 MP4 streams, the smallest one per quality. */
    private fun videoStreams(formats: JSONArray): List<Stream> {
        val byQuality = HashMap<Int, Stream>()
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue
            val stream = stream(format) ?: continue
            val existing = byQuality[stream.quality]
            if (existing == null || stream.length < existing.length) byQuality[stream.quality] = stream
        }
        return ArrayList(byQuality.values)
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
        val initRange = format.optJSONObject("initRange")
        val indexRange = format.optJSONObject("indexRange")
        return Stream(
            url,
            length,
            format.optInt("width", 16),
            format.optInt("height", 9),
            initEnd = initRange?.optString("end")?.toLongOrNull() ?: -1L,
            indexStart = indexRange?.optString("start")?.toLongOrNull() ?: -1L,
            indexEnd = indexRange?.optString("end")?.toLongOrNull() ?: -1L,
        )
    }

    /**
     * Plays [video] with [audio] through the local [StreamServer], merged on the
     * fly, so it starts after the first segments rather than a full download.
     * Null when the streams aren't DASH or loopback HTTP isn't allowed.
     */
    private fun startStream(
        videoId: String,
        video: Stream,
        audio: Stream,
        qualities: List<Int>,
        expiresSeconds: Long,
    ): Pair<ResolvedMedia, Long>? {
        if (!video.isDash || !audio.isDash || !loopbackAllowed()) return null
        val target = videoFile(videoId, video.quality)
        if (target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return ResolvedMedia(Uri.fromFile(target).toString(), video.width, video.height, false, video.quality, qualities) to
                FILE_TTL_MS
        }

        // Both streams' init segment and index, in parallel; a few KB each.
        var audioTrack: Mp4Merge.Track? = null
        val audioThread = Thread {
            audioTrack = try {
                dashTrack(audio)
            } catch (_: Throwable) {
                null
            }
        }
        audioThread.start()
        val videoTrack = dashTrack(video)
        audioThread.join()
        val layout = Mp4Merge.merge(videoTrack ?: return null, audioTrack ?: return null) ?: return null

        cacheDir.mkdirs()
        val keep = layout.total <= MAX_TOTAL_BYTES
        val partFile = File(cacheDir, "yt_${safeId(videoId)}.${video.quality}p.stream.part")
        val ttlMs = (expiresSeconds - URL_EXPIRY_MARGIN_S).coerceAtLeast(60L) * 1_000L
        if (keep) writeQualities(videoId, qualities)
        val url = server().register(
            layout,
            SystemClock.elapsedRealtime() + ttlMs,
            partFile.takeIf { keep },
            target.takeIf { keep },
        )
        val media = ResolvedMedia(url, video.width, video.height, false, video.quality, qualities)
        return media to ttlMs
    }

    private fun dashTrack(stream: Stream): Mp4Merge.Track? {
        val length = (stream.indexEnd + 1).toInt()
        val bytes = ByteArray(length)
        Http.Request("${stream.url}&range=0-${stream.indexEnd}").use { request ->
            request.setRequestTimeout(TIMEOUT_MS)
            request.execute().use { response ->
                if (!response.ok()) return null
                response.stream().use { input ->
                    var filled = 0
                    while (filled < length) {
                        val read = input.read(bytes, filled, length - filled)
                        if (read < 0) return null
                        filled += read
                    }
                }
            }
        }
        return Mp4Merge.parseTrack(stream.url, bytes, stream.initEnd.toInt(), stream.indexStart.toInt())
    }

    @Synchronized
    private fun server(): StreamServer {
        streamServer?.let { return it }
        val created = StreamServer { file -> trimCache(file) }
        streamServer = created
        return created
    }

    /** Discord blocks cleartext HTTP; PlayEmbeds allows it for 127.0.0.1 only (see [PlayEmbeds]). */
    private fun loopbackAllowed(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(StreamServer.LOOPBACK_HOST)
    } catch (_: Throwable) {
        false
    }

    private fun safeId(videoId: String): String = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun videoFile(videoId: String, quality: Int): File =
        File(cacheDir, "yt_${safeId(videoId)}.${quality}p$VIDEO_SUFFIX")

    private fun qualitiesFile(videoId: String): File = File(cacheDir, "yt_${safeId(videoId)}.qualities")

    /** The downloaded files for [videoId], by quality. */
    private fun cachedFiles(videoId: String): Map<Int, File> {
        val id = safeId(videoId)
        val files = HashMap<Int, File>()
        cacheDir.listFiles()?.forEach { file ->
            val matcher = YoutubeCache.VIDEO_FILE.matcher(file.name)
            if (!matcher.matches() || matcher.group(1) != id || file.length() <= 0) return@forEach
            matcher.group(2)?.toIntOrNull()?.let { files[it] = file }
        }
        return files
    }

    /** A video downloaded earlier: exactly [quality], or the best one kept for Auto. */
    private fun fromDisk(videoId: String, quality: Int): ResolvedMedia? {
        val files = cachedFiles(videoId)
        val entry = if (quality == AUTO_QUALITY) {
            files.entries.maxByOrNull { it.key }
        } else {
            files.entries.firstOrNull { it.key == quality }
        } ?: return null
        val file = entry.value
        file.setLastModified(System.currentTimeMillis())
        val size = videoSize(file) ?: return null
        val qualities = (readQualities(videoId) + files.keys).distinct().sorted()
        return ResolvedMedia(Uri.fromFile(file).toString(), size.first, size.second, false, entry.key, qualities)
    }

    private fun videoSize(file: File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (width == null || height == null || width <= 0 || height <= 0) {
                null
            } else if (rotation == 90 || rotation == 270) {
                height to width
            } else {
                width to height
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // Nothing left to free.
            }
        }
    }

    private fun readQualities(videoId: String): List<Int> = try {
        val array = JSONArray(qualitiesFile(videoId).readText())
        val result = ArrayList<Int>()
        for (index in 0 until array.length()) array.optInt(index).takeIf { it > 0 }?.let { result.add(it) }
        result
    } catch (_: Throwable) {
        emptyList()
    }

    private fun writeQualities(videoId: String, qualities: List<Int>) {
        try {
            qualitiesFile(videoId).writeText(JSONArray(qualities).toString())
        } catch (_: Throwable) {
            // Only used to list qualities for cached videos.
        }
    }

    private fun downloadAndMux(
        videoId: String,
        video: Stream,
        audio: Stream,
        qualities: List<Int>,
        tracker: LoadProgress,
    ): File? {
        cacheDir.mkdirs()
        val target = videoFile(videoId, video.quality)
        if (target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val prefix = "yt_${safeId(videoId)}.${video.quality}p"
        val videoFile = File(cacheDir, "$prefix.video.part")
        val audioFile = File(cacheDir, "$prefix.audio.part")
        val muxFile = File(cacheDir, "$prefix.mux.part")
        val totalBytes = video.length + audio.length
        try {
            tracker.totalBytes = totalBytes
            tracker.downloadedBytes.set(0L)
            val downloadStart = SystemClock.elapsedRealtime()
            tracker.stageStartMs = downloadStart
            tracker.stage = LoadStage.DOWNLOADING
            download(listOf(video to videoFile, audio to audioFile), tracker.downloadedBytes)
            val muxStart = SystemClock.elapsedRealtime()
            lastDownloadBytesPerSec = totalBytes * 1_000L / (muxStart - downloadStart).coerceAtLeast(1L)
            tracker.stageStartMs = muxStart
            tracker.stage = LoadStage.MUXING
            // Rewriting the containers takes about as long as copying the file; remuxing
            // sample by sample is the slow fallback for streams without a DASH index.
            if (!mergeFiles(video, audio, videoFile, audioFile, muxFile)) mux(videoFile, audioFile, muxFile)
            lastMuxBytesPerSec = totalBytes * 1_000L / (SystemClock.elapsedRealtime() - muxStart).coerceAtLeast(1L)
            if (!muxFile.renameTo(target)) return null
            writeQualities(videoId, qualities)
            trimCache(target)
            return target
        } catch (_: Throwable) {
            return null
        } finally {
            videoFile.delete()
            audioFile.delete()
            muxFile.delete()
        }
    }

    /**
     * Fetch every stream in [CHUNK_BYTES] ranges, [DOWNLOAD_THREADS] at a time.
     * One range per request: an open-ended request is throttled to playback speed.
     */
    private fun download(streams: List<Pair<Stream, File>>, downloaded: AtomicLong) {
        val chunks = ArrayList<Chunk>()
        for ((stream, file) in streams) {
            RandomAccessFile(file, "rw").use { it.setLength(stream.length) }
            var start = 0L
            while (start < stream.length) {
                val end = minOf(start + CHUNK_BYTES, stream.length) - 1
                chunks.add(Chunk(stream.url, file, start, end))
                start = end + 1
            }
        }
        val next = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        val workers = ArrayList<Thread>()
        while (workers.size < minOf(DOWNLOAD_THREADS, chunks.size)) {
            val worker = Thread {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (error.get() == null) {
                    val index = next.getAndIncrement()
                    if (index >= chunks.size) break
                    try {
                        fetchChunk(chunks[index], buffer, downloaded)
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    }
                }
            }
            worker.start()
            workers.add(worker)
        }
        workers.forEach { it.join() }
        error.get()?.let { throw it }
    }

    private fun fetchChunk(chunk: Chunk, buffer: ByteArray, downloaded: AtomicLong) {
        var attempt = 0
        while (true) {
            var written = 0L
            try {
                RandomAccessFile(chunk.file, "rw").use { out ->
                    out.seek(chunk.start)
                    Http.Request("${chunk.url}&range=${chunk.start}-${chunk.end}").use { request ->
                        request.setRequestTimeout(TIMEOUT_MS)
                        request.execute().use { response ->
                            if (!response.ok()) throw IOException("HTTP ${response.statusCode}")
                            response.stream().use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    written += read
                                    downloaded.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                }
                if (written != chunk.end - chunk.start + 1) throw IOException("Incomplete range")
                return
            } catch (failure: Throwable) {
                downloaded.addAndGet(-written)
                if (++attempt >= CHUNK_ATTEMPTS) throw failure
            }
        }
    }

    /** Merge downloaded DASH streams with [Mp4Merge]; false when they can't be merged that way. */
    private fun mergeFiles(video: Stream, audio: Stream, videoFile: File, audioFile: File, output: File): Boolean {
        if (!video.isDash || !audio.isDash) return false
        return try {
            RandomAccessFile(videoFile, "r").use { videoIn ->
                RandomAccessFile(audioFile, "r").use { audioIn ->
                    val videoTrack = fileTrack(video, videoIn) ?: return false
                    val audioTrack = fileTrack(audio, audioIn) ?: return false
                    val layout = Mp4Merge.merge(videoTrack, audioTrack) ?: return false
                    RandomAccessFile(output, "rw").use { out ->
                        out.setLength(0)
                        out.write(layout.header)
                        for (piece in layout.pieces) {
                            val source = if (piece.track === videoTrack) videoIn else audioIn
                            val data = ByteArray(piece.segment.size)
                            source.seek(piece.segment.sourceOffset)
                            source.readFully(data)
                            Mp4Merge.patchSegment(data, piece)
                            out.write(data)
                        }
                    }
                    true
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun fileTrack(stream: Stream, file: RandomAccessFile): Mp4Merge.Track? {
        val bytes = ByteArray((stream.indexEnd + 1).toInt())
        file.seek(0)
        file.readFully(bytes)
        return Mp4Merge.parseTrack(stream.url, bytes, stream.initEnd.toInt(), stream.indexStart.toInt())
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

    /** Keep the newest videos within [MAX_CACHED_FILES] and [MAX_CACHE_BYTES]; [keep] always stays. */
    private fun trimCache(keep: File) {
        val files = cacheDir.listFiles() ?: return
        val videos = files
            .filter { YoutubeCache.VIDEO_FILE.matcher(it.name).matches() }
            .sortedByDescending { it.lastModified() }
        var count = 0
        var total = 0L
        val keptIds = HashSet<String>()
        for (file in videos) {
            val length = file.length()
            if (file != keep && (count + 1 > MAX_CACHED_FILES || total + length > MAX_CACHE_BYTES)) {
                file.delete()
                continue
            }
            count++
            total += length
            val matcher = YoutubeCache.VIDEO_FILE.matcher(file.name)
            if (matcher.matches()) matcher.group(1)?.let { keptIds.add(it) }
        }
        files.forEach { file ->
            val matcher = YoutubeCache.QUALITIES_FILE.matcher(file.name)
            if (matcher.matches() && !keptIds.contains(matcher.group(1).orEmpty())) file.delete()
        }
    }

    companion object {
        /** Stream the muxed format when there is one, else download up to [AUTO_MAX_QUALITY]. */
        const val AUTO_QUALITY = 0
        const val AUTO_MAX_QUALITY = 360

        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val MUXED_ITAG = 18
        private const val AAC_ITAG = 140
        private const val MAX_TOTAL_BYTES = 250L * 1024L * 1024L
        private const val MAX_INDEX_BYTES = 4L * 1024L * 1024L
        private const val MAX_CACHE_BYTES = 400L * 1024L * 1024L
        private const val MAX_CACHED_FILES = 3
        private const val CHUNK_BYTES = 2L * 1024L * 1024L
        private const val CHUNK_ATTEMPTS = 2
        private const val DOWNLOAD_THREADS = 4
        private const val MAX_RESPONSES = 32
        private const val URL_EXPIRY_MARGIN_S = 600L
        private const val FILE_TTL_MS = 3_600_000L
        private const val CAPTION_TTL_MS = 3_600_000L
        private const val MIN_BUFFER_BYTES = 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MIN_RATE_SAMPLE_MS = 1_000L
        private const val VIDEO_SUFFIX = ".mp4"

        private val CLIENTS = listOf(
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

        /**
         * Asked only for caption tracks, which IOS_PRODUCER never lists. Their streams
         * need a PO token, but their caption URLs download without one (tested Nov
         * 2026), and they answered where VISIONOS and ANDROID_VR got a bot check.
         */
        private val CAPTION_CLIENTS = listOf(
            Client(
                name = "IOS",
                id = 5,
                version = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                extra = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82",
                ),
            ),
            Client(
                name = "ANDROID",
                id = 3,
                version = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                extra = mapOf(
                    "androidSdkVersion" to 30,
                    "osName" to "Android",
                    "osVersion" to "11",
                ),
            ),
        )
    }
}
