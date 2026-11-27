package com.github.yutaplug.fixes.voice

import android.os.SystemClock

/** VP8 RTP payload format (RFC 7741). */
internal object Vp8 {
    /** Length of the VP8 payload descriptor at [off], or -1 when malformed. */
    fun descriptorLen(p: ByteArray, off: Int, end: Int): Int {
        if (off >= end) return -1
        var i = off
        val b0 = Bytes.u8(p, i++)
        if (b0 and 0x80 != 0) {
            if (i >= end) return -1
            val x = Bytes.u8(p, i++)
            if (x and 0x80 != 0) { // PictureID
                if (i >= end) return -1
                if (Bytes.u8(p, i++) and 0x80 != 0) i++
            }
            if (x and 0x40 != 0) i++ // TL0PICIDX
            if (x and 0x30 != 0) i++ // TID / KEYIDX
        }
        return if (i <= end) i - off else -1
    }

    /** S bit set and partition index 0: the first packet of a frame. */
    fun isFrameStart(p: ByteArray, off: Int) = Bytes.u8(p, off) and 0x17 == 0x10

    /** DAVE leaves the VP8 payload header readable: 10 bytes on key frames (P bit 0), else 1. */
    fun clearBytes(frame: ByteArray) = when {
        frame.isEmpty() -> 0
        frame[0].toInt() and 0x01 == 0 -> minOf(10, frame.size)
        else -> 1
    }
}

/** One RTP packet of a VP8 frame: [parts] of the decrypted packet, descriptor length, its sequence number. */
internal class VideoPacket(val parts: Rtp.Parts, val descLen: Int) {
    val seq get() = Bytes.u16(parts.packet, 2)
    val dataOff get() = parts.payloadOff + descLen
    val dataLen get() = parts.payloadEnd - dataOff

    /** This packet's header and descriptor around new frame bytes. */
    fun with(data: ByteArray, off: Int, len: Int, keepExtension: Boolean): ByteArray {
        val payload = ByteArray(descLen + len)
        System.arraycopy(parts.packet, parts.payloadOff, payload, 0, descLen)
        System.arraycopy(data, off, payload, descLen, len)
        return parts.rebuild(payload, keepExtension)
    }

    companion object {
        fun of(parts: Rtp.Parts): VideoPacket? {
            val d = Vp8.descriptorLen(parts.packet, parts.payloadOff, parts.payloadEnd)
            return if (d < 0) null else VideoPacket(parts, d)
        }

        fun concat(packets: List<VideoPacket>): ByteArray {
            var n = 0
            for (p in packets) n += p.dataLen
            val out = ByteArray(n)
            var o = 0
            for (p in packets) {
                System.arraycopy(p.parts.packet, p.dataOff, out, o, p.dataLen)
                o += p.dataLen
            }
            return out
        }

        /**
         * Splits [frame] over [packets] keeping their sizes, the last packet absorbing the difference (the DAVE
         * footer when sending; taking it away when receiving, with every packet keeping at least one byte so no
         * sequence number goes missing). Null when it can't be split that way.
         */
        fun split(packets: List<VideoPacket>, frame: ByteArray): IntArray? {
            val n = packets.size
            val lens = IntArray(n)
            var total = 0
            for (i in 0 until n) {
                lens[i] = packets[i].dataLen
                total += lens[i]
            }
            var diff = frame.size - total
            if (diff >= 0) {
                lens[n - 1] += diff
                return lens
            }
            var i = n - 1
            while (diff < 0 && i >= 0) {
                val take = minOf(-diff, lens[i] - 1)
                if (take > 0) {
                    lens[i] -= take
                    diff += take
                }
                i--
            }
            return if (diff == 0) lens else null
        }
    }
}

/** Our engine's outgoing VP8: whole frames get DAVE-encrypted, then go out in the same packets. Engine thread only. */
internal class OutgoingVideo(private val keys: MediaKeys, private val send: (ByteArray) -> Unit) {
    private class Pending(val timestamp: Long) {
        val packets = ArrayList<VideoPacket>()
    }

    private val pending = HashMap<Int, Pending>()

    /** ssrc -> (seq -> payload as sent), so retransmissions resend exactly what was sent. */
    private val sent = HashMap<Int, LinkedHashMap<Int, ByteArray>>()

    var frames = 0L
        private set
    var dropped = 0L
        private set

    fun onPacket(parts: Rtp.Parts) {
        val p = parts.packet
        if (parts.payloadOff == parts.payloadEnd) {
            send(p) // padding only (bandwidth probing)
            return
        }
        val packet = VideoPacket.of(parts) ?: return
        val ssrc = Rtp.ssrc(p)
        val ts = Bytes.u32(p, 4)
        var cur = pending[ssrc]
        if (cur != null && cur.timestamp != ts) {
            dropped++ // the previous frame never finished
            pending.remove(ssrc)
            cur = null
        }
        if (cur == null) {
            if (!Vp8.isFrameStart(p, parts.payloadOff)) {
                dropped++
                return
            }
            cur = Pending(ts)
            pending[ssrc] = cur
        }
        cur.packets.add(packet)
        if (p[1].toInt() and 0x80 != 0) { // marker: last packet of the frame
            pending.remove(ssrc)
            finish(ssrc, cur.packets)
        }
    }

    private fun finish(ssrc: Int, packets: List<VideoPacket>) {
        val frame = VideoPacket.concat(packets)
        val enc = keys.encryptor.encrypt(frame, Vp8.clearBytes(frame))
        val lens = if (enc == null) null else VideoPacket.split(packets, enc)
        if (enc == null || lens == null) {
            dropped++
            return
        }
        val cache = sent.getOrPut(ssrc) { LinkedHashMap() }
        var off = 0
        for (i in 0 until packets.size) {
            val out = packets[i].with(enc, off, lens[i], keepExtension = true)
            off += lens[i]
            val parsed = Rtp.parse(out)
            if (parsed != null) cache[packets[i].seq] = out.copyOfRange(parsed.payloadOff, parsed.payloadEnd)
            send(out)
        }
        while (cache.size > RTX_CACHE) cache.remove(cache.keys.iterator().next())
        frames++
    }

    /** An RTX retransmission from the engine (OSN + original payload): resend what we sent for that packet. */
    fun onRtx(parts: Rtp.Parts, mediaSsrc: Int?) {
        val p = parts.packet
        if (parts.payloadEnd - parts.payloadOff < 2) {
            send(p) // RTX padding
            return
        }
        val osn = Bytes.u16(p, parts.payloadOff)
        var payload = mediaSsrc?.let { sent[it]?.get(osn) }
        if (payload == null) for (c in sent.values) payload = payload ?: c[osn]
        if (payload == null) {
            dropped++
            return
        }
        val np = ByteArray(2 + payload.size)
        Bytes.putU16(np, 0, osn)
        System.arraycopy(payload, 0, np, 2, payload.size)
        send(parts.rebuild(np, keepExtension = true))
    }

    private companion object {
        const val RTX_CACHE = 2048
    }
}

/** Incoming VP8 from the media server: frames are collected, DAVE-decrypted and handed on whole. Server thread only. */
internal class IncomingVideo(private val keys: MediaKeys, private val deliver: (ByteArray) -> Unit) {
    private class Frame(val created: Long) {
        val packets = HashMap<Int, VideoPacket>()
        var start = -1
        var end = -1
    }

    private val frames = HashMap<Int, LinkedHashMap<Long, Frame>>()

    var decoded = 0L
        private set
    var failed = 0L
        private set

    @Volatile var keepExtension = false

    fun onPacket(parts: Rtp.Parts) {
        val p = parts.packet
        if (parts.payloadOff == parts.payloadEnd) {
            deliver(parts.rebuild(ByteArray(0), keepExtension))
            return
        }
        val packet = VideoPacket.of(parts) ?: return
        val ssrc = Rtp.ssrc(p)
        val ts = Bytes.u32(p, 4)
        val now = SystemClock.elapsedRealtime()
        val byTs = frames.getOrPut(ssrc) { LinkedHashMap() }
        expire(byTs, now)
        val f = byTs.getOrPut(ts) { Frame(now) }
        val seq = packet.seq
        if (f.packets.containsKey(seq)) return
        f.packets[seq] = packet
        if (Vp8.isFrameStart(p, parts.payloadOff)) f.start = seq
        if (p[1].toInt() and 0x80 != 0) f.end = seq
        if (f.start < 0 || f.end < 0) return
        val count = ((f.end - f.start) and 0xFFFF) + 1
        if (f.packets.size < count) return
        byTs.remove(ts)
        val ordered = ArrayList<VideoPacket>(count)
        for (k in 0 until count) ordered.add(f.packets[(f.start + k) and 0xFFFF] ?: return)
        complete(ssrc, ordered)
    }

    /** An RTX retransmission from the server, turned back into the original packet. */
    fun onRtx(parts: Rtp.Parts, mediaSsrc: Int?, mediaPayloadType: Int) {
        val p = parts.packet
        if (mediaSsrc == null || parts.payloadEnd - parts.payloadOff < 3) return
        val osn = Bytes.u16(p, parts.payloadOff)
        val payload = p.copyOfRange(parts.payloadOff + 2, parts.payloadEnd)
        val orig = parts.rebuild(payload, keepExtension = true)
        orig[1] = ((orig[1].toInt() and 0x80) or mediaPayloadType).toByte()
        Bytes.putU16(orig, 2, osn)
        Bytes.putU32(orig, 8, mediaSsrc.toLong() and 0xFFFFFFFFL)
        Rtp.parse(orig)?.let { onPacket(it) }
    }

    private fun complete(ssrc: Int, packets: List<VideoPacket>) {
        val frame = VideoPacket.concat(packets)
        val plain = keys.decrypt(ssrc, frame)
        val lens = if (plain == null) null else VideoPacket.split(packets, plain)
        if (plain == null || lens == null) {
            failed++
            return
        }
        var off = 0
        for (i in 0 until packets.size) {
            deliver(packets[i].with(plain, off, lens[i], keepExtension))
            off += lens[i]
        }
        decoded++
    }

    private fun expire(byTs: LinkedHashMap<Long, Frame>, now: Long) {
        val it = byTs.values.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (now - f.created > 3000 || byTs.size > 120) it.remove() else break
        }
    }
}
