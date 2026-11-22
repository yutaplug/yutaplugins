package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.graphics.Color
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.utilities.color.ColorCompat
import java.util.Locale
import kotlin.math.roundToInt

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
        TextSize("Heading 3", "### Heading", HEADER_3_SCALE, 1.10f),
        TextSize("Subtext", "-# Subtext", SUBTEXT_SCALE, 0.75f),
    )

    data class TextSize(
        val title: String,
        val example: String,
        val key: String,
        val default: Float,
    )

    fun scale(settings: SettingsAPI, size: TextSize): Float =
        settings.getString(size.key, "").toFloatOrNull()?.takeIf { it.isFinite() && it in 0.1f..3f } ?: size.default

    fun normalizeColor(raw: String): String? {
        val value = "#" + raw.trim().removePrefix("#").uppercase(Locale.ROOT)
        return value.takeIf { it.matches(Regex("#[0-9A-F]{6}(?:[0-9A-F]{2})?")) }
    }

    fun bulletColor(settings: SettingsAPI, fallback: Int): Int {
        if (!settings.getBool(CUSTOM_BULLET_COLOR, false)) return fallback
        val color = normalizeColor(settings.getString(BULLET_COLOR, DEFAULT_BULLET_COLOR)) ?: return fallback
        return Color.parseColor(color)
    }

    fun themedColor(context: Context, attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(context, id)
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}

/** Reading at measure time lets existing messages adopt settings without reparsing their spoilers. */
internal class MarkdownSizeSpan(private val settings: SettingsAPI, private val size: MarkdownAppearance.TextSize) :
    RelativeSizeSpan(1f) {
    override fun updateDrawState(paint: TextPaint) {
        paint.textSize *= MarkdownAppearance.scale(settings, size)
    }

    override fun updateMeasureState(paint: TextPaint) = updateDrawState(paint)
}
