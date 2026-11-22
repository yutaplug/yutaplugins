package com.github.yutaplug.fixes.settings

/** Accessed under the controller lock; separates unsaved choices from server truth. */
internal class FriendRequestState {
    data class Selection(val revision: Long, val flags: Int)

    private var revision = 0L
    var confirmed: Int? = null
        private set
    var queued: Selection? = null
        private set
    var saving: Selection? = null
        private set

    val displayed: Int? get() = queued?.flags ?: saving?.flags ?: confirmed
    val isPending: Boolean get() = queued != null || saving != null

    fun read(flags: Int) {
        confirmed = flags
    }

    fun select(flags: Int) {
        queued = Selection(++revision, flags)
    }

    fun beginSave(): Selection? {
        if (saving != null) return null
        val next = queued ?: return null
        queued = null
        saving = next
        return next
    }

    fun complete(selection: Selection, serverFlags: Int?) {
        if (saving?.revision != selection.revision) return
        if (serverFlags != null) confirmed = serverFlags
        saving = null
    }

    companion object {
        fun toggle(flags: Int, index: Int, checked: Boolean): Int {
            require(index >= 0 && index <= 2)
            val known = flags and 14
            return when (index) {
                0 -> if (checked) 14 else known and 6
                1 -> if (checked) known or 2 else known and 4
                else -> if (checked) known or 4 else known and 2
            }
        }
    }
}
