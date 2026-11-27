@file:Suppress("PackageName", "ktlint:standard:package-name")

package org.bouncycastle.mls.TreeKEM

import org.bouncycastle.mls.codec.MLSInputStream
import org.bouncycastle.mls.codec.MLSOutputStream

/**
 * Replaces BouncyCastle bcmls' LifeTime (MIT), whose verify() uses java.time, which Android only has from 8.0.
 * Same wire format and behaviour, using System.currentTimeMillis() instead. BouncyCastle's copy is left out of the
 * bundle (see build.gradle.kts).
 */
open class LifeTime : MLSInputStream.Readable, MLSOutputStream.Writable {
    private val notBefore: Long
    private val notAfter: Long

    @Suppress("unused")
    constructor(stream: MLSInputStream) {
        notBefore = stream.read(java.lang.Long.TYPE) as Long
        notAfter = stream.read(java.lang.Long.TYPE) as Long
    }

    /** not_before = 0, not_after = 2^64 - 1: the lifetime Discord's voice gateway requires on key packages. */
    constructor() {
        notBefore = 0
        notAfter = -1
    }

    override fun writeTo(stream: MLSOutputStream) {
        stream.write(notBefore)
        stream.write(notAfter)
    }

    protected open fun verify(): Boolean {
        val now = System.currentTimeMillis() / 1000L
        // not_after is unsigned on the wire; -1 is 2^64 - 1
        if (notAfter == -1L) return now >= notBefore
        return now >= notBefore && now < notAfter
    }
}
