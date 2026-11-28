package com.github.yutaplug.playembeds

import android.net.Uri
import android.os.SystemClock
import com.aliucord.Http
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Serves merged YouTube streams ([Mp4Merge]) to Discord's ExoPlayer over
 * plain HTTP on 127.0.0.1, so playback starts as soon as the first segments
 * arrive instead of after a full download and remux. Each requested range is
 * fetched from googlevideo segment by segment, with the next few prefetched.
 *
 * Fetched segments are also written to a part file at their merged position;
 * once every segment has been fetched (the video was watched to the end),
 * that file is complete and is handed to [onComplete] to keep as a cached MP4.
 */
internal class StreamServer(private val onComplete: (File) -> Unit) {
    private inner class Session(
        val token: String,
        val layout: Mp4Merge.Layout,
        val expires: Long,
        val partFile: File?,
        val target: File?,
    ) {
        @Volatile var lastAccess = SystemClock.elapsedRealtime()
        private val memory = LinkedHashMap<Int, FutureTask<ByteArray>>(16, 0.75f, true)
        private val written = BooleanArray(layout.pieces.size)
        private var writtenCount = 0
        private var file: RandomAccessFile? = null
        @Volatile var closed = false

        init {
            if (partFile != null && target != null) {
                try {
                    file = RandomAccessFile(partFile, "rw").apply {
                        setLength(layout.total)
                        seek(0)
                        write(layout.header)
                    }
                } catch (_: Throwable) {
                    file = null
                }
            }
        }

        fun write(out: OutputStream, start: Long, end: Long) {
            var position = start
            val header = layout.header
            if (position < header.size) {
                val to = minOf(end + 1, header.size.toLong()).toInt()
                out.write(header, position.toInt(), to - position.toInt())
                position = to.toLong()
            }
            var index = pieceAt(position)
            val pieces = layout.pieces
            while (position <= end && index < pieces.size) {
                for (ahead in 1..PREFETCH_AHEAD) prefetch(index + ahead)
                val piece = pieces[index]
                val data = bytes(index)
                val from = (position - piece.offset).toInt()
                val to = minOf(end + 1 - piece.offset, data.size.toLong()).toInt()
                out.write(data, from, to - from)
                position += to - from
                lastAccess = SystemClock.elapsedRealtime()
                index++
            }
            out.flush()
        }

        /** The piece holding merged byte [position]. */
        private fun pieceAt(position: Long): Int {
            val pieces = layout.pieces
            var low = 0
            var high = pieces.size - 1
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (pieces[middle].offset <= position) low = middle else high = middle - 1
            }
            return low
        }

        private fun task(index: Int): FutureTask<ByteArray> = synchronized(memory) {
            memory[index]?.let { return it }
            val task = FutureTask(Callable { fetch(index) })
            memory[index] = task
            if (memory.size > MAX_MEMORY_PIECES) {
                val iterator = memory.entries.iterator()
                while (memory.size > MAX_MEMORY_PIECES && iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key != index && entry.value.isDone) iterator.remove()
                }
            }
            task
        }

        private fun prefetch(index: Int) {
            if (index >= layout.pieces.size || closed) return
            val task = task(index)
            if (task.isDone) return
            try {
                fetchers.execute(task)
            } catch (_: Throwable) {
                // Shutting down.
            }
        }

        private fun bytes(index: Int): ByteArray {
            val task = task(index)
            task.run()
            return try {
                task.get()
            } catch (error: Throwable) {
                // Let a later request try again.
                synchronized(memory) { if (memory[index] === task) memory.remove(index) }
                throw IOException("Segment $index failed", error)
            }
        }

        private fun fetch(index: Int): ByteArray {
            val piece = layout.pieces[index]
            val segment = piece.segment
            val data = ByteArray(segment.size)
            var attempt = 0
            while (true) {
                try {
                    val url = "${piece.track.url}&range=${segment.sourceOffset}-${segment.sourceOffset + segment.size - 1}"
                    readFully(url, data)
                    break
                } catch (error: Throwable) {
                    if (closed || ++attempt >= FETCH_ATTEMPTS) throw error
                }
            }
            Mp4Merge.patchSegment(data, piece)
            store(index, piece.offset, data)
            return data
        }

        private fun store(index: Int, offset: Long, data: ByteArray) {
            var completed = false
            synchronized(this) {
                val output = file ?: return
                if (written[index]) return
                try {
                    output.seek(offset)
                    output.write(data)
                } catch (_: Throwable) {
                    discardPart()
                    return
                }
                written[index] = true
                writtenCount++
                completed = writtenCount == written.size
                if (completed) {
                    try {
                        output.close()
                    } catch (_: Throwable) {
                        // Checked by the rename below.
                    }
                    file = null
                }
            }
            if (completed && partFile != null && target != null && partFile.renameTo(target)) onComplete(target)
        }

        @Synchronized
        private fun discardPart() {
            try {
                file?.close()
            } catch (_: Throwable) {
                // Deleted next.
            }
            file = null
            if (partFile != null && partFile.exists() && target?.exists() != true) partFile.delete()
        }

        fun close() {
            closed = true
            discardPart()
            synchronized(memory) { memory.clear() }
        }
    }

    /** A remote file passed through as is, with request headers the player can't send itself. */
    private class Proxy(val token: String, val url: String, val headers: Map<String, String>, val expires: Long) {
        @Volatile var lastAccess = SystemClock.elapsedRealtime()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val proxies = ConcurrentHashMap<String, Proxy>()
    private val connections = Executors.newCachedThreadPool()
    private val fetchers = Executors.newFixedThreadPool(FETCH_THREADS)
    private val random = SecureRandom()
    private var socket: ServerSocket? = null
    @Volatile private var closed = false

    /** Serve [layout] until [expires] (elapsedRealtime); returns its URL. */
    fun register(layout: Mp4Merge.Layout, expires: Long, partFile: File?, target: File?): String {
        val port = port()
        removeStale()
        val token = newToken()
        sessions[token] = Session(token, layout, expires, partFile, target)
        return "http://$LOOPBACK_HOST:$port/yt/$token"
    }

    /**
     * Serve the remote file at [url] until [expires] (elapsedRealtime), fetched with
     * [headers] (cookies, referer) that Discord's player has no way to send; returns its URL.
     */
    fun registerProxy(url: String, headers: Map<String, String>, expires: Long): String {
        val port = port()
        val now = SystemClock.elapsedRealtime()
        proxies.values.filter { it.expires <= now }.forEach { proxies.remove(it.token, it) }
        val overflow = proxies.size + 1 - MAX_SESSIONS
        if (overflow > 0) proxies.values.sortedBy { it.lastAccess }.take(overflow).forEach { proxies.remove(it.token, it) }
        val token = newToken()
        proxies[token] = Proxy(token, url, headers, expires)
        return "http://$LOOPBACK_HOST:$port/px/$token"
    }

    private fun newToken(): String =
        java.lang.Long.toHexString(random.nextLong() and Long.MAX_VALUE) +
            java.lang.Long.toHexString(random.nextLong() and Long.MAX_VALUE)

    /** Whether [uri] is one of this server's streams that can still play. */
    fun serves(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "http" || parsed.host != LOOPBACK_HOST || parsed.port != socket?.localPort) return false
        val token = parsed.lastPathSegment.orEmpty()
        proxies[token]?.let { return it.expires > SystemClock.elapsedRealtime() }
        val session = sessions[token] ?: return false
        return !session.closed && session.expires > SystemClock.elapsedRealtime()
    }

    fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: Throwable) {
            // Already closed.
        }
        socket = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        proxies.clear()
        connections.shutdownNow()
        fetchers.shutdownNow()
    }

    @Synchronized
    private fun port(): Int {
        socket?.let { return it.localPort }
        if (closed) throw IOException("Closed")
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
        socket = server
        Thread({
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                try {
                    connections.execute { handle(client) }
                } catch (_: Throwable) {
                    client.close()
                }
            }
        }, "PlayEmbeds-stream").apply { isDaemon = true }.start()
        return server.localPort
    }

    /** Drop expired sessions, and the least recently used ones past [MAX_SESSIONS]. */
    private fun removeStale() {
        val now = SystemClock.elapsedRealtime()
        sessions.values.filter { it.expires <= now }.forEach { remove(it) }
        val overflow = sessions.size + 1 - MAX_SESSIONS
        if (overflow > 0) sessions.values.sortedBy { it.lastAccess }.take(overflow).forEach { remove(it) }
    }

    private fun remove(session: Session) {
        sessions.remove(session.token, session)
        session.close()
    }

    private fun handle(client: Socket) {
        try {
            client.use { connection ->
                connection.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(connection.getInputStream())
                val out = connection.getOutputStream()
                val requestLine = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals("range", ignoreCase = true)) {
                        range = line.substring(colon + 1).trim()
                    }
                }
                val firstSpace = requestLine.indexOf(' ')
                val secondSpace = requestLine.indexOf(' ', firstSpace + 1)
                if (firstSpace <= 0 || secondSpace <= firstSpace) return respond(out, "400 Bad Request")
                val method = requestLine.substring(0, firstSpace)
                val path = requestLine.substring(firstSpace + 1, secondSpace)
                if (path.startsWith("/px/")) {
                    val proxy = proxies[path.removePrefix("/px/")]
                    if (proxy == null || proxy.expires <= SystemClock.elapsedRealtime()) {
                        return respond(out, "404 Not Found")
                    }
                    proxy.lastAccess = SystemClock.elapsedRealtime()
                    return forward(out, method, range, proxy)
                }
                val session = sessions[path.removePrefix("/yt/")]
                if (session == null || session.closed) return respond(out, "404 Not Found")
                session.lastAccess = SystemClock.elapsedRealtime()

                val total = session.layout.total
                var start = 0L
                var end = total - 1
                var partial = false
                if (range != null && range.startsWith("bytes=")) {
                    val spec = range.removePrefix("bytes=")
                    val dash = spec.indexOf('-')
                    val from = if (dash >= 0) spec.substring(0, dash).trim() else ""
                    val to = if (dash >= 0) spec.substring(dash + 1).trim() else ""
                    if (from.isNotEmpty()) {
                        start = from.toLongOrNull() ?: 0L
                        to.toLongOrNull()?.let { end = minOf(it, total - 1) }
                    } else {
                        // A suffix range: the last N bytes.
                        to.toLongOrNull()?.let { start = (total - it).coerceAtLeast(0L) }
                    }
                    partial = true
                }
                if (start >= total || start > end) {
                    return respond(out, "416 Range Not Satisfiable", "Content-Range: bytes */$total\r\n")
                }
                val headers = StringBuilder()
                    .append("HTTP/1.1 ").append(if (partial) "206 Partial Content" else "200 OK").append("\r\n")
                    .append("Content-Type: video/mp4\r\n")
                    .append("Accept-Ranges: bytes\r\n")
                    .append("Content-Length: ").append(end - start + 1).append("\r\n")
                if (partial) headers.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n")
                headers.append("Connection: close\r\n\r\n")
                out.write(headers.toString().toByteArray(Charsets.US_ASCII))
                if (method == "HEAD") return out.flush()
                session.write(out, start, end)
            }
        } catch (_: Throwable) {
            // The player closed the connection (a seek or release) or a segment failed.
        }
    }

    /** Passes one request through to [proxy]'s file with its headers, and the response back. */
    private fun forward(out: OutputStream, method: String, range: String?, proxy: Proxy) {
        val connection = URL(proxy.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = FETCH_TIMEOUT_MS
            connection.readTimeout = FETCH_TIMEOUT_MS
            connection.requestMethod = if (method == "HEAD") "HEAD" else "GET"
            connection.instanceFollowRedirects = true
            proxy.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (range != null) connection.setRequestProperty("Range", range)
            val code = connection.responseCode
            if (code < 200 || code > 299) return respond(out, if (code == 416) "416 Range Not Satisfiable" else "502 Bad Gateway")
            val headers = StringBuilder()
                .append("HTTP/1.1 ").append(if (code == 206) "206 Partial Content" else "200 OK").append("\r\n")
                .append("Content-Type: ").append(connection.contentType ?: "video/mp4").append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
            connection.getHeaderField("Content-Length")?.let { headers.append("Content-Length: ").append(it).append("\r\n") }
            connection.getHeaderField("Content-Range")?.let { headers.append("Content-Range: ").append(it).append("\r\n") }
            headers.append("Connection: close\r\n\r\n")
            out.write(headers.toString().toByteArray(Charsets.US_ASCII))
            if (method != "HEAD") {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(PROXY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        proxy.lastAccess = SystemClock.elapsedRealtime()
                    }
                }
            }
            out.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun respond(out: OutputStream, status: String, extraHeaders: String = "") {
        out.write(
            "HTTP/1.1 $status\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) break
            if (byte != '\r'.code) line.append(byte.toChar())
            if (line.length > MAX_LINE) return null
        }
        return line.toString()
    }

    private fun readFully(url: String, into: ByteArray) {
        Http.Request(url).use { request ->
            request.setRequestTimeout(FETCH_TIMEOUT_MS)
            request.execute().use { response ->
                if (!response.ok()) throw IOException("HTTP ${response.statusCode}")
                response.stream().use { input ->
                    var filled = 0
                    while (filled < into.size) {
                        val read = input.read(into, filled, into.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                    if (filled != into.size) throw IOException("Incomplete segment")
                }
            }
        }
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val FETCH_TIMEOUT_MS = 15_000
        private const val FETCH_THREADS = 4
        private const val FETCH_ATTEMPTS = 3
        private const val PREFETCH_AHEAD = 3
        private const val MAX_MEMORY_PIECES = 6
        private const val MAX_SESSIONS = 6
        private const val MAX_LINE = 8 * 1024
        private const val PROXY_BUFFER = 64 * 1024
    }
}
