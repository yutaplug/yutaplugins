package com.github.yutaplug.hidemodactions;

import android.content.Context;
import android.view.View;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.discord.widgets.user.profile.UserProfileAdminView;

/** Leaves only the Manage User action in the profile-sheet moderation section. */
@SuppressWarnings("unused")
@AliucordPlugin
public final class HideModActions extends Plugin {
    private static final String[] HIDDEN_ACTIONS = {
            "user_profile_admin_kick",
            "user_profile_admin_ban",
            "user_profile_admin_disable_communication",
            "user_profile_admin_server_mute",
            "user_profile_admin_server_deafen",
            "user_profile_admin_server_move",
            "user_profile_admin_server_disconnect"
    };

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
                UserProfileAdminView.class,
                "updateView",
                new Class<?>[]{UserProfileAdminView.ViewState.class},
                new Hook(frame -> {
                    UserProfileAdminView adminView = (UserProfileAdminView) frame.thisObject;
                    for (String action : HIDDEN_ACTIONS) {
                        hideView(adminView, action);
                    }
                })
        );
    }

    private static void hideView(View root, String resourceName) {
        int id = Utils.getResId(resourceName, "id");
        if (id == 0) return;

        View view = root.findViewById(id);
        if (view != null) view.setVisibility(View.GONE);
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}
