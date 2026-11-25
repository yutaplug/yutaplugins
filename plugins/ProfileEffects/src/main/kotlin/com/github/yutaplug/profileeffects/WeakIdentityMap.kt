package com.github.yutaplug.profileeffects

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * A thread-safe map keyed by object identity that doesn't keep its keys alive. Models are keyed by
 * identity because their data-class `hashCode` changes while Gson is still filling them in.
 */
internal class WeakIdentityMap<V : Any> {
    private class Key(referent: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(referent, queue) {
        val hash = System.identityHashCode(referent)

        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            val value = get() ?: return false
            return when (other) {
                is Key -> other.get() === value
                is Probe -> other.target === value
                else -> false
            }
        }
    }

    /** A lookup key that doesn't allocate a reference. */
    private class Probe(val target: Any) {
        override fun hashCode() = System.identityHashCode(target)

        override fun equals(other: Any?) = other is Key && other.get() === target
    }

    private val queue = ReferenceQueue<Any>()
    private val map = HashMap<Any, V>()

    @Synchronized fun put(key: Any, value: V) {
        expunge()
        map.remove(Probe(key))
        map[Key(key, queue)] = value
    }

    @Synchronized fun get(key: Any): V? = map[Probe(key)]

    @Synchronized fun remove(key: Any): V? = map.remove(Probe(key))

    private fun expunge() {
        while (true) {
            val key = queue.poll() ?: return
            map.remove(key)
        }
    }
}
