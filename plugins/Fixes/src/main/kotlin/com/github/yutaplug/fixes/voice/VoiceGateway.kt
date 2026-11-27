package com.github.yutaplug.fixes.voice

import com.aliucord.Logger
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * State of one Discord 126.21 voice gateway socket (b.a.q.n0.a, "RtcControlSocket"): speaks voice gateway v8 to
 * Discord while the app's own code still sees v5, runs DAVE, and points the voice engine at a [Relay].
 */
internal class VoiceGateway(private val socket: b.a.q.n0.a) : DaveSession.Output {
    @Volatile private var lastSeq = -1

    private var selfUserId = 0L
    private var channelId = 0L

    private var keys: MediaKeys? = null
    private var relay: Relay? = null
    private var dave: DaveSession? = null

    private var serverModes: List<String> = emptyList()
    private var serverMode: String? = null
    private var videoCodec: String? = null
    private val localKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private val startedAt = System.currentTimeMillis()

    fun log(msg: String) = logger.info(msg)

    private fun ensureIds() {
        if (selfUserId != 0L && channelId != 0L) return
        try {
            for (l in socket.q) {
                if (l is b.a.q.h0) {
                    val rtc = l.a
                    channelId = rtc.P
                    selfUserId = rtc.T
                }
            }
        } catch (e: Throwable) {
            log("couldn't read the call's channel/user: $e")
        }
    }

    // region incoming

    /** Rewrites a v8 text message for the app's v5 code; null drops it. */
    @Synchronized
    fun onText(text: String): String? {
        val o = try {
            JSONObject(text)
        } catch (e: Throwable) {
            return text
        }
        if (o.has("seq") && !o.isNull("seq")) lastSeq = o.optInt("seq", lastSeq)
        val op = o.optInt("op", -1)
        val d = o.optJSONObject("d")
        try {
            when (op) {
                OP_READY -> if (d != null) onReady(d)
                OP_SESSION_DESCRIPTION -> if (d != null) onDescription(d)
                OP_SPEAKING -> if (d != null) {
                    val uid = jLong(d, "user_id")
                    if (uid != 0L) keys?.mapSsrc(d.optInt("ssrc"), uid)
                }
                OP_HEARTBEAT_ACK -> if (d != null) o.put("d", d.opt("t"))
                OP_HELLO -> if (d != null) d.put("heartbeat_interval", d.optDouble("heartbeat_interval", 13750.0).toLong())
                OP_VIDEO -> if (d != null) {
                    val uid = jLong(d, "user_id")
                    if (uid != 0L) {
                        keys?.mapSsrc(d.optInt("audio_ssrc"), uid)
                        keys?.mapSsrc(d.optInt("video_ssrc"), uid)
                    }
                    mapRtx(d.optInt("video_ssrc"), d.optInt("rtx_ssrc"))
                    d.optJSONArray("streams")?.let { streams ->
                        for (i in 0 until streams.length()) {
                            val s = streams.optJSONObject(i) ?: continue
                            if (uid != 0L) keys?.mapSsrc(s.optInt("ssrc"), uid)
                            mapRtx(s.optInt("ssrc"), s.optInt("rtx_ssrc"))
                        }
                    }
                }
                OP_CLIENTS_CONNECT -> {
                    val ids = d?.optJSONArray("user_ids")
                    val list = ArrayList<Long>()
                    if (ids != null) for (i in 0 until ids.length()) parseLong(ids.opt(i))?.let { list.add(it) }
                    dave?.onClientsConnect(list)
                    return null
                }
                OP_CLIENT_DISCONNECT -> if (d != null) {
                    val uid = jLong(d, "user_id")
                    dave?.onClientDisconnect(uid)
                    keys?.removeUser(uid)
                }
                DaveSession.OP_PREPARE_TRANSITION -> {
                    if (d != null) dave?.onPrepareTransition(d.optInt("transition_id"), d.optInt("protocol_version"))
                    return null
                }
                DaveSession.OP_EXECUTE_TRANSITION -> {
                    if (d != null) dave?.onExecuteTransition(d.optInt("transition_id"))
                    return null
                }
                DaveSession.OP_PREPARE_EPOCH -> {
                    if (d != null) dave?.onPrepareEpoch(d.optLong("epoch"), d.optInt("protocol_version"))
                    return null
                }
                else -> if (op >= 16) return null // v8-only events the app doesn't know
            }
        } catch (e: Throwable) {
            log("handling op $op failed: $e")
            logger.error(e)
        }
        return o.toString()
    }

    private fun onReady(d: JSONObject) {
        ensureIds()
        val ip = d.getString("ip")
        val port = d.getInt("port")
        val ssrc = d.getInt("ssrc")
        val modes = ArrayList<String>()
        d.optJSONArray("modes")?.let { for (i in 0 until it.length()) modes.add(it.optString(i)) }
        serverModes = modes
        serverMode = PacketCipher.SERVER_MODES.firstOrNull { modes.contains(it) }
        if (serverMode == null) log("no supported encryption mode in $modes")

        relay?.close()
        keys?.clear()
        val k = MediaKeys(selfUserId)
        keys = k
        dave = DaveSession(selfUserId, channelId, k, this) { log(it) }
        val r = Relay(ip, port, ssrc, k) { log(it) }
        relay = r
        // our own video streams and their RTX SSRCs
        d.optJSONArray("streams")?.let { streams ->
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                mapRtx(s.optInt("ssrc"), s.optInt("rtx_ssrc"))
            }
        }
        log("READY: media server $ip:$port, ssrc $ssrc, modes $modes, relay on 127.0.0.1:${r.localPort}")
        d.put("ip", "127.0.0.1")
        d.put("port", r.localPort)
    }

    private fun mapRtx(media: Int, rtx: Int) {
        if (media != 0 && rtx != 0) relay?.rtxToMedia?.put(rtx, media)
    }

    private fun onDescription(d: JSONObject) {
        val mode = d.optString("mode")
        val arr = d.optJSONArray("secret_key")
        val key = ByteArray(arr?.length() ?: 0)
        for (i in key.indices) key[i] = arr!!.optInt(i).toByte()
        val r = relay
        if (r != null) {
            r.setServerKey(mode, key)
            r.setLocalKey(localKey)
        }
        val version = d.optInt("dave_protocol_version", 0)
        videoCodec = if (d.isNull("video_codec")) null else d.optString("video_codec")
        log("session description: mode $mode, video ${videoCodec ?: "none"}, DAVE version $version")
        d.put("mode", PacketCipher.XSALSA20_LITE)
        val local = JSONArray()
        for (b in localKey) local.put(b.toInt() and 0xFF)
        d.put("secret_key", local)
        keys?.encryptor?.passthrough = version == 0
        keys?.passthroughDefault = version == 0
        dave?.onSelectProtocolAck(version)
    }

    @Synchronized
    fun onBinary(bytes: ByteArray) {
        if (bytes.size < 3) return
        lastSeq = Bytes.u16(bytes, 0)
        val op = Bytes.u8(bytes, 2)
        val payload = bytes.copyOfRange(3, bytes.size)
        val s = dave ?: return
        try {
            when (op) {
                DaveSession.OP_MLS_EXTERNAL_SENDER -> s.onExternalSender(payload)
                DaveSession.OP_MLS_PROPOSALS -> s.onProposals(payload)
                DaveSession.OP_MLS_ANNOUNCE_COMMIT ->
                    s.onAnnounceCommit(Bytes.u16(payload, 0), payload.copyOfRange(2, payload.size))
                DaveSession.OP_MLS_WELCOME -> s.onWelcome(Bytes.u16(payload, 0), payload.copyOfRange(2, payload.size))
                else -> log("unknown binary op $op")
            }
        } catch (e: Throwable) {
            log("binary op $op failed: $e")
            logger.error(e)
        }
    }

    // endregion

    // region outgoing

    /** Sends [op] ourselves in v8 form; false lets the app send it unchanged. */
    fun outgoing(op: Int, payload: Any?): Boolean {
        val d: Any = when (op) {
            OP_IDENTIFY -> toJson(payload).also {
                it.put("max_dave_protocol_version", MAX_DAVE_VERSION)
                ensureIds()
                if (selfUserId == 0L) selfUserId = jLong(it, "user_id")
                if (channelId != 0L && !it.has("channel_id")) it.put("channel_id", channelId.toString())
            }
            OP_SELECT_PROTOCOL -> selectProtocol(toJson(payload))
            OP_HEARTBEAT -> JSONObject().put("t", parseLong(payload) ?: System.currentTimeMillis()).put("seq_ack", lastSeq)
            OP_RESUME -> toJson(payload).put("seq_ack", lastSeq)
            else -> return false
        }
        sendJson(op, d)
        return true
    }

    private fun selectProtocol(json: JSONObject): JSONObject {
        val r = relay
        val data = json.optJSONObject("data") ?: JSONObject().also { json.put("data", it) }
        if (r != null) {
            if (!r.awaitDiscovery(5000)) log("sending SELECT_PROTOCOL without a discovered address")
            r.externalAddress?.let { data.put("address", it) }
            if (r.externalPort != 0) data.put("port", r.externalPort)
        }
        serverMode?.let { data.put("mode", it) }
        json.optJSONArray("codecs")?.let { codecs ->
            // Only VP8 video: its DAVE handling is fixed (1 or 10 clear bytes); H264's needs NAL unit rewriting.
            val kept = JSONArray()
            for (i in 0 until codecs.length()) {
                val c = codecs.optJSONObject(i) ?: continue
                val name = c.optString("name")
                when {
                    name == "opus" -> {
                        r?.opusPayloadType = c.optInt("payload_type", 120)
                        kept.put(c)
                    }
                    name == "VP8" -> { // the engine upper-cases its codec names
                        r?.videoPayloadType = c.optInt("payload_type", -1)
                        r?.videoRtxPayloadType = if (c.isNull("rtx_payload_type")) -1 else c.optInt("rtx_payload_type", -1)
                        c.put("priority", 1)
                        kept.put(c)
                    }
                    c.optString("type") != "video" -> kept.put(c)
                }
            }
            json.put("codecs", kept)
        }
        return json
    }

    private fun toJson(payload: Any?): JSONObject = JSONObject(socket.n.m(payload))

    private fun sendJson(op: Int, d: Any) {
        val text = JSONObject().put("op", op).put("d", d).toString()
        val ws = socket.s ?: run {
            log("can't send op $op: no websocket")
            return
        }
        socket.h("sending (v8 op $op): $text")
        ws.a(text)
    }

    override fun sendJson(op: Int, data: JSONObject) = sendJson(op, data as Any)

    /** okhttp's send(ByteString) was stripped from Discord's build, so queue the binary frame like it would. */
    override fun sendBinary(op: Int, payload: ByteArray) {
        val ws = socket.s as? f0.e0.n.d ?: run {
            log("can't send binary op $op: no websocket")
            return
        }
        val bytes = ByteArray(payload.size + 1)
        bytes[0] = op.toByte()
        System.arraycopy(payload, 0, bytes, 1, payload.size)
        synchronized(ws) {
            if (ws.p || ws.m) return
            ws.l += bytes.size.toLong()
            ws.k.add(f0.e0.n.d.b(2, ByteString(bytes)))
            ws.l()
        }
    }

    // endregion

    fun close() {
        relay?.close()
        relay = null
        keys?.clear()
        keys = null
        dave = null
    }

    fun status(): String {
        val r = relay
        val k = keys
        val s = dave
        val sb = StringBuilder()
        sb.append("Channel ").append(channelId).append(", up ").append((System.currentTimeMillis() - startedAt) / 1000).append("s\n")
        sb.append("Server modes: ").append(serverModes).append(" -> ").append(serverMode ?: "none").append('\n')
        if (s != null) {
            sb.append("DAVE v").append(s.protocolVersion).append(", group ")
                .append(if (s.established) "established (epoch ${s.epoch})" else "not established").append('\n')
            s.privacyCode?.let { sb.append("Privacy code: ").append(it).append('\n') }
            s.lastError?.let { sb.append("Last error: ").append(it).append('\n') }
        }
        if (r != null) {
            sb.append("External address: ").append(r.externalAddress ?: "?").append(':').append(r.externalPort).append('\n')
            sb.append("Engine layout: ").append(r.localLayout).append('\n')
            sb.append("Sent: ").append(r.sentToServer).append(" of ").append(r.engineIn).append(" engine packets")
                .append(" (unreadable ").append(r.engineUnreadable).append(", no key ").append(r.droppedNoKey)
                .append(", not audio ").append(r.droppedOther).append(")\n")
            if (r.otherPayloadTypes.isNotEmpty()) sb.append("Other payload types: ").append(r.otherPayloadTypes).append('\n')
            sb.append("Received: ").append(r.deliveredToEngine).append(" delivered of ").append(r.serverInCount)
                .append(" (unreadable ").append(r.serverUnreadable).append(")\n")
            sb.append("Video codec: ").append(videoCodec ?: "?").append(", ").append(r.videoStats).append('\n')
        }
        if (k != null) {
            sb.append("Frames: ").append(k.encryptor.encrypted).append(" encrypted, ").append(k.decrypted)
                .append(" decrypted, ").append(k.decryptFailed).append(" failed, ").append(k.droppedPlain)
                .append(" unencrypted dropped")
        }
        return sb.toString()
    }

    companion object {
        val logger = Logger("Fixes/DaveVoice")

        const val MAX_DAVE_VERSION = 1

        const val OP_IDENTIFY = 0
        const val OP_SELECT_PROTOCOL = 1
        const val OP_READY = 2
        const val OP_HEARTBEAT = 3
        const val OP_SESSION_DESCRIPTION = 4
        const val OP_SPEAKING = 5
        const val OP_HEARTBEAT_ACK = 6
        const val OP_RESUME = 7
        const val OP_HELLO = 8
        const val OP_CLIENTS_CONNECT = 11
        const val OP_VIDEO = 12
        const val OP_CLIENT_DISCONNECT = 13

        fun parseLong(v: Any?): Long? = when (v) {
            is Number -> v.toLong()
            is String -> try {
                java.lang.Long.parseLong(v)
            } catch (e: NumberFormatException) {
                null
            }
            else -> null
        }

        fun jLong(o: JSONObject, key: String) = parseLong(o.opt(key)) ?: 0L
    }
}
