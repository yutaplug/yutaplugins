package com.github.yutaplug.recentprofilepictures;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import b.a.y.b0;
import b.a.y.c0;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.GsonUtils;
import com.discord.widgets.settings.profile.SettingsUserProfileViewModel;
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
@AliucordPlugin
public class RecentProfilePictures extends Plugin {
    private static final int MAX_RECENT_AVATARS = 6;

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
                WidgetEditUserOrGuildMemberProfile.class,
                "configureAvatarSelect",
                new Class<?>[]{SettingsUserProfileViewModel.ViewState.Loaded.class},
                new Hook(frame -> {
                    WidgetEditUserOrGuildMemberProfile editor =
                            (WidgetEditUserOrGuildMemberProfile) frame.thisObject;
                    SettingsUserProfileViewModel.ViewState.Loaded state =
                            (SettingsUserProfileViewModel.ViewState.Loaded) frame.args[0];

                    // Keep per-server profile editing on Discord's original path.
                    if (state.getGuild() != null) return;

                    WidgetEditUserOrGuildMemberProfile.access$getBinding$p(editor).o.setOnAvatarEdit(() -> {
                        showAvatarContextMenu(editor, state);
                        return Unit.a;
                    });
                })
        );
    }

    private void showAvatarContextMenu(WidgetEditUserOrGuildMemberProfile editor,
                                       SettingsUserProfileViewModel.ViewState.Loaded state) {
        List<c0> options = new ArrayList<>();
        options.add(new c0(editor.getString(Utils.getResId("user_settings_change_avatar", "string")),
                null, null, null, null, null, null, 116));
        options.add(new c0("Recent avatars", null, null, null, null, null, null, 116));
        if (state.getHasAvatarForDisplay()) {
            options.add(new c0(editor.getString(Utils.getResId("user_settings_remove_avatar", "string")),
                    null, null, null, null, null, null, 84));
        }

        b0.k.a(editor.getChildFragmentManager(), "", options, false,
                new Function1<Integer, Unit>() {
                    @Override
                    public Unit invoke(Integer selected) {
                        if (selected == 0) {
                            WidgetEditUserOrGuildMemberProfile.access$setImageSelectedResult$p(
                                    editor, WidgetEditUserOrGuildMemberProfile.access$getAvatarSelectedResult$p(editor));
                            editor.openMediaChooser();
                        } else if (selected == 1) {
                            loadRecentAvatars(editor, state);
                        } else if (state.getHasAvatarForDisplay()) {
                            WidgetEditUserOrGuildMemberProfile.access$getViewModel$p(editor).updateAvatar(null);
                        }
                        return Unit.a;
                    }
                });
    }

    private void loadRecentAvatars(WidgetEditUserOrGuildMemberProfile editor,
                                   SettingsUserProfileViewModel.ViewState.Loaded state) {
        new Thread(() -> {
            try (Http.Request request = Http.Request.newDiscordRNRequest("/users/@me/avatars")) {
                Http.Response response = request.execute();
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                Map<?, ?> body = GsonUtils.fromJson(response.text(), Map.class);
                List<RecentAvatar> avatars = parseAvatars(body);
                View editorView = editor.getView();
                if (editorView != null) {
                    editorView.postDelayed(() -> {
                        if (editor.isAdded()) showRecentAvatarMenu(editor, state, avatars);
                    }, 300L);
                }
            } catch (Throwable error) {
                logger.error("Failed to load synced recent avatars", error);
                View editorView = editor.getView();
                if (editorView != null) {
                    editorView.post(() -> Utils.showToast("Could not load recent profile pictures"));
                }
            }
        }, "RecentProfilePictures").start();
    }

    private List<RecentAvatar> parseAvatars(Map<?, ?> body) {
        List<RecentAvatar> result = new ArrayList<>();
        Object rawAvatars = body.get("avatars");
        if (!(rawAvatars instanceof List<?>)) return result;
        for (Object raw : (List<?>) rawAvatars) {
            if (!(raw instanceof Map<?, ?>)) continue;
            Map<?, ?> object = (Map<?, ?>) raw;
            Object id = object.get("id");
            Object hash = object.get("storage_hash");
            if (id != null && hash != null && result.size() < MAX_RECENT_AVATARS) {
                result.add(new RecentAvatar(String.valueOf(id), String.valueOf(hash)));
            }
        }
        return result;
    }

    private void showRecentAvatarMenu(WidgetEditUserOrGuildMemberProfile editor,
                                      SettingsUserProfileViewModel.ViewState.Loaded state,
                                      List<RecentAvatar> avatars) {
        List<c0> options = new ArrayList<>();
        for (int i = 0; i < avatars.size(); i++) {
            RecentAvatar avatar = avatars.get(i);
            options.add(new c0("Recent avatar " + (i + 1), "Select this recent avatar",
                    null, avatar.cdnUrl(state.getUser().getId()), null, null, null, 116));
        }
        if (options.isEmpty()) {
            Utils.showToast("No recent profile pictures found");
            return;
        }

        b0.k.a(editor.getChildFragmentManager(), "Recent avatars", options, false,
                new Function1<Integer, Unit>() {
                    @Override
                    public Unit invoke(Integer selected) {
                        if (selected >= 0 && selected < avatars.size()) {
                            confirmRecentAvatar(editor, avatars.get(selected).id);
                        }
                        return Unit.a;
                    }
                });
    }

    private void confirmRecentAvatar(WidgetEditUserOrGuildMemberProfile editor, String avatarId) {
        AlertDialog dialog = new AlertDialog.Builder(editor.requireContext())
                .setTitle("Change profile picture?")
                .setMessage("Your profile picture will be changed to the selected recent avatar.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Change", (ignoredDialog, which) -> selectRecentAvatar(editor, avatarId))
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (title != null) title.setTextColor(Color.WHITE);
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) message.setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    private void selectRecentAvatar(WidgetEditUserOrGuildMemberProfile editor, String avatarId) {
        new Thread(() -> {
            try (Http.Request request = Http.Request.newDiscordRNRequest("/users/@me", "PATCH")) {
                request.setHeader("content-type", "application/json");
                Http.Response response = request.executeWithBody(
                        GsonUtils.toJson(Map.of("avatar_id", avatarId)));
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                View editorView = editor.getView();
                if (editorView != null) {
                    editorView.post(() -> {
                        if (editor.isAdded()) editor.requireActivity().onBackPressed();
                        Utils.showToast("Recent profile picture applied");
                    });
                }
            } catch (Throwable error) {
                logger.error("Failed to select synced recent avatar", error);
                View editorView = editor.getView();
                if (editorView != null) {
                    editorView.post(() -> Utils.showToast("Could not select recent profile picture"));
                }
            }
        }, "RecentProfilePicturesSelect").start();
    }

    private static final class RecentAvatar {
        private final String id;
        private final String hash;

        private RecentAvatar(String id, String hash) {
            this.id = id;
            this.hash = hash;
        }

        private String cdnUrl(long userId) {
            String extension = hash.startsWith("a_") ? "gif" : "png";
            return "https://cdn.discordapp.com/avatars/" + userId + "/" + hash + "." + extension;
        }
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}
