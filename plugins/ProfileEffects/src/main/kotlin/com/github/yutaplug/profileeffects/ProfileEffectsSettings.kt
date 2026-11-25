package com.github.yutaplug.profileeffects

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

internal class Prefs(private val settings: SettingsAPI) {
    val effects get() = settings.getBool(EFFECTS, true)
    val frames get() = settings.getBool(FRAMES, true)
    val themes get() = settings.getBool(THEMES, true)
    val roleGradients get() = settings.getBool(ROLE_GRADIENTS, true)
    val nameFonts get() = settings.getBool(NAME_FONTS, true)
    val nameColors get() = settings.getBool(NAME_COLORS, true)
    val nameColorsInLists get() = settings.getBool(NAME_COLORS_IN_LISTS, true)

    companion object {
        const val EFFECTS = "effects"
        const val FRAMES = "frames"
        const val THEMES = "themes"
        const val ROLE_GRADIENTS = "roleGradients"
        const val NAME_FONTS = "nameFonts"
        const val NAME_COLORS = "nameColors"
        const val NAME_COLORS_IN_LISTS = "nameColorsInLists"
    }
}

class ProfileEffectsSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("ProfileEffects")
        toggle(Prefs.EFFECTS, "Profile effects", "Show animated profile effects on profiles.")
        toggle(Prefs.FRAMES, "Profile frames", "Show profile frames around profiles.")
        toggle(Prefs.THEMES, "Profile themes", "Color profiles with the gradient their owner picked.")
        toggle(
            Prefs.ROLE_GRADIENTS,
            "Role gradients",
            "Show gradient role colors on names in chat and the member list.",
        )
        toggle(
            Prefs.NAME_FONTS,
            "Display name fonts",
            "Show the font people picked for their display name. Fonts are downloaded the first time they're needed.",
        )
        toggle(
            Prefs.NAME_COLORS,
            "Display name colors",
            "Show display name colors and effects (gradient, neon, toon, pop, glow).",
        )
        toggle(
            Prefs.NAME_COLORS_IN_LISTS,
            "Display name colors in lists and DMs",
            "Show display name colors in the friend list, the DM list and DMs. When off, those names keep the normal text color but still use their font. Profiles always show colors.",
        )
    }

    private fun toggle(key: String, title: String, subtitle: String) {
        addView(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = settings.getBool(key, true)
                setOnCheckedListener {
                    settings.setBool(key, it)
                    ProfileEffects.settingsChanged()
                }
            },
        )
    }
}
