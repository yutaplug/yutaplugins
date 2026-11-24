package com.github.yutaplug.groupimages

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

class GroupImagesSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("GroupImages")

        addView(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Arrows in fullscreen",
                "Show previous and next arrows in the fullscreen viewer. Swiping still works when they're off.",
            ).apply {
                isChecked = settings.getBool(GroupImages.KEY_ARROWS, true)
                setOnCheckedListener { settings.setBool(GroupImages.KEY_ARROWS, it) }
            },
        )
    }
}
