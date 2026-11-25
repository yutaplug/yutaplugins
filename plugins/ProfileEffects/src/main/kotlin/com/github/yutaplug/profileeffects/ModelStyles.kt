package com.github.yutaplug.profileeffects

import com.discord.stores.StoreStream
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Aliucord's patched base app adds `displayNameStyles` to Discord's user classes (see Aliucord's
 * Decorations core plugin): a public field on the API `User`, and a getter on `CoreUser`/`MeUser`.
 * Read it by reflection so older base apps without it still work, falling back to the styles this
 * plugin captures itself.
 */
internal object ModelStyles {
    private val getters = HashMap<Class<*>, Method?>()
    private val fields = HashMap<Class<*>, Field?>()
    private var fontId: Method? = null
    private var effectId: Method? = null
    private var colors: Method? = null

    /**
     * Main thread. Prefers [user], the user object the view was bound with: people who only appear
     * in a reply preview, a DM or a search result are often missing from the user store.
     */
    fun style(userId: Long, user: Any? = null): NameStyle? {
        if (user != null) read(user)?.let { return convert(it) }
        val stored = StoreStream.getUsers().users[userId] ?: return null
        return convert(read(stored) ?: return null)
    }

    /** The user's `DisplayNameStyle`, or null when it has none or the base app lacks the field. */
    private fun read(user: Any): Any? = try {
        val type = user.javaClass
        val getter = getters.getOrPut(type) {
            try {
                type.getMethod("getDisplayNameStyles")
            } catch (_: NoSuchMethodException) {
                null
            }
        }
        if (getter != null) {
            getter.invoke(user)
        } else {
            fields.getOrPut(type) {
                try {
                    type.getField("displayNameStyles")
                } catch (_: NoSuchFieldException) {
                    null
                }
            }?.get(user)
        }
    } catch (_: Exception) {
        null
    }

    private fun convert(style: Any): NameStyle? = try {
        val type = style.javaClass
        if (fontId?.declaringClass != type) {
            fontId = type.getMethod("getFontId")
            effectId = type.getMethod("getEffectId")
            colors = type.getMethod("getColors")
        }
        val font = (fontId!!.invoke(style) as? Number)?.toInt() ?: NameStyle.FONT_DEFAULT
        val effect = (effectId!!.invoke(style) as? Number)?.toInt() ?: NameStyle.EFFECT_SOLID
        val values = Json.colors(colors!!.invoke(style) as? List<*>) ?: IntArray(0)
        if (font == NameStyle.FONT_DEFAULT && values.isEmpty()) null else NameStyle(font, effect, values)
    } catch (_: Exception) {
        null
    }
}
