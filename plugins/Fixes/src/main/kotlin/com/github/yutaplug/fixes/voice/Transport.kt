package com.github.yutaplug.fixes.voice

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/** RTP / RTCP packet layout helpers. */
internal object Rtp {
    fun isRtpOrRtcp(p: ByteArray, len: Int) = len >= 8 && (p[0].toInt() and 0xC0) == 0x80

    /** RTCP packet types (192..223) sit where RTP has the marker bit and payload type (RFC 5761). */
    fun isRtcp(p: ByteArray) = Bytes.u8(p, 1) in 192..223

    fun payloadType(p: ByteArray) = p[1].toInt() and 0x7F

    fun ssrc(p: ByteArray) = Bytes.u32(p, 8).toInt()

    fun hasExtension(p: ByteArray) = p[0].toInt() and 0x10 != 0

    private fun csrcCount(p: ByteArray) = p[0].toInt() and 0x0F

    /** Fixed header plus CSRCs. */
    fun baseHeaderLen(p: ByteArray) = 12 + 4 * csrcCount(p)

    /**
     * Bytes left in the clear by transport encryption. "rtpsize" modes also leave the 4 byte header extension header
     * (profile + length) clear; the extension body is encrypted with the payload. RTCP always keeps its 8 byte header.
     */
    fun clearLen(p: ByteArray, len: Int, rtpsize: Boolean): Int {
        if (isRtcp(p)) return 8
        val n = baseHeaderLen(p) + if (rtpsize && hasExtension(p)) 4 else 0
        return if (n <= len) n else -1
    }

    /** A decrypted RTP packet split into header, header extension and payload (padding removed). */
    class Parts(val packet: ByteArray, val headerLen: Int, val extLen: Int, val payloadOff: Int, val payloadEnd: Int) {
        fun payload(): ByteArray = packet.copyOfRange(payloadOff, payloadEnd)

        /** Same header (padding flag cleared), optionally without the header extension, around [newPayload]. */
        fun rebuild(newPayload: ByteArray, keepExtension: Boolean): ByteArray {
            val ext = if (keepExtension) extLen else 0
            val out = ByteArray(headerLen + ext + newPayload.size)
            System.arraycopy(packet, 0, out, 0, headerLen + ext)
            System.arraycopy(newPayload, 0, out, headerLen + ext, newPayload.size)
            var flags = out[0].toInt() and 0xDF // no padding
            if (!keepExtension) flags = flags and 0xEF // no extension
            out[0] = flags.toByte()
            return out
        }
    }

    fun parse(p: ByteArray): Parts? {
        val hdr = baseHeaderLen(p)
        if (p.size < hdr) return null
        var ext = 0
        if (hasExtension(p)) {
            if (p.size < hdr + 4) return null
            ext = 4 + 4 * Bytes.u16(p, hdr + 2)
            if (p.size < hdr + ext) return null
        }
        var end = p.size
        if (p[0].toInt() and 0x20 != 0 && end > hdr + ext) {
            val pad = Bytes.u8(p, end - 1)
            if (pad == 0 || end - pad < hdr + ext) return null
            end -= pad
        }
        return Parts(p, hdr, ext, hdr + ext, end)
    }
}

/** An AEAD keyed for one transport leg. Not thread safe. */
internal abstract class TransportAead {
    /** Encrypts; returns the ciphertext with its tag (where the mode puts it). */
    abstract fun seal(nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray

    /** Decrypts and authenticates; null when the packet doesn't authenticate. */
    abstract fun open(nonce: ByteArray, aad: ByteArray, ct: ByteArray, off: Int, len: Int): ByteArray?

    abstract val overhead: Int
}

/** aead_aes256_gcm_rtpsize: AES-256-GCM, 12 byte nonce. */
internal class AesGcm(private val key: ByteArray) : TransportAead() {
    private val gcm = GCMBlockCipher.newInstance(AESEngine.newInstance())
    override val overhead = 16

    override fun seal(nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        gcm.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(gcm.getOutputSize(pt.size))
        val n = gcm.processBytes(pt, 0, pt.size, out, 0)
        gcm.doFinal(out, n)
        return out
    }

    override fun open(nonce: ByteArray, aad: ByteArray, ct: ByteArray, off: Int, len: Int): ByteArray? = try {
        gcm.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(gcm.getOutputSize(len))
        val n = gcm.processBytes(ct, off, len, out, 0)
        gcm.doFinal(out, n)
        out
    } catch (e: InvalidCipherTextException) {
        null
    }
}

/** aead_xchacha20_poly1305_rtpsize: XChaCha20-Poly1305 (IETF), 24 byte nonce. */
internal class XChaCha(private val key: ByteArray) : TransportAead() {
    private val aead = ChaCha20Poly1305()
    override val overhead = 16

    private fun init(encrypt: Boolean, nonce: ByteArray, aad: ByteArray) {
        val subKey = hChaCha20(key, nonce)
        val n12 = ByteArray(12)
        System.arraycopy(nonce, 16, n12, 4, 8)
        aead.init(encrypt, AEADParameters(KeyParameter(subKey), 128, n12, aad))
    }

    override fun seal(nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        init(true, nonce, aad)
        val out = ByteArray(aead.getOutputSize(pt.size))
        val n = aead.processBytes(pt, 0, pt.size, out, 0)
        aead.doFinal(out, n)
        return out
    }

    override fun open(nonce: ByteArray, aad: ByteArray, ct: ByteArray, off: Int, len: Int): ByteArray? = try {
        init(false, nonce, aad)
        val out = ByteArray(aead.getOutputSize(len))
        val n = aead.processBytes(ct, off, len, out, 0)
        aead.doFinal(out, n)
        out
    } catch (e: InvalidCipherTextException) {
        null
    }

    companion object {
        /** HChaCha20 (draft-irtf-cfrg-xchacha, section 2.2) over the key and the first 16 nonce bytes. */
        fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
            val s = IntArray(16)
            s[0] = 0x61707865
            s[1] = 0x3320646e
            s[2] = 0x79622d32
            s[3] = 0x6b206574
            for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
            for (i in 0 until 4) s[12 + i] = le32(nonce, 4 * i)
            for (round in 0 until 10) {
                quarter(s, 0, 4, 8, 12)
                quarter(s, 1, 5, 9, 13)
                quarter(s, 2, 6, 10, 14)
                quarter(s, 3, 7, 11, 15)
                quarter(s, 0, 5, 10, 15)
                quarter(s, 1, 6, 11, 12)
                quarter(s, 2, 7, 8, 13)
                quarter(s, 3, 4, 9, 14)
            }
            val out = ByteArray(32)
            for (i in 0 until 4) putLe32(out, 4 * i, s[i])
            for (i in 0 until 4) putLe32(out, 16 + 4 * i, s[12 + i])
            return out
        }

        private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]
            s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
            s[c] += s[d]
            s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
            s[a] += s[b]
            s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
            s[c] += s[d]
            s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
        }

        private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun putLe32(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v ushr 8).toByte()
            b[o + 2] = (v ushr 16).toByte()
            b[o + 3] = (v ushr 24).toByte()
        }
    }
}

/** NaCl secretbox (XSalsa20-Poly1305), 24 byte nonce, tag before the ciphertext. No AAD: the header is only clear. */
internal class SecretBox(private val key: ByteArray) : TransportAead() {
    private val salsa = XSalsa20Engine()
    private val mac = Poly1305()
    override val overhead = 16

    private fun start(nonce: ByteArray) {
        salsa.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val polyKey = ByteArray(32)
        salsa.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        mac.init(KeyParameter(polyKey))
    }

    override fun seal(nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        start(nonce)
        val out = ByteArray(16 + pt.size)
        salsa.processBytes(pt, 0, pt.size, out, 16)
        mac.update(out, 16, pt.size)
        mac.doFinal(out, 0)
        return out
    }

    override fun open(nonce: ByteArray, aad: ByteArray, ct: ByteArray, off: Int, len: Int): ByteArray? {
        if (len < 16) return null
        start(nonce)
        mac.update(ct, off + 16, len - 16)
        val tag = ByteArray(16)
        mac.doFinal(tag, 0)
        if (!Bytes.ctEquals(tag, 0, ct, off, 16)) return null
        val out = ByteArray(len - 16)
        salsa.processBytes(ct, off + 16, out.size, out, 0)
        return out
    }
}

/**
 * Transport encryption of whole RTP/RTCP packets for the "rtpsize" family of modes: the clear header (see
 * [Rtp.clearLen]) is authenticated as AAD (where the cipher can), everything after it is encrypted, and a 32 bit
 * big-endian nonce counter is appended to the packet. [noncePos] is where that counter sits in the full nonce.
 */
internal class PacketCipher(
    val mode: String,
    private val aead: TransportAead,
    private val nonceLen: Int,
    val rtpsizeHeader: Boolean = true,
    val noncePos: Int = 0,
) {
    /** Last nonce counter used by [seal]. */
    var counter = 0L

    /** Returns the decrypted packet (clear header + plaintext), or null when it doesn't authenticate. */
    fun open(p: ByteArray, len: Int): ByteArray? {
        val hdr = Rtp.clearLen(p, len, rtpsizeHeader)
        if (hdr < 0 || len < hdr + aead.overhead + 4) return null
        val nonce = ByteArray(nonceLen)
        System.arraycopy(p, len - 4, nonce, noncePos, 4)
        val pt = aead.open(nonce, p.copyOfRange(0, hdr), p, hdr, len - 4 - hdr) ?: return null
        val out = ByteArray(hdr + pt.size)
        System.arraycopy(p, 0, out, 0, hdr)
        System.arraycopy(pt, 0, out, hdr, pt.size)
        return out
    }

    fun seal(plain: ByteArray): ByteArray {
        val hdr = Rtp.clearLen(plain, plain.size, rtpsizeHeader)
        counter = (counter + 1) and 0xFFFFFFFFL
        val n4 = ByteArray(4)
        Bytes.putU32(n4, 0, counter)
        val nonce = ByteArray(nonceLen)
        System.arraycopy(n4, 0, nonce, noncePos, 4)
        val header = plain.copyOfRange(0, hdr)
        val ct = aead.seal(nonce, header, plain.copyOfRange(hdr, plain.size))
        return Bytes.concat(header, ct, n4)
    }

    companion object {
        const val AES256_GCM = "aead_aes256_gcm_rtpsize"
        const val XCHACHA20 = "aead_xchacha20_poly1305_rtpsize"
        const val XSALSA20_LITE = "xsalsa20_poly1305_lite_rtpsize"

        /** Modes we can speak to Discord's media servers, most preferred first. */
        val SERVER_MODES = listOf(AES256_GCM, XCHACHA20)

        fun server(mode: String, key: ByteArray): PacketCipher? = when (mode) {
            AES256_GCM -> PacketCipher(mode, AesGcm(key), 12)
            XCHACHA20 -> PacketCipher(mode, XChaCha(key), 24)
            else -> null
        }

        /**
         * The local leg to Discord 126.21's own voice engine, which still knows xsalsa20_poly1305_lite_rtpsize. How it
         * lays the packet out isn't documented, so the relay tries these until one authenticates (Poly1305 makes a
         * match conclusive); the documented layout comes first.
         */
        fun localVariants(key: ByteArray): List<PacketCipher> = listOf(
            PacketCipher(XSALSA20_LITE, SecretBox(key), 24, rtpsizeHeader = true),
            PacketCipher(XSALSA20_LITE, SecretBox(key), 24, rtpsizeHeader = false),
            PacketCipher(XSALSA20_LITE, SecretBox(key), 24, rtpsizeHeader = true, noncePos = 20),
            PacketCipher(XSALSA20_LITE, SecretBox(key), 24, rtpsizeHeader = false, noncePos = 20),
        )
    }
}
