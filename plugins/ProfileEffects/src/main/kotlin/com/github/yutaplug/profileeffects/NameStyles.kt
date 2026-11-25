package com.github.yutaplug.profileeffects

/** A display name style: Discord's font, effect and up to five colors. */
internal class NameStyle(val fontId: Int, val effectId: Int, val colors: IntArray) {
    override fun equals(other: Any?): Boolean = other is NameStyle &&
        other.fontId == fontId &&
        other.effectId == effectId &&
        other.colors.contentEquals(colors)

    override fun hashCode(): Int = (fontId * 31 + effectId) * 31 + colors.contentHashCode()

    companion object {
        const val FONT_DEFAULT = 11

        const val EFFECT_SOLID = 1
        const val EFFECT_GRADIENT = 2
        const val EFFECT_NEON = 3
        const val EFFECT_TOON = 4
        const val EFFECT_POP = 5
        const val EFFECT_GLOW = 6
        const val EFFECT_PRISM = 7
        const val EFFECT_GUMMY = 8

        /** Parses a `display_name_styles` object; null for JSON null or an empty style. */
        fun parse(raw: Any?): NameStyle? {
            val map = raw as? Map<*, *> ?: return null
            val font = Json.long(map["font_id"])?.toInt() ?: FONT_DEFAULT
            val effect = Json.long(map["effect_id"])?.toInt() ?: EFFECT_SOLID
            val colors = Json.colors(map["colors"]) ?: IntArray(0)
            if (font == FONT_DEFAULT && colors.isEmpty()) return null
            return NameStyle(font, effect, colors)
        }
    }
}

/** Enhanced role colors: a role's gradient stops (two, or three for the holographic style). */
internal class RoleGradient(val colors: IntArray)

/**
 * Display name styles and role gradients that Discord 126.21's models drop. Filled from the
 * gateway/REST JSON (see [ModelCapture]) and from fetched profiles.
 */
internal object NameStyles {
    private const val MAX_USERS = 8192
    private const val MAX_ROLES = 32768

    private val users = lru<Long, NameStyle>(MAX_USERS)
    private val members = lru<Pair<Long, Long>, NameStyle>(MAX_USERS)
    private val roles = lru<Long, RoleGradient?>(MAX_ROLES)

    /** Called on any thread when a stored style or role gradient changes. */
    @Volatile var onChange: (() -> Unit)? = null

    @Volatile var version = 0
        private set

    private fun <K, V> lru(capacity: Int) = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    fun setUser(userId: Long, style: NameStyle?) {
        if (userId <= 0) return
        synchronized(this) {
            val previous = if (style == null) users.remove(userId) else users.put(userId, style)
            if (previous != style) changed()
        }
    }

    /** Member styles whose guild isn't known yet, by user; at most one pending per user. */
    private val unresolved = HashMap<Long, NameStyle?>()

    @Synchronized fun setUnresolved(userId: Long, style: NameStyle?) {
        if (userId <= 0) return
        unresolved[userId] = style
        if (unresolved.size > MAX_USERS) unresolved.clear()
    }

    /** Discord stored [userId] as a member of [guildId]: give it the style parsed with it, if any. */
    fun resolve(guildId: Long, userId: Long) {
        val style: NameStyle?
        synchronized(this) {
            if (!unresolved.containsKey(userId)) return
            style = unresolved.remove(userId)
        }
        setMember(guildId, userId, style)
    }

    fun setMember(guildId: Long, userId: Long, style: NameStyle?) {
        if (guildId <= 0 || userId <= 0) return
        val key = Pair(guildId, userId)
        synchronized(this) {
            val previous = if (style == null) members.remove(key) else members.put(key, style)
            if (previous != style) changed()
        }
    }

    @Synchronized fun member(userId: Long, guildId: Long?): NameStyle? =
        if (guildId != null && guildId > 0) members[Pair(guildId, userId)] else null

    @Synchronized fun user(userId: Long): NameStyle? = users[userId]

    /** The guild style wins over the global one, as on Discord. */
    fun style(userId: Long, guildId: Long?): NameStyle? =
        member(userId, guildId) ?: ModelStyles.style(userId) ?: user(userId)

    fun setRole(roleId: Long, colors: Map<*, *>?) {
        if (roleId <= 0) return
        val primary = Json.color(colors?.get("primary_color"))
        val secondary = Json.color(colors?.get("secondary_color"))
        val tertiary = Json.color(colors?.get("tertiary_color"))
        val gradient = if (primary != null && secondary != null && (primary and 0xFFFFFF) != 0) {
            RoleGradient(if (tertiary != null) intArrayOf(primary, secondary, tertiary) else intArrayOf(primary, secondary))
        } else {
            null
        }
        synchronized(this) {
            val previous = roles.put(roleId, gradient)
            if (previous?.colors?.contentEquals(gradient?.colors) != true && (previous != null || gradient != null)) {
                changed()
            }
        }
    }

    /** Whether this role was seen in any payload since the plugin started. */
    @Synchronized fun knowsRole(roleId: Long) = roles.containsKey(roleId)

    @Synchronized fun role(roleId: Long): RoleGradient? = roles[roleId]

    private fun changed() {
        version++
        onChange?.invoke()
    }

    @Synchronized fun clear() {
        users.clear()
        unresolved.clear()
        members.clear()
        roles.clear()
        changed()
    }
}
