package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Color
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.utilities.color.ColorCompat
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.roundToInt

/** Sizes and colors the user can change. Everything reads settings at draw time, so changes apply live. */
internal object MarkdownAppearance {
    const val HEADER_1_SCALE = "header1Scale"
    const val HEADER_2_SCALE = "header2Scale"
    const val HEADER_3_SCALE = "header3Scale"
    const val SUBTEXT_SCALE = "subtextScale"
    const val CUSTOM_BULLET_COLOR = "customBulletColor"
    const val BULLET_COLOR = "bulletColor"
    const val DEFAULT_BULLET_COLOR = "#5865F2"

    val sizes = listOf(
        TextSize("Heading 1", "# Heading", HEADER_1_SCALE, 1.35f),
        TextSize("Heading 2", "## Heading", HEADER_2_SCALE, 1.20f),
        TextSize("Heading 3", "### Heading", HEADER_3_SCALE, 1.0f),
        TextSize("Subtext", "-# Subtext", SUBTEXT_SCALE, 0.75f),
    )

    val bullet = ColorSetting("Bullet color", CUSTOM_BULLET_COLOR, BULLET_COLOR, DEFAULT_BULLET_COLOR)

    // Not (1..3): Discord's stripped IntRange can't be iterated at runtime.
    val headingColors = listOf(1, 2, 3).map {
        ColorSetting("Heading $it color", "customHeader${it}Color", "header${it}Color", "#FFFFFF")
    }

    /** Offered in the color picker; any other color can be typed as hex. */
    val presets = listOf(
        "Blurple" to "#5865F2",
        "Green" to "#3BA55C",
        "Yellow" to "#FEE75C",
        "Orange" to "#E67E22",
        "Gold" to "#FAA61A",
        "Red" to "#ED4245",
        "Fuchsia" to "#EB459E",
        "Purple" to "#9B59B6",
        "Blue" to "#00B0F4",
        "Teal" to "#1ABC9C",
        "White" to "#FFFFFF",
        "Grey" to "#99AAB5",
    )

    class TextSize(
        val title: String,
        val example: String,
        val key: String,
        val default: Float,
    )

    /** A color that follows the theme until [customKey] is set; [key] keeps the last chosen hex. */
    class ColorSetting(
        val title: String,
        val customKey: String,
        val key: String,
        val default: String,
    ) {
        fun isCustom(settings: SettingsAPI) = settings.getBool(customKey, false)

        fun hex(settings: SettingsAPI): String = normalizeColor(settings.getString(key, default)) ?: default

        /** The custom color, or null while the theme color is used. */
        fun color(settings: SettingsAPI): Int? = if (isCustom(settings)) Color.parseColor(hex(settings)) else null
    }

    private val hexPattern = Pattern.compile("#[0-9A-F]{6}(?:[0-9A-F]{2})?")

    fun scale(settings: SettingsAPI, size: TextSize): Float =
        settings.getString(size.key, "").toFloatOrNull()?.takeIf { it.isFinite() && it >= 0.1f && it <= 3f }
            ?: size.default

    fun normalizeColor(raw: String): String? {
        val value = "#" + raw.trim().removePrefix("#").uppercase(Locale.ROOT)
        return value.takeIf { hexPattern.matcher(it).matches() }
    }

    fun themedColor(context: Context, attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(context, id)
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
