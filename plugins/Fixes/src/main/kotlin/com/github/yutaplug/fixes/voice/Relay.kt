package com.github.yutaplug.fixes.voice

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * UDP relay on 127.0.0.1 between Discord 126.21's voice engine and Discord's media server.
 *
 * Engine leg: xsalsa20_poly1305_lite_rtpsize with a local key (all the old engine supports that's close to today's
 * protocol). Server leg: aead_aes256_gcm_rtpsize / aead_xchacha20_poly1305_rtpsize with the real key. In between,
 * Opus frames get DAVE end-to-end encryption on the way out and lose it on the way in.
 */
internal class Relay(
    serverIp: String,
    serverPort: Int,
    private val ssrc: Int,
    private val keys: MediaKeys,
    private val log: (String) -> Unit,
) {
    private val local = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    private val remote = DatagramSocket()
    private val server = InetSocketAddress(InetAddress.getByName(serverIp), serverPort)

    val localPort: Int get() = local.localPort

    @Volatile private var running = true

    @Volatile private var engine: SocketAddress? = null

    // server leg
    @Volatile private var serverOut: PacketCipher? = null

    @Volatile private var serverIn: PacketCipher? = null

    // engine leg
    @Volatile private var localKey: ByteArray? = null

    @Volatile private var localCandidates: List<PacketCipher> = emptyList()

    @Volatile private var localIn: PacketCipher? = null

    @Volatile private var localLocked = false

    @Volatile private var localOut: PacketCipher? = null

    /** Opus payload type from SELECT_PROTOCOL; everything else (video, RTX) isn't relayed. */
    @Volatile var opusPayloadType = 120

    // IP discovery against the media server
    @Volatile var externalAddress: String? = null
        private set

    @Volatile var externalPort = 0
        private set

    private val discovered = CountDownLatch(1)

    @Volatile private var lastServerSend = 0L

    // stats
    @Volatile var engineIn = 0L
    @Volatile var engineUnreadable = 0L
    @Volatile var sentToServer = 0L
    @Volatile var serverInCount = 0L
    @Volatile var serverUnreadable = 0L
    @Volatile var deliveredToEngine = 0L
    @Volatile var droppedNoKey = 0L
    @Volatile var droppedOther = 0L
    @Volatile var otherPayloadTypes = ""

    // video (VP8 only, see VoiceGateway.selectProtocol)
    @Volatile var videoPayloadType = -1

    @Volatile var videoRtxPayloadType = -1

    /** RTX SSRC -> the media SSRC it retransmits, ours and other users'. */
    val rtxToMedia = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val outgoingVideo = OutgoingVideo(keys) { plain ->
        serverOut?.let {
            sendServer(it.seal(plain))
            videoSent++
        }
    }

    private val incomingVideo = IncomingVideo(keys) { plain ->
        val to = engine
        val out = localOut
        if (to != null && out != null) {
            sendEngine(out.seal(plain), to)
            videoDelivered++
        }
    }

    @Volatile var videoSent = 0L
    @Volatile var videoDelivered = 0L

    val videoStats: String
        get() = "out ${outgoingVideo.frames} frames ($videoSent packets, ${outgoingVideo.dropped} dropped), " +
            "in ${incomingVideo.decoded} frames ($videoDelivered packets, ${incomingVideo.failed} undecryptable)"

    val localLayout: String
        get() = localIn?.let { "${if (it.rtpsizeHeader) "rtpsize" else "fixed"} header${if (localLocked) "" else " (assumed)"}" }
            ?: "not seen yet"

    val serverMode: String get() = serverOut?.mode ?: "none"

    init {
        thread("engine") { engineLoop() }
        thread("server") { serverLoop() }
        thread("discovery") { discoveryLoop() }
        thread("keepalive") { keepAliveLoop() }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread({
            try {
                body()
            } catch (e: Throwable) {
                if (running) log("relay $name stopped: $e")
            }
        }, "DaveVoice-$name").apply {
            isDaemon = true
            start()
        }
    }

    fun setServerKey(mode: String, key: ByteArray) {
        serverOut = PacketCipher.server(mode, key)
        serverIn = PacketCipher.server(mode, key)
        if (serverOut == null) log("unsupported server mode $mode")
    }

    fun setLocalKey(key: ByteArray) {
        localKey = key
        localCandidates = PacketCipher.localVariants(key)
        localIn = null
        localLocked = false
        localOut = PacketCipher.localVariants(key)[0]
    }

    /** Blocks until IP discovery with the media server finished (or [timeoutMs] passed). */
    fun awaitDiscovery(timeoutMs: Long): Boolean = discovered.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun close() {
        running = false
        try {
            local.close()
        } catch (e: Throwable) {
        }
        try {
            remote.close()
        } catch (e: Throwable) {
        }
    }

    // region IP discovery

    private fun discoveryLoop() {
        val req = ByteArray(74)
        Bytes.putU16(req, 0, 1)
        Bytes.putU16(req, 2, 70)
        Bytes.putU32(req, 4, ssrc.toLong() and 0xFFFFFFFFL)
        var attempt = 0
        while (running && discovered.count > 0 && attempt < 20) {
            sendServer(req)
            attempt++
            discovered.await(500, TimeUnit.MILLISECONDS)
        }
        if (discovered.count > 0 && running) log("IP discovery got no answer from the media server")
    }

    private fun handleDiscoveryResponse(p: ByteArray, len: Int) {
        if (len < 74 || discovered.count == 0L) return
        val sb = StringBuilder()
        var i = 8
        while (i < 72 && p[i].toInt() != 0) sb.append((p[i++].toInt() and 0xFF).toChar())
        externalAddress = sb.toString()
        externalPort = Bytes.u16(p, 72)
        log("external address $externalAddress:$externalPort")
        discovered.countDown()
    }

    /** The engine's own IP discovery goes to us: answer with something plausible; Java replaces it anyway. */
    private fun answerEngineDiscovery(p: ByteArray, len: Int, from: SocketAddress) {
        val port = (from as? InetSocketAddress)?.port ?: 0
        val addr = "127.0.0.1".toByteArray()
        if (len == 74 && Bytes.u16(p, 0) == 1) {
            val r = ByteArray(74)
            Bytes.putU16(r, 0, 2)
            Bytes.putU16(r, 2, 70)
            System.arraycopy(p, 4, r, 4, 4)
            System.arraycopy(addr, 0, r, 8, addr.size)
            Bytes.putU16(r, 72, port)
            sendEngine(r, from)
        } else {
            // legacy 70 byte form: ssrc, address, little-endian port
            val r = ByteArray(70)
            System.arraycopy(p, 0, r, 0, 4)
            System.arraycopy(addr, 0, r, 4, addr.size)
            r[68] = port.toByte()
            r[69] = (port ushr 8).toByte()
            sendEngine(r, from)
        }
    }

    // endregion

    // region engine -> server

    private fun engineLoop() {
        val buf = ByteArray(65536)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            pkt.setLength(buf.size)
            local.receive(pkt)
            val len = pkt.length
            val from = pkt.socketAddress
            // legacy 70 byte discovery starts with the SSRC, which can look like RTP: before keys it's discovery
            if (!Rtp.isRtpOrRtcp(buf, len) || (localKey == null && len == 70)) {
                if ((len == 74 && Bytes.u16(buf, 0) == 1) || len == 70) {
                    engine = from
                    answerEngineDiscovery(buf.copyOf(len), len, from)
                } else {
                    // e.g. the engine's own keep-alives
                    engine = from
                    sendServer(buf.copyOf(len))
                }
                continue
            }
            engine = from
            engineIn++
            val p = buf.copyOf(len)
            val plain = openLocal(p, len)
            if (plain == null) {
                engineUnreadable++
                if (engineUnreadable <= 5) log("unreadable engine packet (${len}b): ${Bytes.hex(p, 24)}")
                continue
            }
            val out = serverOut ?: continue
            if (Rtp.isRtcp(plain)) {
                sendServer(out.seal(plain))
                continue
            }
            val pt = Rtp.payloadType(plain)
            if (pt != opusPayloadType) {
                val parts = Rtp.parse(plain)
                when {
                    parts == null -> droppedOther++
                    pt == videoPayloadType -> outgoingVideo.onPacket(parts)
                    pt == videoRtxPayloadType -> outgoingVideo.onRtx(parts, rtxToMedia[Rtp.ssrc(plain)])
                    else -> {
                        droppedOther++
                        notePayloadType(pt)
                    }
                }
                continue
            }
            val parts = Rtp.parse(plain) ?: continue
            val frame = keys.encryptor.encrypt(parts.payload())
            if (frame == null) {
                droppedNoKey++
                continue
            }
            sendServer(out.seal(parts.rebuild(frame, keepExtension = true)))
            sentToServer++
        }
    }

    private fun openLocal(p: ByteArray, len: Int): ByteArray? {
        val known = localIn
        if (known != null) {
            known.open(p, len)?.let { return it }
            if (localLocked) return null
        }
        for (c in localCandidates) {
            if (c === known) continue
            val plain = c.open(p, len) ?: continue
            if (localIn !== c) {
                localIn = c
                val key = localKey
                if (key != null) {
                    val prev = localOut
                    localOut = PacketCipher(c.mode, SecretBox(key), 24, c.rtpsizeHeader, noncePosOf(c)).also {
                        it.counter = prev?.counter ?: 0
                    }
                }
            }
            // The layouts only differ when the header extension is present; only then is a match conclusive.
            if (!Rtp.isRtcp(plain) && Rtp.hasExtension(plain) && !localLocked) {
                localLocked = true
                log("engine transport layout: $localLayout")
            }
            return plain
        }
        return null
    }

    private fun noncePosOf(c: PacketCipher) = c.noncePos

    private val seenPayloadTypes = java.util.Collections.synchronizedSet(HashSet<Int>())

    // no String.split: it goes through Discord's stripped kotlin.ranges
    private fun notePayloadType(pt: Int) {
        if (!seenPayloadTypes.add(pt)) return
        otherPayloadTypes = if (otherPayloadTypes.isEmpty()) pt.toString() else "$otherPayloadTypes,$pt"
    }

    // endregion

    // region server -> engine

    private fun serverLoop() {
        val buf = ByteArray(65536)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            pkt.setLength(buf.size)
            remote.receive(pkt)
            val len = pkt.length
            if (len == 74 && Bytes.u16(buf, 0) == 2) {
                handleDiscoveryResponse(buf, len)
                continue
            }
            if (!Rtp.isRtpOrRtcp(buf, len)) continue
            serverInCount++
            val cipher = serverIn ?: continue
            val p = buf.copyOf(len)
            val plain = cipher.open(p, len)
            if (plain == null) {
                serverUnreadable++
                continue
            }
            val to = engine ?: continue
            val out = localOut ?: continue
            if (Rtp.isRtcp(plain)) {
                sendEngine(out.seal(plain), to)
                continue
            }
            val pt = Rtp.payloadType(plain)
            if (pt != opusPayloadType) {
                val parts = Rtp.parse(plain)
                // The engine's own packets tell us whether it reads header extensions in the clear (see openLocal);
                // until then they're dropped from video too.
                incomingVideo.keepExtension = localLocked
                when {
                    parts == null -> droppedOther++
                    pt == videoPayloadType -> incomingVideo.onPacket(parts)
                    pt == videoRtxPayloadType ->
                        incomingVideo.onRtx(parts, rtxToMedia[Rtp.ssrc(plain)], videoPayloadType)
                    else -> droppedOther++
                }
                continue
            }
            val parts = Rtp.parse(plain) ?: continue
            val frame = keys.decrypt(Rtp.ssrc(plain), parts.payload()) ?: continue
            // The header extension is dropped: the engine doesn't need it, and that way its layout doesn't matter.
            sendEngine(out.seal(parts.rebuild(frame, keepExtension = false)), to)
            deliveredToEngine++
        }
    }

    // endregion

    private fun keepAliveLoop() {
        var counter = 0L
        while (running) {
            Thread.sleep(1000)
            if (externalAddress == null) continue
            if (SystemClock.elapsedRealtime() - lastServerSend < 5000) continue
            val ka = ByteArray(8)
            for (i in 0 until 4) ka[i] = (counter ushr (8 * i)).toByte()
            counter = (counter + 1) and 0xFFFFFFFFL
            sendServer(ka)
        }
    }

    private fun sendServer(b: ByteArray) {
        try {
            remote.send(DatagramPacket(b, b.size, server))
            lastServerSend = SystemClock.elapsedRealtime()
        } catch (e: Throwable) {
            if (running) log("send to server failed: $e")
        }
    }

    private fun sendEngine(b: ByteArray, to: SocketAddress) {
        try {
            local.send(DatagramPacket(b, b.size, to))
        } catch (e: Throwable) {
            if (running) log("send to engine failed: $e")
        }
    }
}
