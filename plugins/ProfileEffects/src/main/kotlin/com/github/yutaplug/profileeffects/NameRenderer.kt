package com.github.yutaplug.profileeffects

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.CharacterStyle
import android.widget.TextView
import com.discord.api.role.GuildRole
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import java.util.WeakHashMap

/**
 * Applies display name styles (font, colors, effects) and role gradients to name TextViews, and
 * re-applies them to the views it styled when a font or late data arrives.
 */
internal class NameRenderer(
    private val prefs: Prefs,
    private val fetchRoles: (Long) -> Unit,
    private val log: (String, Throwable) -> Unit,
) {
    private class Target(
        val userId: Long,
        val guildId: Long?,
        val roles: List<Long>?,
        val roleColors: Boolean,
        /** In the friend list, the DM list or a DM, where colors follow [Prefs.nameColorsInLists]. */
        val list: Boolean,
        /** The user object the view was bound with, which carries the style even when the store lacks the user. */
        val model: Any?,
    )

    private val targets = WeakHashMap<TextView, Target>()

    fun refreshAll() {
        // Detached rows too: lists re-attach cached rows without binding them again.
        for ((view, target) in ArrayList(targets.entries)) {
            try {
                style(view, target)
            } catch (error: Throwable) {
                log("Could not restyle the name of ${target.userId}", error)
            }
        }
    }

    /** Removes this view's style and stops restyling it, for views Discord now uses for something else. */
    fun forget(view: TextView) {
        targets.remove(view) ?: return
        val text = view.text as? Spannable ?: return
        val spans = text.getSpans(0, text.length, StyledName::class.java)
        for (span in spans) text.removeSpan(span)
    }

    /** Removes every style this renderer added, for when the plugin stops. */
    fun clear() {
        for (view in ArrayList(targets.keys)) {
            val text = view.text as? Spannable ?: continue
            val spans = text.getSpans(0, text.length, StyledName::class.java)
            if (spans.isEmpty()) continue
            for (span in spans) text.removeSpan(span)
            view.requestLayout()
            view.invalidate()
        }
        targets.clear()
    }

    /**
     * Main thread, right after Discord set the view's text and color.
     * [roleColors] is false where Discord itself never shows role colors (profile names).
     */
    fun apply(
        view: TextView,
        userId: Long,
        guildId: Long?,
        roles: List<Long>?,
        roleColors: Boolean,
        list: Boolean = false,
        model: Any? = null,
    ) {
        val target = Target(userId, guildId?.takeIf { it > 0 }, roles, roleColors, list, model)
        targets[view] = target
        try {
            style(view, target)
        } catch (error: Throwable) {
            log("Could not style the name of $userId", error)
            return
        }
    }

    private fun style(view: TextView, target: Target) {
        val text = view.text ?: return
        if (text is Spanned) {
            val old = text.getSpans(0, text.length, StyledName::class.java)
            if (old.isNotEmpty() && text is Spannable) for (span in old) text.removeSpan(span)
        }
        if (text.isEmpty() || target.userId <= 0) return

        val style = NameStyles.style(target.userId, target.guildId, target.model)
        val typeface = if (prefs.nameFonts && style != null && style.fontId != NameStyle.FONT_DEFAULT) {
            NameFonts.get(view.context, style.fontId)
        } else {
            null
        }
        // Discord draws every name font at the name's own size; only its files' units per em differ from ours.
        val fontScale = if (typeface != null) NameFonts.scale(style!!.fontId) else 1f
        val fontSpacing = if (typeface != null) NameFonts.spacing(style!!.fontId) else 0f
        val density = view.resources.displayMetrics.density
        val name = text.toString()
        val spans = ArrayList<Triple<Int, Int, CharacterStyle>>()

        // As on Discord's current apps, a colored role wins over the display name style's colors;
        // the style still supplies the font.
        val role = if (target.roleColors) colorRole(target) else null
        // The friend and DM lists can show only the font, keeping Discord's own text color.
        val colorsShown = prefs.nameColors && (!target.list || prefs.nameColorsInLists)
        val styleColors = style?.colors?.takeIf { colorsShown && it.isNotEmpty() }
        if (role == null && styleColors != null) {
            effectSpans(view, style.effectId, styleColors, typeface, fontScale, fontSpacing, name, density, spans)
        } else {
            // Without a gradient, Discord's own text color (the role color) stays.
            val gradient = if (role != null && prefs.roleGradients) roleGradient(target.guildId!!, role) else null
            if (gradient != null || typeface != null) {
                spans.add(Triple(0, name.length, NameSpan(typeface, 0, gradient, NameSpan.HORIZONTAL, name, fontScale, fontSpacing)))
            }
        }
        if (spans.isEmpty()) return

        val spannable = text as? Spannable ?: SpannableString(text)
        for ((start, end, span) in spans) {
            spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (spannable !== text) {
            view.setText(spannable, TextView.BufferType.SPANNABLE)
        } else {
            view.requestLayout()
            view.invalidate()
        }
    }

    private fun effectSpans(
        view: TextView,
        effect: Int,
        colors: IntArray,
        typeface: Typeface?,
        fontScale: Float,
        fontSpacing: Float,
        name: String,
        density: Float,
        out: MutableList<Triple<Int, Int, CharacterStyle>>,
    ) {
        val first = colors[0]
        val all = if (colors.size > 1) colors else null
        val end = name.length
        fun span(fill: Int, stops: IntArray? = null, mode: Int = NameSpan.HORIZONTAL, prismMinCycle: Float = 0f) =
            NameSpan(typeface, fill, stops, mode, name, fontScale, fontSpacing, prismMinCycle, view)

        when (effect) {
            // Discord draws Glow exactly like Gradient unless it is animating.
            NameStyle.EFFECT_GRADIENT, NameStyle.EFFECT_GLOW -> {
                out.add(Triple(0, end, span(first, all, NameSpan.DIAGONAL)))
            }

            NameStyle.EFFECT_PRISM -> {
                out.add(Triple(0, end, span(first, all, NameSpan.PRISM, 38f * density)))
            }

            NameStyle.EFFECT_NEON, NameStyle.EFFECT_TOON, NameStyle.EFFECT_POP -> {
                out.add(Triple(0, end, OutlineSpan(effect, first, typeface, fontScale, fontSpacing, density)))
            }

            NameStyle.EFFECT_GUMMY -> {
                // Each letter takes the next color of the pattern, with no outline or shadow, as on Discord.
                var index = 0
                var letter = 0
                while (index < end) {
                    val next = index + Character.charCount(name.codePointAt(index))
                    val color = colors[letter % colors.size]
                    out.add(Triple(index, next, span(color)))
                    if (!Character.isWhitespace(name.codePointAt(index))) letter++
                    index = next
                }
            }

            else -> {
                out.add(Triple(0, end, span(first)))
            }
        }
    }

    /** The member's highest role that has a color: the one Discord colors their name with. */
    private fun colorRole(target: Target): GuildRole? {
        val guildId = target.guildId ?: return null
        val roleIds = target.roles ?: StoreStream.getGuilds().getMember(guildId, target.userId)?.roles ?: return null
        if (roleIds.isEmpty()) return null
        val guildRoles = StoreStream.getGuilds().roles[guildId] ?: return null
        var best: GuildRole? = null
        for (id in roleIds) {
            val role = guildRoles[id] ?: continue
            if (role.b() == 0) continue
            if (RoleUtils.rankIsHigher(role, best)) best = role
        }
        return best
    }

    private fun roleGradient(guildId: Long, role: GuildRole): IntArray? {
        if (!NameStyles.knowsRole(role.id)) {
            fetchRoles(guildId)
            return null
        }
        return NameStyles.role(role.id)?.colors
    }

    companion object {
        fun mix(from: Int, to: Int, amount: Float): Int {
            val keep = 1f - amount
            return Color.rgb(
                (Color.red(from) * keep + Color.red(to) * amount).toInt(),
                (Color.green(from) * keep + Color.green(to) * amount).toInt(),
                (Color.blue(from) * keep + Color.blue(to) * amount).toInt(),
            )
        }
    }
}
