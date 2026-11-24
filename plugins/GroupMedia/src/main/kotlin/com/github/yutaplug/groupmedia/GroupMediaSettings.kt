package com.github.yutaplug.groupmedia

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

class GroupMediaSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("GroupMedia")

        addView(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Arrows in fullscreen",
                "Show previous and next arrows in the fullscreen viewer. Swiping still works when they're off.",
            ).apply {
                isChecked = settings.getBool(GroupMedia.KEY_ARROWS, true)
                setOnCheckedListener { settings.setBool(GroupMedia.KEY_ARROWS, it) }
            },
        )
    }
}
