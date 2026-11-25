package com.github.yutaplug.profileeffects

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import com.discord.api.role.GuildRole
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import java.util.WeakHashMap
import kotlin.math.max

/** Evenly spaced gradient stops from 10% to 90%, as Discord places them. */
private fun stopPositions(count: Int): FloatArray {
    val positions = FloatArray(count)
    var i = 0
    while (i < count) {
        positions[i] = 0.1f + 0.8f * i / (count - 1)
        i++
    }
    return positions
}

/** Draws one run of a styled name. Gradients span the measured width of the whole name. */
internal class NameSpan(
    private val typeface: Typeface?,
    private val fill: Int,
    private val colors: IntArray?,
    private val vertical: Boolean,
    private val shadowRadius: Float,
    private val shadowDx: Float,
    private val shadowDy: Float,
    private val shadowColor: Int,
    private val name: String,
    /** Size relative to the name's own, for fonts Discord draws smaller or larger. */
    private val scale: Float = 1f,
    /** Extra letter spacing, in em. */
    private val spacing: Float = 0f,
) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        if (typeface != null) paint.typeface = typeface
        if (scale != 1f) paint.textSize *= scale
        if (spacing != 0f) paint.letterSpacing += spacing
    }

    override fun updateDrawState(paint: TextPaint) {
        if (typeface != null) paint.typeface = typeface
        if (scale != 1f) paint.textSize *= scale
        if (spacing != 0f) paint.letterSpacing += spacing
        if (fill != 0) paint.color = fill
        val stops = colors
        if (stops != null && stops.size > 1) {
            paint.color = Color.BLACK
            paint.shader = if (vertical) {
                LinearGradient(0f, 0f, 0f, max(paint.textSize * 1.2f, 1f), stops, null, Shader.TileMode.CLAMP)
            } else {
                // Discord spreads the stops between 10% and 90% of the name.
                LinearGradient(0f, 0f, max(paint.measureText(name), 1f), 0f, stops, stopPositions(stops.size), Shader.TileMode.CLAMP)
            }
        }
        if (shadowRadius > 0f) paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
    }
}

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
        val spans = text.getSpans(0, text.length, NameSpan::class.java)
        for (span in spans) text.removeSpan(span)
    }

    /** Removes every style this renderer added, for when the plugin stops. */
    fun clear() {
        for (view in ArrayList(targets.keys)) {
            val text = view.text as? Spannable ?: continue
            val spans = text.getSpans(0, text.length, NameSpan::class.java)
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
            val old = text.getSpans(0, text.length, NameSpan::class.java)
            if (old.isNotEmpty() && text is Spannable) for (span in old) text.removeSpan(span)
        }
        if (text.isEmpty() || target.userId <= 0) return

        val style = NameStyles.style(target.userId, target.guildId, target.model)
        val typeface = if (prefs.nameFonts && style != null && style.fontId != NameStyle.FONT_DEFAULT) {
            NameFonts.get(view.context, style.fontId)
        } else {
            null
        }
        val fontScale = if (typeface != null) capScale(view, typeface) else 1f
        val fontSpacing = if (typeface != null) NameFonts.spacing(style!!.fontId) else 0f
        val density = view.resources.displayMetrics.density
        val name = text.toString()
        val spans = ArrayList<Triple<Int, Int, NameSpan>>()

        // As on Discord's current apps, a colored role wins over the display name style's colors;
        // the style still supplies the font.
        val role = if (target.roleColors) colorRole(target) else null
        // The friend and DM lists can show only the font, keeping Discord's own text color.
        val colorsShown = prefs.nameColors && (!target.list || prefs.nameColorsInLists)
        val styleColors = style?.colors?.takeIf { colorsShown && it.isNotEmpty() }
        if (role == null && styleColors != null) {
            effectSpans(style.effectId, styleColors, typeface, fontScale, fontSpacing, name, density, spans)
        } else {
            // Without a gradient, Discord's own text color (the role color) stays.
            val gradient = if (role != null && prefs.roleGradients) roleGradient(target.guildId!!, role) else null
            if (gradient != null || typeface != null) {
                spans.add(Triple(0, name.length, NameSpan(typeface, 0, gradient, false, 0f, 0f, 0f, 0, name, fontScale, fontSpacing)))
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
        effect: Int,
        colors: IntArray,
        typeface: Typeface?,
        fontScale: Float,
        fontSpacing: Float,
        name: String,
        density: Float,
        out: MutableList<Triple<Int, Int, NameSpan>>,
    ) {
        val first = colors[0]
        val all = if (colors.size > 1) colors else null
        val end = name.length
        fun span(
            fill: Int,
            stops: IntArray? = null,
            vertical: Boolean = false,
            radius: Float = 0f,
            dx: Float = 0f,
            dy: Float = 0f,
            shadow: Int = 0,
        ) = NameSpan(typeface, fill, stops, vertical, radius, dx, dy, shadow, name, fontScale, fontSpacing)

        when (effect) {
            NameStyle.EFFECT_GRADIENT, NameStyle.EFFECT_PRISM -> {
                out.add(Triple(0, end, span(first, all)))
            }

            NameStyle.EFFECT_GLOW -> {
                out.add(Triple(0, end, span(first, all, radius = 4f * density, shadow = withAlpha(first, 0xB0))))
            }

            NameStyle.EFFECT_NEON -> {
                out.add(Triple(0, end, span(mix(first, Color.WHITE, 0.55f), radius = 5f * density, shadow = first)))
            }

            NameStyle.EFFECT_TOON -> {
                val stops = intArrayOf(mix(first, Color.WHITE, 0.45f), first)
                out.add(
                    Triple(
                        0,
                        end,
                        span(first, stops, true, 1.2f * density, 0f, 1f * density, mix(first, Color.BLACK, 0.65f)),
                    ),
                )
            }

            NameStyle.EFFECT_POP -> {
                out.add(
                    Triple(
                        0,
                        end,
                        span(first, radius = 0.01f, dx = 1.5f * density, dy = 1.5f * density, shadow = mix(first, Color.BLACK, 0.55f)),
                    ),
                )
            }

            NameStyle.EFFECT_GUMMY -> {
                // Each letter takes the next color of the pattern.
                var index = 0
                var letter = 0
                while (index < end) {
                    val next = index + Character.charCount(name.codePointAt(index))
                    val color = colors[letter % colors.size]
                    out.add(Triple(index, next, span(color, radius = 0.01f, dy = 1f * density, shadow = mix(color, Color.BLACK, 0.5f))))
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

    private val capScales = HashMap<Pair<Typeface?, Typeface>, Float>()
    private val capPaint = TextPaint()
    private val capBounds = Rect()

    /**
     * Discord sizes name fonts so their capital letters are as tall as its own font's: Playpen
     * Sans, measured against the official app, comes out at the predicted ~0.86-0.88x. Do the same
     * against the font this view would otherwise use.
     */
    private fun capScale(view: TextView, typeface: Typeface): Float {
        val base = view.paint.typeface
        val key = Pair(base, typeface)
        capScales[key]?.let { return it }
        val scale = (capHeight(base) / capHeight(typeface).coerceAtLeast(1f))
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(0.75f, 1.25f) ?: 1f
        capScales[key] = scale
        return scale
    }

    private fun capHeight(typeface: Typeface?): Float {
        capPaint.typeface = typeface
        capPaint.textSize = 200f
        capPaint.getTextBounds("H", 0, 1, capBounds)
        return capBounds.height().toFloat()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)

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
