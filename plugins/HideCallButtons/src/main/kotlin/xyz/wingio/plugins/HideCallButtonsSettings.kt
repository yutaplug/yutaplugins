package xyz.wingio.plugins

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class HideCallButtonsSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        addSetting(
            HideCallButtons.HIDE_DM_TOPBAR,
            "Hide DM top-bar call buttons",
            "Hides the voice and video call buttons in direct-message headers.",
        )
        addSetting(
            HideCallButtons.HIDE_DM_MEMBER_LIST,
            "Hide DM member-list call buttons",
            "Hides the voice and video call buttons in the direct-message member list.",
        )
        addSetting(
            HideCallButtons.HIDE_PROFILE_SHEET,
            "Hide profile-sheet call buttons",
            "Hides the voice and video call buttons in user profile sheets.",
        )
        addSetting(
            HideCallButtons.HIDE_FRIEND_LIST,
            "Hide friend-list call button",
            "Hides the call button shown beside friends in the Friends list.",
        )
        addSetting(
            HideCallButtons.HIDE_VC_CAMERA,
            "Hide VC camera button",
            "Hides the camera button inside voice-channel controls.",
        )
    }

    private fun addSetting(key: String, title: String, subtitle: String) {
        addView(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = settings.getBool(key, false)
                setOnCheckedListener { settings.setBool(key, it) }
            },
        )
    }
}
