package com.github.yutaplug.profileeffects

import com.discord.stores.StoreStream
import java.lang.reflect.Method

/**
 * Aliucord's patched base app adds `displayNameStyles` to Discord's user models (see Aliucord's
 * Decorations core plugin), so every user the client knows about (friends, DM recipients, message
 * authors) already carries its style. Read it by reflection so older base apps without the field
 * still work, falling back to the styles this plugin captures itself.
 */
internal object ModelStyles {
    private var getter: Method? = null
    private var getterClass: Class<*>? = null
    private var fontId: Method? = null
    private var effectId: Method? = null
    private var colors: Method? = null
    private var unsupported = false

    /** Main thread. */
    fun style(userId: Long): NameStyle? {
        if (unsupported) return null
        val user = StoreStream.getUsers().users[userId] ?: return null
        return try {
            val method = getter.takeIf { getterClass === user.javaClass }
                ?: user.javaClass.getMethod("getDisplayNameStyles").also {
                    getter = it
                    getterClass = user.javaClass
                }
            convert(method.invoke(user) ?: return null)
        } catch (_: NoSuchMethodException) {
            unsupported = true
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun convert(style: Any): NameStyle? {
        val type = style.javaClass
        if (fontId?.declaringClass != type) {
            fontId = type.getMethod("getFontId")
            effectId = type.getMethod("getEffectId")
            colors = type.getMethod("getColors")
        }
        val font = (fontId!!.invoke(style) as? Number)?.toInt() ?: NameStyle.FONT_DEFAULT
        val effect = (effectId!!.invoke(style) as? Number)?.toInt() ?: NameStyle.EFFECT_SOLID
        val values = Json.colors(colors!!.invoke(style) as? List<*>) ?: IntArray(0)
        if (font == NameStyle.FONT_DEFAULT && values.isEmpty()) return null
        return NameStyle(font, effect, values)
    }
}
