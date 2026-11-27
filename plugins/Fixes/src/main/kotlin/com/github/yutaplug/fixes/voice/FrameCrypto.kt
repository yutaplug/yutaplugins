package com.github.yutaplug.fixes.voice

import android.os.SystemClock
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.mls.crypto.MlsCipherSuite
import org.bouncycastle.mls.crypto.Secret

/**
 * DAVE per-sender media key ratchet: the MLS hash ratchet (RFC 9420 section 9.1) seeded with the sender's exported
 * base secret. The key for a generation is DeriveTreeSecret(secret, "key", generation, 16).
 */
internal class KeyRatchet(private val baseSecret: ByteArray) {
    private val keys = HashMap<Int, ByteArray>()
    private var nextGeneration = 0
    private var nextSecret = baseSecret

    /** Identifies the key domain (epoch + sender); a re-installed ratchet for the same domain keeps the nonce. */
    fun sameDomain(other: KeyRatchet?) = other != null && baseSecret.contentEquals(other.baseSecret)

    @Synchronized
    fun key(generation: Int): ByteArray? {
        keys[generation]?.let { return it }
        if (generation < nextGeneration || generation > nextGeneration + MAX_GENERATION_GAP) return null
        while (nextGeneration <= generation) {
            val secret = Secret(nextSecret)
            keys[nextGeneration] = secret.deriveTreeSecret(SUITE, "key", nextGeneration, KEY_BYTES).value()
            nextSecret = secret.deriveTreeSecret(SUITE, "secret", nextGeneration, 32).value()
            nextGeneration++
        }
        return keys[generation]
    }

    companion object {
        const val KEY_BYTES = 16
        const val MAX_GENERATION_GAP = 250
        val SUITE: MlsCipherSuite = MlsCipherSuite.getSuite(MlsCipherSuite.MLS_128_DHKEMP256_AES128GCM_SHA256_P256)
    }
}

internal object DaveFrame {
    const val TAG_BYTES = 8
    const val MIN_SUPPLEMENTAL = TAG_BYTES + 1 + 1 + 2 // tag, 1 byte nonce, size byte, marker
    val SILENCE = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())

    /** The 32 bit truncated nonce goes, little-endian, in the last 4 bytes of the 12 byte AES-GCM nonce. */
    fun expandNonce(nonce: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 4) out[8 + i] = (nonce ushr (8 * i)).toByte()
        return out
    }

    fun gcm(encrypt: Boolean, key: ByteArray, nonce: Long, aad: ByteArray): org.bouncycastle.crypto.modes.GCMModeCipher {
        val c = GCMBlockCipher.newInstance(AESEngine.newInstance())
        c.init(encrypt, AEADParameters(KeyParameter(key), TAG_BYTES * 8, expandNonce(nonce), aad))
        return c
    }

    /** Parsed protocol frame footer, or null when the frame fails the protocol frame check. */
    class Parsed(
        val frame: ByteArray,
        val bodyLen: Int,
        val nonce: Long,
        /** offset, length pairs of the unencrypted ranges, ascending */
        val ranges: IntArray,
    ) {
        val tagOff get() = bodyLen
    }

    fun parse(f: ByteArray): Parsed? {
        val n = f.size
        if (n < MIN_SUPPLEMENTAL || f[n - 1] != 0xFA.toByte() || f[n - 2] != 0xFA.toByte()) return null
        val supp = Bytes.u8(f, n - 3)
        if (supp < MIN_SUPPLEMENTAL || supp > n) return null
        val bodyLen = n - supp
        val pos = intArrayOf(bodyLen + TAG_BYTES)
        val end = n - 3
        val nonce = Bytes.readLeb128(f, pos, end)
        if (nonce < 0 || nonce > 0xFFFFFFFFL) return null
        val ranges = ArrayList<Int>()
        var last = 0L
        while (pos[0] < end) {
            val off = Bytes.readLeb128(f, pos, end)
            val len = Bytes.readLeb128(f, pos, end)
            if (off < 0 || len < 0 || off < last || off + len > bodyLen) return null
            last = off + len
            ranges.add(off.toInt())
            ranges.add(len.toInt())
        }
        val arr = IntArray(ranges.size)
        for (k in 0 until ranges.size) arr[k] = ranges[k]
        return Parsed(f, bodyLen, nonce, arr)
    }
}

/** Encrypts our own outgoing Opus frames. */
internal class FrameEncryptor {
    private var ratchet: KeyRatchet? = null
    private var nonce = 0L

    /** No E2EE for this session (protocol version 0): frames go out untouched. */
    @Volatile var passthrough = true

    var encrypted = 0L
        private set
    var failed = 0L
        private set

    @Synchronized
    fun setRatchet(r: KeyRatchet?) {
        if (!(r != null && r.sameDomain(ratchet))) nonce = 0
        ratchet = r
    }

    @Synchronized
    fun hasKey() = ratchet != null

    /** Returns the protocol frame, the frame itself in passthrough mode, or null (drop) without a key. */
    @Synchronized
    fun encrypt(frame: ByteArray, clearPrefix: Int = 0): ByteArray? {
        if (passthrough) return frame
        val r = ratchet ?: run {
            failed++
            return null
        }
        nonce = (nonce + 1) and 0xFFFFFFFFL
        val key = r.key((nonce ushr 24).toInt()) ?: run {
            failed++
            return null
        }
        // The first [clear] bytes (VP8's payload header) stay readable for the packetizer and are authenticated as
        // AAD; the rest is encrypted. Opus frames are fully encrypted (clear = 0, no unencrypted range).
        val clear = minOf(clearPrefix, frame.size)
        val c = DaveFrame.gcm(true, key, nonce, frame.copyOfRange(0, clear))
        val ct = ByteArray(c.getOutputSize(frame.size - clear))
        val w = c.processBytes(frame, clear, frame.size - clear, ct, 0)
        c.doFinal(ct, w)
        // ct = ciphertext + 8 byte tag
        val nonceSize = Bytes.leb128Size(nonce)
        val rangesSize = if (clear > 0) Bytes.leb128Size(0) + Bytes.leb128Size(clear.toLong()) else 0
        val supp = DaveFrame.TAG_BYTES + nonceSize + rangesSize + 1 + 2
        val out = ByteArray(frame.size + supp)
        System.arraycopy(frame, 0, out, 0, clear)
        System.arraycopy(ct, 0, out, clear, ct.size)
        var o = clear + ct.size
        o += Bytes.writeLeb128(nonce, out, o)
        if (clear > 0) {
            o += Bytes.writeLeb128(0, out, o)
            o += Bytes.writeLeb128(clear.toLong(), out, o)
        }
        out[o++] = supp.toByte()
        out[o++] = 0xFA.toByte()
        out[o] = 0xFA.toByte()
        encrypted++
        return out
    }
}

/** Decrypts one sender's frames. Keeps the previous epochs' ratchets for 10 seconds while a transition settles. */
internal class FrameDecryptor {
    private class Manager(val ratchet: KeyRatchet) {
        var expiry = Long.MAX_VALUE
        var newest = -1L
        val seen = LinkedHashSet<Long>()

        fun accepts(nonce: Long) = !seen.contains(nonce) && nonce > newest - MAX_MISSING

        fun record(nonce: Long) {
            seen.add(nonce)
            if (nonce > newest) newest = nonce
            while (seen.size > MAX_MISSING) seen.remove(seen.iterator().next())
        }
    }

    private val managers = ArrayList<Manager>()
    private var passthroughUntil = 0L

    @Synchronized
    fun transitionToRatchet(r: KeyRatchet?) {
        if (r == null) return
        val newest = managers.lastOrNull()
        if (newest != null && r.sameDomain(newest.ratchet)) return
        val now = SystemClock.elapsedRealtime()
        for (m in managers) if (m.expiry == Long.MAX_VALUE) m.expiry = now + EXPIRY_MS
        managers.add(Manager(r))
    }

    @Synchronized
    fun transitionToPassthrough(passthrough: Boolean) {
        val now = SystemClock.elapsedRealtime()
        passthroughUntil = if (passthrough) Long.MAX_VALUE else minOf(passthroughUntil, now + EXPIRY_MS)
    }

    @Synchronized
    fun hasKeys() = managers.isNotEmpty()

    sealed class Result {
        class Ok(val frame: ByteArray) : Result()
        object NotProtocol : Result()
        object Failed : Result()
    }

    @Synchronized
    fun decrypt(frame: ByteArray): Result {
        val now = SystemClock.elapsedRealtime()
        if (frame.contentEquals(DaveFrame.SILENCE)) return Result.Ok(frame)
        val parsed = DaveFrame.parse(frame) ?: return if (now < passthroughUntil) Result.Ok(frame) else Result.NotProtocol
        // expired ratchets go, but the newest one always stays
        while (managers.size > 1 && managers[0].expiry <= now) managers.removeAt(0)
        // newest ratchet first (no IntRange/reversed(): Discord ships its own stripped kotlin.ranges)
        var i = managers.size - 1
        while (i >= 0) {
            val m = managers[i--]
            if (m.expiry <= now || !m.accepts(parsed.nonce)) continue
            val plain = tryDecrypt(m.ratchet, parsed) ?: continue
            m.record(parsed.nonce)
            return Result.Ok(plain)
        }
        // a protocol-looking frame that isn't ours to decrypt may still be a plain frame during passthrough
        return if (now < passthroughUntil) Result.Ok(frame) else Result.Failed
    }

    private fun tryDecrypt(r: KeyRatchet, p: Parsed): ByteArray? {
        val key = r.key((p.nonce ushr 24).toInt()) ?: return null
        val f = p.frame
        // Split the interleaved body into the unencrypted ranges (AAD) and the ciphertext.
        val aad = java.io.ByteArrayOutputStream()
        val ct = java.io.ByteArrayOutputStream()
        var pos = 0
        var i = 0
        while (i < p.ranges.size) {
            val off = p.ranges[i]
            val len = p.ranges[i + 1]
            ct.write(f, pos, off - pos)
            aad.write(f, off, len)
            pos = off + len
            i += 2
        }
        ct.write(f, pos, p.bodyLen - pos)
        ct.write(f, p.tagOff, DaveFrame.TAG_BYTES)
        val ctBytes = ct.toByteArray()
        val plain = try {
            val c = DaveFrame.gcm(false, key, p.nonce, aad.toByteArray())
            val out = ByteArray(c.getOutputSize(ctBytes.size))
            val w = c.processBytes(ctBytes, 0, ctBytes.size, out, 0)
            c.doFinal(out, w)
            out
        } catch (e: InvalidCipherTextException) {
            return null
        }
        if (p.ranges.isEmpty()) return plain
        // Put the plaintext back between the unencrypted ranges.
        val out = ByteArray(p.bodyLen)
        var src = 0
        pos = 0
        i = 0
        while (i < p.ranges.size) {
            val off = p.ranges[i]
            val len = p.ranges[i + 1]
            System.arraycopy(plain, src, out, pos, off - pos)
            src += off - pos
            System.arraycopy(f, off, out, off, len)
            pos = off + len
            i += 2
        }
        System.arraycopy(plain, src, out, pos, p.bodyLen - pos)
        return out
    }

    private companion object {
        const val EXPIRY_MS = 10_000L
        const val MAX_MISSING = 1000
    }
}

private typealias Parsed = DaveFrame.Parsed
