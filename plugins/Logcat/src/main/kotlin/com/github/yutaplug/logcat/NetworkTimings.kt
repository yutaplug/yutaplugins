package com.github.yutaplug.logcat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.api.PatcherAPI
import de.robv.android.xposed.XC_MethodHook
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Locale
import f0.e0.g.j as RealConnection

/**
 * Times Discord's DNS lookups, connects, TLS handshakes, requests, media downloads and uploads, which Discord
 * itself never logs. Lines go into Logcat's log through [DiagLog].
 */
internal class NetworkTimings(private val patcher: PatcherAPI, private val logger: Logger) {
    private val hooked = ArrayList<String>()
    private val failed = ArrayList<String>()

    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        running = true
        hook("DNS") { hookDns() }
        hook("TCP connect") { hookSocketConnect() }
        hook("TLS handshake (OkHttp)") { hookTls() }
        hook("HTTP requests (OkHttp)") { hookOkHttpRequests() }
        hook("Image downloads (Fresco)") { hookFresco() }
        hook("Aliucord Http (UploadSize uploads)") { hookAliucordHttp() }
        hook("Video downloads (ExoPlayer)") { hookVideoHttp() }
        hook("Video player") { hookVideoPlayer() }
        DiagLog.add("Started. Hooks: ${hooked.joinToString()}" + if (failed.isEmpty()) "" else "; failed: ${failed.joinToString()}")
    }

    fun stop() {
        if (!running) return
        running = false
        patcher.unpatchAll()
        watchdog.removeCallbacksAndMessages(null)
        synchronized(videoLoads) { videoLoads.clear() }
        synchronized(players) { players.clear() }
        hooked.clear()
        failed.clear()
    }

    private inline fun hook(name: String, block: () -> Unit) {
        try {
            block()
            hooked.add(name)
        } catch (e: Throwable) {
            failed.add(name)
            logger.error("Could not hook $name", e)
        }
    }

    /** Runs [before] and [after] around a method, passing the elapsed time to [after]. */
    private fun timed(
        member: java.lang.reflect.Member,
        before: (XC_MethodHook.MethodHookParam) -> Boolean = { true },
        after: (XC_MethodHook.MethodHookParam, Long) -> Unit,
    ) {
        val start = ThreadLocal<Long>()
        patcher.patch(
            member,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    start.set(if (safe { before(param) } == true) SystemClock.elapsedRealtime() else -1L)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val began = start.get() ?: return
                    start.remove()
                    if (began < 0) return
                    safe { after(param, SystemClock.elapsedRealtime() - began) }
                }
            },
        )
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        logger.error("Diagnostics hook failed", e)
        null
    }

    private fun hookDns() {
        timed(
            InetAddress::class.java.getDeclaredMethod("getAllByName", String::class.java),
            before = { DiagLog.isDiscordHost(it.args[0] as? String) },
        ) { param, ms ->
            val host = param.args[0] as String
            val stats = DiagLog.stats
            synchronized(DiagLog) {
                stats.dnsLookups++
                stats.dnsTotalMs += ms
            }
            val error = param.throwable
            @Suppress("UNCHECKED_CAST")
            val result = param.result as? Array<InetAddress>
            if (error != null || result == null) {
                DiagLog.add("DNS  $host FAILED after ${ms}ms: ${DiagLog.describe(error)}")
                return@timed
            }
            DiagLog.rememberIps(host, result)
            val v6 = result.count { it is Inet6Address }
            val v4 = result.count { it is Inet4Address }
            val first = result.firstOrNull()?.let { DiagLog.family(it) } ?: "none"
            val ips = result.joinToString { it.hostAddress ?: "?" }
            DiagLog.add("DNS  $host ${ms}ms: $v6 IPv6, $v4 IPv4, $first tried first [$ips]")
        }
    }

    private fun hookSocketConnect() {
        timed(
            Socket::class.java.getDeclaredMethod("connect", SocketAddress::class.java, Int::class.javaPrimitiveType),
            before = { param ->
                val address = param.args[0] as? InetSocketAddress
                address != null && discordHost(address) != null
            },
        ) { param, ms ->
            val address = param.args[0] as InetSocketAddress
            val host = discordHost(address) ?: return@timed
            val family = DiagLog.family(address.address)
            val stats = if (family == "IPv6") DiagLog.stats.v6 else DiagLog.stats.v4
            val error = param.throwable
            synchronized(DiagLog) {
                stats.connects++
                stats.totalMs += ms
                if (error != null) {
                    stats.failures++
                    stats.failedMs += ms
                }
            }
            val ip = address.address?.hostAddress ?: "?"
            val timeout = param.args[1] as Int
            val result = if (error == null) "ok" else "FAILED (${DiagLog.describe(error)}, timeout ${timeout}ms)"
            DiagLog.add("TCP  $host $family $ip:${address.port} ${ms}ms $result")
        }
    }

    private fun discordHost(address: InetSocketAddress): String? {
        val name = address.hostString
        if (DiagLog.isDiscordHost(name)) return name
        return DiagLog.hostForIp(address.address?.hostAddress ?: name)
    }

    private fun hookTls() {
        val method = RealConnection::class.java.declaredMethods.first { it.name == "g" && it.parameterTypes.size == 4 }
        timed(method, before = { DiagLog.isDiscordHost(routeHost(it.thisObject)) }) { param, ms ->
            val host = routeHost(param.thisObject) ?: return@timed
            val error = param.throwable
            val result = if (error == null) "ok" else "FAILED (${DiagLog.describe(error)})"
            DiagLog.add("TLS  $host ${ms}ms $result")
        }
    }

    private fun routeHost(connection: Any?): String? = (connection as? RealConnection)?.q?.a?.a?.g

    /** OkHttp's last interceptor: the time spent sending the request (and its upload) and reading the response headers. */
    private fun hookOkHttpRequests() {
        val method = f0.e0.h.b::class.java.getDeclaredMethod("intercept", Interceptor.Chain::class.java)
        timed(method, before = { DiagLog.isDiscordHost((it.args[0] as Interceptor.Chain).c().b.g) }) { param, ms ->
            val request: Request = (param.args[0] as Interceptor.Chain).c()
            val url = request.b
            val shape = DiagLog.pathShape(url.g, url.i)
            val sent = try {
                request.e?.contentLength() ?: 0L
            } catch (e: Throwable) {
                -1L
            }
            val error = param.throwable
            val stats = DiagLog.stats
            if (error != null) {
                synchronized(DiagLog) { stats.requestFailures++ }
                DiagLog.add("HTTP ${request.c} ${url.g}$shape FAILED after ${ms}ms (${DiagLog.describe(error)})")
                return@timed
            }
            val response = param.result as? Response
            val code = response?.m ?: 0
            val protocol = response?.k?.toString() ?: "?"
            val received = response?.p?.a() ?: -1L
            val upload = sent >= 64 * 1024
            if (upload) {
                synchronized(DiagLog) {
                    stats.uploads++
                    stats.uploadBytes += sent
                    stats.uploadMs += ms
                }
            }
            val body = if (sent > 0) ", sent ${DiagLog.size(sent)}" + (if (upload) DiagLog.speed(sent, ms) else "") else ""
            DiagLog.add(
                "HTTP ${request.c} ${url.g}$shape $code $protocol ${ms}ms$body, response ${DiagLog.size(received)}",
            )
        }
    }

    private class FrescoFetch {
        var host: String? = null
        var shape = ""
        var code = 0
        var length = -1L
        var headersMs = 0L
        var error: Throwable? = null
    }

    /** Fresco's HttpURLConnection fetcher, used for images and other media previews. */
    private fun hookFresco() {
        val fetcher = Class.forName("b.f.j.p.a0")
        val task = Class.forName("b.f.j.p.y")
        val current = ThreadLocal<FrescoFetch>()
        val connect = fetcher.getDeclaredMethod("a", Uri::class.java, Int::class.javaPrimitiveType)

        timed(task.getDeclaredMethod("run"), before = { current.set(FrescoFetch()); true }) { param, ms ->
            val fetch = current.get() ?: return@timed
            current.remove()
            val host = fetch.host ?: return@timed
            val stats = DiagLog.stats
            val error = param.throwable
            if (fetch.code == 0 || error != null) {
                synchronized(DiagLog) { stats.requestFailures++ }
                DiagLog.add("MEDIA GET $host${fetch.shape} FAILED after ${ms}ms (${DiagLog.describe(error ?: fetch.error)})")
                return@timed
            }
            synchronized(DiagLog) {
                stats.downloads++
                if (fetch.length > 0) stats.downloadBytes += fetch.length
                stats.downloadMs += ms
            }
            val bodyMs = ms - fetch.headersMs
            DiagLog.add(
                "MEDIA GET $host${fetch.shape} ${fetch.code} ${DiagLog.size(fetch.length)}: headers after " +
                    "${fetch.headersMs}ms, body ${bodyMs}ms${DiagLog.speed(fetch.length, bodyMs)}, total ${ms}ms",
            )
        }

        // Called once per redirect; the innermost call returns first and holds the final URL.
        val started = ThreadLocal<Long>()
        timed(connect, before = {
            if (current.get() != null && started.get() == null) started.set(SystemClock.elapsedRealtime())
            true
        }) { param, _ ->
            val fetch = current.get() ?: return@timed
            if (fetch.host != null) return@timed
            val uri = param.args[0] as? Uri
            val host = uri?.host
            val begin = started.get()
            started.remove()
            if (!DiagLog.isDiscordHost(host)) {
                current.remove()
                return@timed
            }
            fetch.host = host
            fetch.shape = DiagLog.pathShape(host!!, uri.pathSegments ?: emptyList())
            fetch.headersMs = if (begin != null) SystemClock.elapsedRealtime() - begin else 0L
            fetch.error = param.throwable
            val connection = param.result as? HttpURLConnection
            if (connection != null) {
                fetch.code = try {
                    connection.responseCode
                } catch (e: Throwable) {
                    0
                }
                fetch.length = connection.contentLength.toLong()
            }
        }
    }

    private class AliucordRequest(val started: Long) {
        var length = -1L
    }

    /**
     * Aliucord's own HttpURLConnection client. Its UploadSize core plugin sends attachments with it: a POST to
     * /channels/:id/attachments, a PUT of the file to Google Cloud Storage, then the message POST. The PUT body is
     * written before `execute()`, so timing runs from creating the request until the response arrives.
     */
    private fun hookAliucordHttp() {
        val requests = java.util.WeakHashMap<Http.Request, AliucordRequest>()

        patcher.patch(
            Http.Request::class.java.getDeclaredConstructor(String::class.java, String::class.java),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val request = param.thisObject as? Http.Request ?: return
                    if (param.throwable != null || !DiagLog.isDiscordHost(request.conn.url.host)) return
                    synchronized(requests) { requests[request] = AliucordRequest(SystemClock.elapsedRealtime()) }
                }
            },
        )

        patcher.patch(
            Http.Request::class.java.getDeclaredMethod("setHeader", String::class.java, String::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!"Content-Length".equals(param.args[0] as? String, ignoreCase = true)) return
                    val entry = synchronized(requests) { requests[param.thisObject] } ?: return
                    entry.length = try {
                        java.lang.Long.parseLong((param.args[1] as String).trim())
                    } catch (e: Throwable) {
                        -1L
                    }
                }
            },
        )

        patcher.patch(
            Http.Response::class.java.getDeclaredConstructor(Http.Request::class.java),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safe {
                        val request = param.args[0] as? Http.Request ?: return@safe
                        val entry = synchronized(requests) { requests.remove(request) } ?: return@safe
                        val ms = SystemClock.elapsedRealtime() - entry.started
                        val url = request.conn.url
                        val host = url.host
                        val shape = DiagLog.pathShape(host, Uri.parse(url.toString()).pathSegments ?: emptyList())
                        val method = request.conn.requestMethod
                        val error = param.throwable
                        val stats = DiagLog.stats
                        if (error != null) {
                            synchronized(DiagLog) { stats.requestFailures++ }
                            DiagLog.add("ALIUCORD $method $host$shape FAILED after ${ms}ms (${DiagLog.describe(error)})")
                            return@safe
                        }
                        val code = (param.thisObject as Http.Response).statusCode
                        val size = entry.length
                        val upload = size >= 64 * 1024
                        if (upload) {
                            synchronized(DiagLog) {
                                stats.uploads++
                                stats.uploadBytes += size
                                stats.uploadMs += ms
                            }
                        }
                        val body = if (size > 0) ", sent ${DiagLog.size(size)}" + (if (upload) DiagLog.speed(size, ms) else "") else ""
                        DiagLog.add("ALIUCORD $method $host$shape $code ${ms}ms$body")
                    }
                }
            },
        )
    }

    private class VideoLoad(val label: String, val opened: Long) {
        var lastBytes = 0L
        var lastProgress = opened
        var stalled = false
    }

    private val videoLoads = java.util.WeakHashMap<Any, VideoLoad>()
    private val watchdog = Handler(Looper.getMainLooper())

    private val dataSource by lazy { Class.forName("b.i.a.c.e3.r") }
    private val dataSpecField by lazy { field(dataSource, "m") }
    private val codeField by lazy { field(dataSource, "q") }
    private val lengthField by lazy { field(dataSource, "r") }
    private val bytesField by lazy { field(dataSource, "s") }

    private fun field(cls: Class<*>, name: String): Field = cls.getDeclaredField(name).apply { isAccessible = true }

    private fun bytesRead(source: Any): Long = try {
        bytesField.getLong(source)
    } catch (e: Throwable) {
        -1L
    }

    /** "host/shape @offset" for a DataSpec; other hosts are named only as such. */
    private fun videoLabel(spec: Any?): String {
        if (spec == null) return "?"
        val uri = spec.javaClass.getDeclaredField("a").get(spec) as? Uri
        val position = spec.javaClass.getDeclaredField("f").getLong(spec)
        val host = uri?.host
        val where = when {
            uri == null -> "?"
            uri.scheme == "file" || uri.scheme == "content" -> "(local file)"
            DiagLog.isDiscordHost(host) -> host + DiagLog.pathShape(host!!, uri.pathSegments ?: emptyList())
            else -> "(non-Discord host)"
        }
        return if (position > 0) "$where from ${DiagLog.size(position)}" else where
    }

    /**
     * ExoPlayer's DefaultHttpDataSource (`b.i.a.c.e3.r`), which Discord's video player downloads through (behind
     * its cache). `open` connects and reads the headers, `read` streams the body, `close` ends the load. A
     * watchdog reports loads that stop receiving data, since a stalled read never throws and only shows as
     * endless buffering.
     */
    private fun hookVideoHttp() {
        val dataSpec = Class.forName("b.i.a.c.e3.n")
        timed(dataSource.getDeclaredMethod("a", dataSpec)) { param, ms ->
            val source = param.thisObject
            val label = videoLabel(param.args[0])
            val code = codeField.getInt(source)
            val error = param.throwable
            if (error != null) {
                synchronized(DiagLog) { DiagLog.stats.requestFailures++ }
                val status = if (code != 0) "HTTP $code" else DiagLog.describe(error)
                DiagLog.add("VIDEO open $label FAILED after ${ms}ms ($status)")
                return@timed
            }
            val length = lengthField.getLong(source)
            DiagLog.add("VIDEO open $label $code, ${DiagLog.size(length)} to read, headers after ${ms}ms")
            synchronized(videoLoads) { videoLoads[source] = VideoLoad(label, SystemClock.elapsedRealtime()) }
            scheduleWatchdog()
        }

        patcher.patch(
            dataSource.getDeclaredMethod("close"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safe {
                        val source = param.thisObject
                        val load = synchronized(videoLoads) { videoLoads.remove(source) } ?: return@safe
                        val ms = SystemClock.elapsedRealtime() - load.opened
                        val bytes = bytesRead(source)
                        val length = lengthField.getLong(source)
                        synchronized(DiagLog) {
                            val stats = DiagLog.stats
                            stats.downloads++
                            if (bytes > 0) stats.downloadBytes += bytes
                            stats.downloadMs += ms
                        }
                        val of = if (length > 0) " of ${DiagLog.size(length)}" else ""
                        DiagLog.add(
                            "VIDEO closed ${load.label}: read ${DiagLog.size(bytes)}$of in ${ms}ms" +
                                DiagLog.speed(bytes, ms),
                        )
                    }
                }
            },
        )

        // Read errors (a dropped or reset connection mid-download).
        patcher.patch(
            dataSource.getDeclaredMethod("read", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val error = param.throwable ?: return
                    safe {
                        val load = synchronized(videoLoads) { videoLoads[param.thisObject] }
                        val label = load?.label ?: videoLabel(dataSpecField.get(param.thisObject))
                        synchronized(DiagLog) { DiagLog.stats.requestFailures++ }
                        DiagLog.add(
                            "VIDEO read $label FAILED after ${DiagLog.size(bytesRead(param.thisObject))} " +
                                "(${DiagLog.describe(error)})",
                        )
                    }
                }
            },
        )
    }

    private fun scheduleWatchdog() {
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed({ checkStalls() }, WATCHDOG_MS)
    }

    private fun checkStalls() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val open = synchronized(videoLoads) { videoLoads.entries.map { it.key to it.value } }
        for ((source, load) in open) {
            val bytes = bytesRead(source)
            if (bytes > load.lastBytes) {
                if (load.stalled) {
                    DiagLog.add("VIDEO ${load.label} receiving data again after ${now - load.lastProgress}ms")
                }
                load.lastBytes = bytes
                load.lastProgress = now
                load.stalled = false
            } else if (!load.stalled && now - load.lastProgress >= STALL_MS) {
                load.stalled = true
                DiagLog.add(
                    "VIDEO ${load.label} STALLED: no data for ${now - load.lastProgress}ms, " +
                        "${DiagLog.size(bytes)} read so far",
                )
            }
        }
        if (open.isNotEmpty()) watchdog.postDelayed({ checkStalls() }, WATCHDOG_MS)
    }

    private class PlayerState {
        var state = 0
        var since = SystemClock.elapsedRealtime()
    }

    private val players = java.util.WeakHashMap<Any, PlayerState>()

    /** Discord's ExoPlayer listener (`b.a.p.k`): playback state changes, play/pause and playback errors. */
    private fun hookVideoPlayer() {
        val listener = Class.forName("b.a.p.k")

        patcher.patch(
            listener.getDeclaredMethod("y", Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safe {
                        val state = param.args[0] as Int
                        val now = SystemClock.elapsedRealtime()
                        val player = synchronized(players) { players.getOrPut(param.thisObject) { PlayerState() } }
                        if (player.state == state) return@safe
                        val previous = player.state
                        val took = now - player.since
                        player.state = state
                        player.since = now
                        val after = if (previous != 0) " after ${took}ms ${stateName(previous).toLowerCase(Locale.US)}" else ""
                        DiagLog.add("PLAYER ${stateName(state)}$after")
                    }
                }
            },
        )

        patcher.patch(
            listener.getDeclaredMethod("j0", Boolean::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    DiagLog.add(if (param.args[0] as Boolean) "PLAYER playing" else "PLAYER not playing")
                }
            },
        )

        patcher.patch(
            listener.getDeclaredMethod("s", com.google.android.exoplayer2.PlaybackException::class.java),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safe {
                        val error = param.args[0] as com.google.android.exoplayer2.PlaybackException
                        val code = error.errorCode
                        val cause = error.cause?.let { ", cause ${DiagLog.describe(it)}" } ?: ""
                        DiagLog.add("PLAYER ERROR $code ${errorName(code)}$cause")
                    }
                }
            },
        )
    }

    private fun stateName(state: Int) = when (state) {
        1 -> "IDLE"
        2 -> "BUFFERING"
        3 -> "READY"
        4 -> "ENDED"
        else -> "STATE $state"
    }

    /** ExoPlayer's PlaybackException error codes (2.15). */
    private fun errorName(code: Int) = when (code) {
        1000 -> "unspecified"
        1001 -> "remote error"
        1002 -> "behind live window"
        1003 -> "timeout"
        1004 -> "failed runtime check"
        2000 -> "IO unspecified"
        2001 -> "network connection failed"
        2002 -> "network connection timeout"
        2003 -> "invalid HTTP content type"
        2004 -> "bad HTTP status"
        2005 -> "file not found"
        2006 -> "no permission"
        2007 -> "cleartext not permitted"
        2008 -> "read position out of range"
        3001 -> "malformed container"
        3002 -> "malformed manifest"
        3003 -> "unsupported container"
        3004 -> "unsupported manifest"
        4001 -> "decoder init failed"
        4002 -> "decoder query failed"
        4003 -> "decoding failed"
        4004 -> "format exceeds device capabilities"
        4005 -> "format unsupported"
        5001 -> "audio track init failed"
        5002 -> "audio track write failed"
        else -> "unknown"
    }

    /** Device network state, without any addresses. */
    fun networkSummary(context: Context): List<String> {
        val out = ArrayList<String>()
        out.add("Android API ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT < 23) return out
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network == null) {
                out.add("No active network")
                return out
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null) {
                val transports = ArrayList<String>()
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("Wi-Fi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("mobile data")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN")
                out.add("Network: " + if (transports.isEmpty()) "other" else transports.joinToString(" + "))
                out.add(
                    String.format(
                        Locale.US,
                        "Bandwidth estimate: %d kbps down, %d kbps up",
                        caps.linkDownstreamBandwidthKbps,
                        caps.linkUpstreamBandwidthKbps,
                    ),
                )
            }
            val link = cm.getLinkProperties(network)
            if (link != null) {
                val addresses = link.linkAddresses.map { it.address }
                val globalV6 = addresses.any {
                    it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isLoopbackAddress &&
                        (it.address[0].toInt() and 0xfe) != 0xfc
                }
                out.add("Has IPv4 address: ${yes(addresses.any { it is Inet4Address })}")
                out.add("Has public IPv6 address: ${yes(globalV6)}")
                val dns = link.dnsServers
                out.add("DNS servers: ${dns.count { it is Inet4Address }} IPv4, ${dns.count { it is Inet6Address }} IPv6")
                if (Build.VERSION.SDK_INT >= 28) out.add("Private DNS active: ${yes(link.isPrivateDnsActive)}")
            }
        } catch (e: Throwable) {
            out.add("Network state unavailable: ${e.javaClass.simpleName}")
        }
        return out
    }

    private fun yes(value: Boolean) = if (value) "yes" else "no"

    /** Network state and timing totals, for the top of the exported log. */
    fun buildReport(context: Context): String {
        val s = DiagLog.stats
        val sb = StringBuilder()
        for (line in networkSummary(context)) sb.append(line).append('\n')
        synchronized(DiagLog) {
            sb.append(familyLine("IPv6", s.v6)).append('\n')
            sb.append(familyLine("IPv4", s.v4)).append('\n')
            if (s.dnsLookups > 0) sb.append("DNS lookups: ${s.dnsLookups}, avg ${s.dnsTotalMs / s.dnsLookups}ms\n")
            if (s.uploads > 0) {
                sb.append(
                    "Uploads: ${s.uploads}, ${DiagLog.size(s.uploadBytes)} in ${s.uploadMs}ms" +
                        DiagLog.speed(s.uploadBytes, s.uploadMs) + "\n",
                )
            }
            if (s.downloads > 0) {
                sb.append(
                    "Media downloads: ${s.downloads}, ${DiagLog.size(s.downloadBytes)} in ${s.downloadMs}ms" +
                        DiagLog.speed(s.downloadBytes, s.downloadMs) + "\n",
                )
            }
            sb.append("Failed requests: ${s.requestFailures}\n")
        }
        return sb.toString()
    }

    /** One line for the settings page. */
    fun summary(): String {
        val s = DiagLog.stats
        return synchronized(DiagLog) {
            "IPv6 connects: ${s.v6.connects} (${s.v6.failures} failed), " +
                "IPv4 connects: ${s.v4.connects} (${s.v4.failures} failed)."
        }
    }

    private fun familyLine(name: String, f: DiagLog.Family): String {
        if (f.connects == 0) return "$name connects: none"
        val ok = f.connects - f.failures
        val okAvg = if (ok > 0) (f.totalMs - f.failedMs) / ok else 0
        val failText = if (f.failures > 0) ", ${f.failures} failed (avg ${f.failedMs / f.failures}ms wasted)" else ""
        return "$name connects: ${f.connects}, $ok ok (avg ${okAvg}ms)$failText"
    }

    companion object {
        const val TAG = "NetTiming"
        private const val WATCHDOG_MS = 5000L
        private const val STALL_MS = 10000L
    }
}
