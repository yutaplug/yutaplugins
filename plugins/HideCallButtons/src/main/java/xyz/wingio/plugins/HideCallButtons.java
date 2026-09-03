package xyz.wingio.plugins;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.aliucord.Utils;
import com.aliucord.api.CommandsAPI;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.entities.Plugin.SettingsTab;
import com.aliucord.patcher.Hook;
import com.discord.databinding.WidgetHomeBinding;
import com.discord.views.channelsidebar.PrivateChannelSideBarActionsView;
import com.discord.widgets.friends.WidgetFriendsListAdapter;
import com.discord.widgets.home.WidgetHome;
import com.discord.widgets.home.WidgetHomeHeaderManager;
import com.discord.widgets.home.WidgetHomeModel;
import com.discord.widgets.user.calls.PrivateCallLauncher;
import com.discord.widgets.user.usersheet.WidgetUserSheet;
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;

@SuppressWarnings("unused")
@AliucordPlugin
public class HideCallButtons extends Plugin {
    static final String HIDE_DM_TOPBAR = "hideDmTopbar";
    static final String HIDE_DM_MEMBER_LIST = "hideDmMemberList";
    static final String HIDE_PROFILE_SHEET = "hideProfileSheet";
    static final String HIDE_FRIEND_LIST = "hideFriendList";
    private WeakReference<WidgetHome> currentHome;

    @Override
    public void start(Context context) throws Throwable {
        settingsTab = new SettingsTab(HideCallButtonsSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings);
        commands.registerCommand("call", "Start a voice call with this DM recipient", commandContext -> {
            var channel = commandContext.getCurrentChannel();
            if (channel == null || !channel.isDM()) {
                return new CommandsAPI.CommandResult("This command can only be used in a direct message.");
            }

            var homeReference = currentHome;
            var home = homeReference == null ? null : homeReference.get();
            if (home == null || !home.isAdded()) {
                return new CommandsAPI.CommandResult("Unable to start a call from the current screen.");
            }

            long channelId = channel.getId();
            Context callContext = commandContext.getContext();
            Utils.mainThread.post(() -> {
                try {
                    if (home.isAdded()) {
                        new PrivateCallLauncher(home, home, callContext,
                                home.getParentFragmentManager()).launchVoiceCall(channelId);
                    }
                } catch (Throwable ignored) {
                    // The current screen may close while the command is being dispatched.
                }
            });
            return null;
        });

        final int videoId = Utils.getResId("user_sheet_video_action_button", "id");
        final int callId = Utils.getResId("user_sheet_call_action_button", "id");

        patcher.patch(WidgetUserSheet.class, "configureNote", new Class<?>[]{ WidgetUserSheetViewModel.ViewState.Loaded.class }, new Hook(callFrame -> {
            var binding = WidgetUserSheet.access$getBinding$p((WidgetUserSheet) callFrame.thisObject);
            var root = binding.getRoot();

            if (settings.getBool(HIDE_PROFILE_SHEET, false)) {
                hideView(root, videoId);
                hideView(root, callId);
            }
        }));

        final int topbarCallId = Utils.getResId("menu_chat_start_call", "id");
        final int topbarVideoId = Utils.getResId("menu_chat_start_video_call", "id");
        Method configure = WidgetHomeHeaderManager.class.getDeclaredMethod(
                "configure", WidgetHome.class, WidgetHomeModel.class, WidgetHomeBinding.class);
        patcher.patch(configure, new Hook(callFrame -> {
            var home = (WidgetHome) callFrame.args[0];
            currentHome = new WeakReference<>(home);
            var toolbar = home.getToolbar();
            if (toolbar == null) return;

            if (settings.getBool(HIDE_DM_TOPBAR, false)) {
                hideMenuItem(toolbar.getMenu(), topbarCallId);
                hideMenuItem(toolbar.getMenu(), topbarVideoId);
            }
        }));

        Constructor<PrivateChannelSideBarActionsView> constructor =
                PrivateChannelSideBarActionsView.class.getDeclaredConstructor(Context.class, AttributeSet.class);
        patcher.patch(constructor, new Hook(callFrame -> {
            var actions = (PrivateChannelSideBarActionsView) callFrame.thisObject;
            if (settings.getBool(HIDE_DM_MEMBER_LIST, false)) {
                hideView(actions, "private_channel_sidebar_actions_call");
                hideView(actions, "private_channel_sidebar_actions_video");
            }
        }));

        Constructor<WidgetFriendsListAdapter.ItemUser> friendItemConstructor =
                WidgetFriendsListAdapter.ItemUser.class.getDeclaredConstructor(WidgetFriendsListAdapter.class);
        patcher.patch(friendItemConstructor, new Hook(callFrame -> {
            if (settings.getBool(HIDE_FRIEND_LIST, false)) {
                var item = (WidgetFriendsListAdapter.ItemUser) callFrame.thisObject;
                hideView(item.itemView, "friends_list_item_call_button");
            }
        }));
    }

    private static void hideMenuItem(Menu menu, int id) {
        if (menu == null || id == 0) return;
        MenuItem item = menu.findItem(id);
        if (item != null) item.setVisible(false);
    }

    private static void hideView(View root, String resourceName) {
        int id = Utils.getResId(resourceName, "id");
        hideView(root, id);
    }

    private static void hideView(View root, int id) {
        if (id == 0) return;
        View view = root.findViewById(id);
        if (view != null) view.setVisibility(View.GONE);
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        commands.unregisterAll();
        currentHome = null;
    }
}
