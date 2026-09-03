package xyz.wingio.plugins;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.discord.databinding.WidgetHomeBinding;
import com.discord.views.channelsidebar.PrivateChannelSideBarActionsView;
import com.discord.widgets.home.WidgetHome;
import com.discord.widgets.home.WidgetHomeHeaderManager;
import com.discord.widgets.home.WidgetHomeModel;
import com.discord.widgets.user.usersheet.WidgetUserSheet;
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
@AliucordPlugin
public class HideCallButtons extends Plugin {
    @Override
    public void start(Context context) throws Throwable {

        final int videoId = Utils.getResId("user_sheet_video_action_button", "id");
        final int callId = Utils.getResId("user_sheet_call_action_button", "id");

        patcher.patch(WidgetUserSheet.class, "configureNote", new Class<?>[]{ WidgetUserSheetViewModel.ViewState.Loaded.class }, new Hook(callFrame -> {
            var binding = WidgetUserSheet.access$getBinding$p((WidgetUserSheet) callFrame.thisObject);
            var root = binding.getRoot();

            hideView(root, videoId);
            hideView(root, callId);
        }));

        final int topbarCallId = Utils.getResId("menu_chat_start_call", "id");
        final int topbarVideoId = Utils.getResId("menu_chat_start_video_call", "id");
        Method configure = WidgetHomeHeaderManager.class.getDeclaredMethod(
                "configure", WidgetHome.class, WidgetHomeModel.class, WidgetHomeBinding.class);
        patcher.patch(configure, new Hook(callFrame -> {
            var home = (WidgetHome) callFrame.args[0];
            var toolbar = home.getToolbar();
            if (toolbar == null) return;

            hideMenuItem(toolbar.getMenu(), topbarCallId);
            hideMenuItem(toolbar.getMenu(), topbarVideoId);
        }));

        Constructor<PrivateChannelSideBarActionsView> constructor =
                PrivateChannelSideBarActionsView.class.getDeclaredConstructor(Context.class, AttributeSet.class);
        patcher.patch(constructor, new Hook(callFrame -> {
            var actions = (PrivateChannelSideBarActionsView) callFrame.thisObject;
            hideView(actions, "private_channel_sidebar_actions_call");
            hideView(actions, "private_channel_sidebar_actions_video");
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
    }
}
