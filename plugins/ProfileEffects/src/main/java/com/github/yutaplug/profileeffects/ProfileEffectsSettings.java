package com.github.yutaplug.profileeffects;

import android.os.Bundle;
import android.view.View;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.views.CheckedSetting;

/** Settings for choosing the profile-effect renderer. */
public final class ProfileEffectsSettings extends BottomSheet {
    private final SettingsAPI settings;

    public ProfileEffectsSettings(SettingsAPI settings) {
        this.settings = settings;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        getLinearLayout().setPadding(20, 20, 20, 20);

        CheckedSetting setting = Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Use WebView for profile effects",
                "Use the older renderer on devices where native APNG effects freeze or crash.");
        setting.setChecked(settings.getBool(ProfileEffects.USE_WEBVIEW, false));
        setting.setOnCheckedListener(value -> settings.setBool(ProfileEffects.USE_WEBVIEW, value));
        addView(setting);
    }
}
