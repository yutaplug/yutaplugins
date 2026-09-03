package xyz.wingio.plugins;

import android.os.Bundle;
import android.view.View;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.views.CheckedSetting;

/** Settings for independently hiding call actions from each Discord surface. */
public class HideCallButtonsSettings extends BottomSheet {
    private final SettingsAPI settings;

    public HideCallButtonsSettings(SettingsAPI settings) {
        this.settings = settings;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        getLinearLayout().setPadding(20, 20, 20, 20);

        addSetting(HideCallButtons.HIDE_DM_TOPBAR,
                "Hide DM top-bar call buttons",
                "Hides the voice and video call buttons in direct-message headers.");
        addSetting(HideCallButtons.HIDE_DM_MEMBER_LIST,
                "Hide DM member-list call buttons",
                "Hides the voice and video call buttons in the direct-message member list.");
        addSetting(HideCallButtons.HIDE_PROFILE_SHEET,
                "Hide profile-sheet call buttons",
                "Hides the voice and video call buttons in user profile sheets.");
        addSetting(HideCallButtons.HIDE_FRIEND_LIST,
                "Hide friend-list call button",
                "Hides the call button shown beside friends in the Friends list.");
    }

    private void addSetting(String key, String title, String subtitle) {
        CheckedSetting setting = Utils.createCheckedSetting(
                requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle);
        setting.setChecked(settings.getBool(key, false));
        setting.setOnCheckedListener(value -> settings.setBool(key, value));
        addView(setting);
    }
}
