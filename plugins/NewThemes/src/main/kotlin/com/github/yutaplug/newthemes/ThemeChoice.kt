package com.github.yutaplug.newthemes

/** The full selection: a standard theme, or a Nitro preset drawn on its base theme. */
internal data class Appearance(val theme: ThemeChoice, val preset: GradientPreset? = null) {
    companion object {
        fun of(preset: GradientPreset) = Appearance(preset.theme, preset)
    }
}

internal enum class ThemeChoice(val label: String, val wire: Int, val native: String) {
    LIGHT("Light", 2, "light"),
    ASH("Ash", 1, "ash"),
    DARK("Dark", 3, "darker"),
    ONYX("Onyx", 4, "onyx");

    companion object {
        fun fromWire(value: Int): ThemeChoice? = when (value) {
            0, 1 -> ASH
            2 -> LIGHT
            3 -> DARK
            4 -> ONYX
            else -> null
        }

        fun fromNative(value: String): ThemeChoice = when (value) {
            "light" -> LIGHT
            "darker" -> DARK
            "onyx", "pureEvil" -> ONYX
            else -> ASH
        }
    }
}
