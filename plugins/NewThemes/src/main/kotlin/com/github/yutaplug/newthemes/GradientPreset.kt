package com.github.yutaplug.newthemes

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader

/**
 * Nitro client theme presets, taken from Discord's web client. [id] is the
 * value stored in `appearance.client_theme_settings.background_gradient_preset_id`.
 */
internal class GradientPreset(
    val id: Int,
    val name: String,
    val light: Boolean,
    private val colors: IntArray,
    private val stops: FloatArray,
    private val angle: Float,
    midpointPercentage: Float,
) {
    /** The native theme the preset is drawn on top of. */
    val theme: ThemeChoice get() = if (light) ThemeChoice.LIGHT else ThemeChoice.DARK

    /** Representative color, used wherever a solid color is needed. */
    val mid: Int = colorAt(midpointPercentage / 100f)

    private fun colorAt(fraction: Float): Int {
        if (fraction <= stops[0] / 100f) return colors[0]
        for (index in 1 until stops.size) {
            val end = stops[index] / 100f
            if (fraction <= end) {
                val start = stops[index - 1] / 100f
                val t = if (end > start) (fraction - start) / (end - start) else 1f
                return mix(colors[index - 1], colors[index], t)
            }
        }
        return colors[colors.size - 1]
    }

    /** A CSS-style `linear-gradient(angle)` covering a [width] x [height] box. */
    fun shader(left: Float, top: Float, width: Float, height: Float): Shader {
        val radians = Math.toRadians(angle.toDouble())
        val dx = Math.sin(radians).toFloat()
        val dy = -Math.cos(radians).toFloat()
        val half = (Math.abs(width * dx) + Math.abs(height * dy)) / 2f
        val cx = left + width / 2f
        val cy = top + height / 2f
        val positions = FloatArray(stops.size) { (stops[it] / 100f).coerceIn(0f, 1f) }
        return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, positions, Shader.TileMode.CLAMP)
    }

    companion object {
        private fun preset(id: Int, name: String, light: Boolean, angle: Float, midpoint: Float, vararg stops: Pair<Int, Float>) =
            GradientPreset(id, name, light, IntArray(stops.size) { stops[it].first or Color.BLACK }, FloatArray(stops.size) { stops[it].second }, angle, midpoint)

        private val light = listOf(
            preset(0, "Mint Apple", true, 180f, 27f, 0x56b69f to 6.15f, 0x63bc61 to 48.7f, 0x9eca67 to 93.07f),
            preset(1, "Citrus Sherbert", true, 180f, 27f, 0xf3b336 to 31.1f, 0xee8558 to 67.09f),
            preset(2, "Retro Raincloud", true, 148.71f, 50f, 0x3a7ca1 to 5.64f, 0x7f7eb9 to 26.38f, 0x7f7eb9 to 49.92f, 0x3a7ca1 to 73.12f),
            preset(3, "Hanami", true, 38.08f, 50f, 0xefaab3 to 3.56f, 0xefd696 to 35.49f, 0xa6daa2 to 68.78f),
            preset(4, "Sunrise", true, 154.19f, 50f, 0x9f4175 to 8.62f, 0xc49064 to 48.07f, 0xa6953d to 76.04f),
            preset(5, "Cotton Candy", true, 180.14f, 50f, 0xf4abb8 to 8.5f, 0xb1c2fc to 94.28f),
            preset(6, "Lofi Vibes", true, 179.52f, 27f, 0xa4c0f7 to 7.08f, 0xa9e4e8 to 34.94f, 0xb0e2b8 to 65.12f, 0xcfdfa2 to 96.23f),
            preset(7, "Desert Khaki", true, 38.99f, 50f, 0xe7dbd0 to 12.92f, 0xdfd0b2 to 32.92f, 0xe0d6a3 to 52.11f),
        )

        private val dark = listOf(
            preset(8, "Sunset", false, 141.68f, 35f, 0x48288c to 27.57f, 0xdb7f4b to 71.25f),
            preset(9, "Chroma Glow", false, 128.92f, 15f, 0x0eb5bf to 3.94f, 0x4c0ce0 to 26.1f, 0xa308a7 to 39.82f, 0x9a53ff to 56.89f, 0x218be0 to 76.45f),
            preset(10, "Forest", false, 162.27f, 50f, 0x142215 to 11.2f, 0x2d4d39 to 29.93f, 0x454c32 to 48.64f, 0x5a7c58 to 67.85f, 0xa98e4b to 83.54f),
            preset(11, "Crimson Moon", false, 64.92f, 30f, 0x950909 to 16.17f, 0x000000 to 72f),
            preset(12, "Midnight Blurple", false, 48.17f, 24f, 0x5348ca to 11.21f, 0x140730 to 61.92f),
            preset(13, "Mars", false, 170.82f, 50f, 0x895240 to 14.61f, 0x8f4343 to 74.62f),
            preset(14, "Dusk", false, 180f, 50f, 0x665069 to 12.84f, 0x91a3d1 to 85.99f),
            preset(15, "Under the Sea", false, 179.14f, 50f, 0x647962 to 1.91f, 0x588575 to 48.99f, 0x6a8482 to 96.35f),
            preset(17, "Retro Storm", false, 148.71f, 61f, 0x3a7ca1 to 5.64f, 0x58579a to 26.38f, 0x58579a to 49.92f, 0x3a7ca1 to 73.12f),
            preset(18, "Neon Nights", false, 180f, 50f, 0x01a89e to 0f, 0x7d60ba to 50f, 0xb43898 to 100f),
            preset(20, "Strawberry Lemonade", false, 161.03f, 32f, 0xaf1a6c to 18.79f, 0xc26b20 to 49.76f, 0xe7a525 to 80.72f),
            preset(21, "Aurora", false, 239.16f, 34f, 0x062053 to 10.39f, 0x191fbb to 26.87f, 0x13929a to 48.31f, 0x218573 to 64.98f, 0x051a81 to 92.5f),
            preset(19, "Sepia", false, 69.98f, 50f, 0x857664 to 14.14f, 0x5b4421 to 60.35f),
        )

        private val blurpleTwilight = preset(22, "Blurple Twilight", false, 47.61f, 50f, 0x2c3fe7 to 11.18f, 0x261d83 to 64.54f)

        // Not offered in the picker, but rendered when the account already uses them.
        private val hidden = listOf(
            preset(16, "Easter Egg", true, 180f, 50f, 0x7289da to 4f, 0x4e5d94 to 96f),
            preset(23, "Hexagon", false, 135f, 35f, 0x4645ff to 0f, 0xaf01f3 to 33.33f, 0xff6698 to 66.67f, 0xf89a03 to 100f),
        )

        /** Picker order, matching the mobile client: dark presets first. */
        val shown: List<GradientPreset> = listOf(blurpleTwilight) + dark + light

        private val all = shown + hidden

        fun byId(id: Int?): GradientPreset? = if (id == null) null else all.firstOrNull { it.id == id }

        fun mix(from: Int, to: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val a = (from shr shift) and 255
                val b = (to shr shift) and 255
                return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
            }
            return Color.rgb(channel(16), channel(8), channel(0))
        }
    }
}
