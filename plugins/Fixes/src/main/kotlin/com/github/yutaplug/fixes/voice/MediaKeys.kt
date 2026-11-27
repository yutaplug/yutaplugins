package com.github.yutaplug.fixes.voice

import java.util.concurrent.ConcurrentHashMap

/** DAVE media keys of one voice connection, shared by the gateway side (MLS) and the UDP relay. */
internal class MediaKeys(private val selfUserId: Long) {
    val encryptor = FrameEncryptor()
    private val decryptors = ConcurrentHashMap<Long, FrameDecryptor>()
    private val ssrcUsers = ConcurrentHashMap<Int, Long>()

    /** Before any transition: frames that aren't protocol frames are only played in a non-E2EE session. */
    @Volatile var passthroughDefault = true

    var decrypted = 0L
    var decryptFailed = 0L
    var droppedPlain = 0L

    fun decryptor(userId: Long): FrameDecryptor = decryptors[userId] ?: synchronized(this) {
        decryptors.getOrPut(userId) {
            FrameDecryptor().also { it.transitionToPassthrough(passthroughDefault) }
        }
    }

    fun knownUsers(): Set<Long> = HashSet(decryptors.keys)

    fun removeUser(userId: Long) {
        decryptors.remove(userId)
        val it = ssrcUsers.entries.iterator()
        while (it.hasNext()) if (it.next().value == userId) it.remove()
    }

    fun mapSsrc(ssrc: Int, userId: Long) {
        if (ssrc == 0 || userId == selfUserId) return
        ssrcUsers[ssrc] = userId
    }

    fun userForSsrc(ssrc: Int) = ssrcUsers[ssrc]

    /** Decrypts a received Opus frame; null means drop it. */
    fun decrypt(ssrc: Int, frame: ByteArray): ByteArray? {
        val user = ssrcUsers[ssrc]
        if (user != null) {
            when (val r = decryptor(user).decrypt(frame)) {
                is FrameDecryptor.Result.Ok -> return ok(r.frame)
                FrameDecryptor.Result.NotProtocol -> return plainDropped()
                FrameDecryptor.Result.Failed -> {}
            }
        }
        // Unknown or stale SSRC (the speaking event can lag the audio): try everyone we have keys for.
        for ((uid, d) in decryptors) {
            if (uid == user) continue
            val r = d.decrypt(frame)
            if (r is FrameDecryptor.Result.Ok && DaveFrame.parse(frame) != null) {
                ssrcUsers[ssrc] = uid
                return ok(r.frame)
            }
        }
        if (user == null && passthroughDefault && DaveFrame.parse(frame) == null) return ok(frame)
        decryptFailed++
        return null
    }

    private fun ok(f: ByteArray): ByteArray {
        decrypted++
        return f
    }

    private fun plainDropped(): ByteArray? {
        droppedPlain++
        return null
    }

    fun clear() {
        decryptors.clear()
        ssrcUsers.clear()
        encryptor.setRatchet(null)
    }
}
