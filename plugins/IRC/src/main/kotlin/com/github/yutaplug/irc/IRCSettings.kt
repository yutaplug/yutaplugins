package com.github.yutaplug.irc

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class IRCSettings(private val settings: SettingsAPI, private val plugin: IRC) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        val showAvatars = Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Show avatars",
            "Show author avatars in compact chat.",
        )
        showAvatars.isChecked = settings.getBool(IRC.SHOW_AVATARS, false)
        showAvatars.setOnCheckedListener {
            settings.setBool(IRC.SHOW_AVATARS, it)
            plugin.refreshAvatarLayout()
        }
        addView(showAvatars)
    }
}
