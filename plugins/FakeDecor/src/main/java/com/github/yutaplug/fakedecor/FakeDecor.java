package com.github.yutaplug.fakedecor;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.content.ClipboardManager;
import android.content.ClipData;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.coreplugins.decorations.avatar.AvatarDecorator;
import com.aliucord.coreplugins.decorations.avatar.AvatarSticker;
import com.discord.api.channel.Channel;
import com.discord.api.user.AvatarDecoration;
import com.discord.models.member.GuildMember;
import com.discord.models.user.User;
import com.discord.stores.StoreStream;
import com.discord.utilities.icon.IconUtils;
import com.discord.utilities.stickers.StickerUtils;
import com.discord.views.sticker.StickerView;
import com.facebook.drawee.view.SimpleDraweeView;
import com.discord.widgets.channels.list.items.ChannelListItemPrivate;
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter;
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.MessageEntry;
import com.discord.widgets.user.profile.UserProfileHeaderView;
import com.discord.widgets.user.profile.UserProfileHeaderViewModel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.reflect.KProperty;

@SuppressWarnings({"unused", "unchecked"})
@AliucordPlugin
public final class FakeDecor extends Plugin {
    static final String API_URL = "https://decor.fieryflames.dev/api";
    static final String CDN_URL = "https://ugc.decor.fieryflames.dev";
    static final String AUTHORIZE_URL = API_URL + "/authorize";
    static final String DISCORD_OAUTH_CLIENT_ID = "1096966363416899624";
    static final long DECOR_SKU_ID = 100101099111114L;
    private static final String API_TOKENS_KEY = "apiTokens";
    private static final String LEGACY_API_TOKEN_KEY = "apiToken";

    private final Map<Long, String> userAssets = Collections.synchronizedMap(new HashMap<>());
    private final Set<Long> fetchedUsers = ConcurrentHashMap.newKeySet();
    private int decorationViewId;
    private int profileDecorationViewId;

    public FakeDecor() {
        settingsTab = new SettingsTab(FakeDecorSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings, this);
    }

    @Override
    public void start(Context context) throws Throwable {
        decorationViewId = avatarDecorationViewId();
        profileDecorationViewId = View.generateViewId();

        // 126.021 does not have the fields that Aliucord's newer core bridge reads.
        // Missing optional compatibility fields should behave like null instead of
        // preventing the core plugin (and this plugin) from starting.
        patchMissingOptionalFields();
        patchCoreConfiguration();
        patchAnimatedDecorationUrl();

        long currentUserId = currentUserId();
        getApiToken();
        String selected = settings.getString("selectedAsset", "");
        if (currentUserId != 0 && !selected.isEmpty()) userAssets.put(currentUserId, selected);
    }

    private void patchMissingOptionalFields() throws Throwable {
        Method getter = com.aliucord.utils.FieldAccessor.class.getDeclaredMethod(
                "getValue", Object.class, KProperty.class);
        patcher.patch(getter, new Hook(param -> {
            Object target = param.args[0];
            KProperty<?> property = (KProperty<?>) param.args[1];
            if (target != null && !hasField(target.getClass(), property.getName())) {
                param.setResult(null);
            }
        }));

        Method setter = com.aliucord.utils.FieldAccessor.class.getDeclaredMethod(
                "setValue", Object.class, KProperty.class, Object.class);
        patcher.patch(setter, new Hook(param -> {
            Object target = param.args[0];
            KProperty<?> property = (KProperty<?>) param.args[1];
            if (target != null && !hasField(target.getClass(), property.getName())) {
                param.setResult(null);
            }
        }));
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                current.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // Continue through the class hierarchy.
            }
        }
        return false;
    }

    private void patchCoreConfiguration() throws Throwable {
        patcher.patch(
                AvatarDecorator.class.getDeclaredMethod("onProfileHeaderInit", UserProfileHeaderView.class),
                new Hook(param -> ensureProfileDecoration((UserProfileHeaderView) param.args[0]))
        );

        // The core dispatcher normally forwards updateViewState to the decorator. Keep a
        // direct hook as well because profile settings can host the same header without
        // going through the dispatcher on some client builds.
        patcher.patch(
                UserProfileHeaderView.class.getDeclaredMethod(
                        "updateViewState", UserProfileHeaderViewModel.ViewState.Loaded.class),
                new Hook(param -> {
                    UserProfileHeaderView view = (UserProfileHeaderView) param.thisObject;
                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) param.args[0];
                    ensureProfileDecoration(view);
                    renderFromId(view, state.getUser().getId());
                })
        );

        patcher.patch(
                AvatarDecorator.class.getDeclaredMethod(
                        "onDMsListConfigure",
                        com.discord.widgets.channels.list.WidgetChannelsListAdapter.ItemChannelPrivate.class,
                        ChannelListItemPrivate.class),
                new Hook(param -> {
                    com.discord.widgets.channels.list.WidgetChannelsListAdapter.ItemChannelPrivate holder =
                            (com.discord.widgets.channels.list.WidgetChannelsListAdapter.ItemChannelPrivate) param.args[0];
                    ChannelListItemPrivate item = (ChannelListItemPrivate) param.args[1];
                    User user = com.aliucord.utils.ChannelUtils.getDMRecipient(item.getChannel());
                    renderFromId(bindingRoot(holder),
                            user == null ? 0 : user.getId());
                    param.setResult(null);
                })
        );

        patcher.patch(
                AvatarDecorator.class.getDeclaredMethod(
                        "onMembersListConfigure", ChannelMembersListViewHolderMember.class,
                        ChannelMembersListAdapter.Item.Member.class,
                        ChannelMembersListAdapter.class),
                new Hook(param -> {
                    ChannelMembersListViewHolderMember holder =
                            (ChannelMembersListViewHolderMember) param.args[0];
                    ChannelMembersListAdapter.Item.Member item =
                            (ChannelMembersListAdapter.Item.Member) param.args[1];
                    renderFromId(bindingRoot(holder), item.getUserId());
                    param.setResult(null);
                })
        );

        patcher.patch(
                AvatarDecorator.class.getDeclaredMethod(
                        "onMessageConfigure", WidgetChatListAdapterItemMessage.class, MessageEntry.class),
                new Hook(param -> {
                    WidgetChatListAdapterItemMessage holder =
                            (WidgetChatListAdapterItemMessage) param.args[0];
                    MessageEntry entry = (MessageEntry) param.args[1];
                    long id = 0;
                    GuildMember author = entry.getAuthor();
                    if (author != null) id = author.getUserId();
                    if (id == 0 && entry.getMessage() != null && entry.getMessage().getAuthor() != null) {
                        id = entry.getMessage().getAuthor().getId();
                    }
                    renderFromId(holder.itemView, id);
                    param.setResult(null);
                })
        );

        patcher.patch(
                AvatarDecorator.class.getDeclaredMethod(
                        "onProfileHeaderConfigure", UserProfileHeaderView.class,
                        UserProfileHeaderViewModel.ViewState.Loaded.class),
                new Hook(param -> {
                    UserProfileHeaderView view = (UserProfileHeaderView) param.args[0];
                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) param.args[1];
                    long id = state.getUser().getId();
                    ensureProfileDecoration(view);
                    renderFromId(view, id);
                    param.setResult(null);
                })
        );
    }

    private void patchAnimatedDecorationUrl() throws Throwable {
        Method method = StickerUtils.class.getDeclaredMethod(
                "getCDNAssetUrl", com.discord.api.sticker.BaseSticker.class,
                Integer.class, boolean.class);
        patcher.patch(method, new Hook(param -> {
            if (!(param.args[0] instanceof AvatarSticker)) return;
            AvatarDecoration data = ((AvatarSticker) param.args[0]).getData();
            param.setResult(assetUrl(data.getAsset(), true));
        }));
    }

    private static int avatarDecorationViewId() {
        try {
            Method method = Class.forName(
                    "com.aliucord.coreplugins.decorations.avatar.AvatarDecoratorKt"
            ).getDeclaredMethod("access$getDecoId$p");
            return (Integer) method.invoke(null);
        } catch (Throwable error) {
            return View.generateViewId();
        }
    }

    private static View bindingRoot(Object holder) {
        try {
            Field binding = holder.getClass().getDeclaredField("binding");
            binding.setAccessible(true);
            Object value = binding.get(holder);
            Method getRoot = value.getClass().getMethod("getRoot");
            return (View) getRoot.invoke(value);
        } catch (Throwable error) {
            return holder instanceof View ? (View) holder : null;
        }
    }

    private void renderFromId(View parent, long userId) {
        if (parent == null || userId == 0) {
            hideDecoration(parent);
            return;
        }

        String asset;
        synchronized (userAssets) {
            asset = userAssets.get(userId);
        }

        long current = currentUserId();
        if (userId == current) {
            asset = settings.getString("selectedAsset", "");
            if (asset.isEmpty()) asset = null;
            if (asset == null) userAssets.remove(userId);
            else userAssets.put(userId, asset);
        } else if (!fetchedUsers.contains(userId)) {
            fetchUserDecoration(userId, parent);
            hideDecoration(parent);
            return;
        }

        configureDecoration(parent, asset);
    }

    private void fetchUserDecoration(long userId, View parent) {
        if (!fetchedUsers.add(userId)) return;
        Utils.threadPool.execute(() -> {
            String asset = null;
            try {
                String ids = URLEncoder.encode("[\"" + userId + "\"]", "UTF-8");
                String body = Http.simpleGet(API_URL + "/users?ids=" + ids);
                Map<?, ?> response = com.aliucord.utils.GsonUtils.fromJson(body, Map.class);
                Object value = response.get(String.valueOf(userId));
                if (value != null) asset = String.valueOf(value);
            } catch (Throwable error) {
                fetchedUsers.remove(userId);
                logger.error("Failed to fetch Decor decoration for " + userId, error);
            }

            final String result = asset;
            if (result != null) userAssets.put(userId, result);
            if (parent != null) {
                parent.post(() -> configureDecoration(parent, result));
            }
        });
    }

    void setSelectedAsset(String asset) {
        long userId = currentUserId();
        if (asset == null || asset.isEmpty()) {
            settings.remove("selectedAsset");
            if (userId != 0) userAssets.remove(userId);
        } else {
            settings.setString("selectedAsset", normalizeAsset(asset));
            if (userId != 0) userAssets.put(userId, normalizeAsset(asset));
        }
    }

    String getSelectedAsset() {
        return settings.getString("selectedAsset", "");
    }

    String getApiToken() {
        long userId = currentUserId();
        if (userId == 0) return "";

        Map<String, String> tokens = readApiTokens();
        String token = tokens.get(String.valueOf(userId));
        if (token != null && !token.isEmpty()) return token;

        // Migrate the old single-token setting to the current account once.
        String legacy = settings.getString(LEGACY_API_TOKEN_KEY, "").trim();
        if (!legacy.isEmpty()) {
            tokens.put(String.valueOf(userId), legacy);
            saveApiTokens(tokens);
            settings.remove(LEGACY_API_TOKEN_KEY);
            return legacy;
        }
        return "";
    }

    void setApiToken(String token) {
        long userId = currentUserId();
        if (userId == 0) return;

        Map<String, String> tokens = readApiTokens();
        String key = String.valueOf(userId);
        if (token == null || token.trim().isEmpty()) tokens.remove(key);
        else tokens.put(key, token.trim());
        saveApiTokens(tokens);
        settings.remove(LEGACY_API_TOKEN_KEY);
    }

    boolean isAuthorized() {
        return !getApiToken().isEmpty();
    }

    void disconnectDecor() {
        setApiToken(null);
        Utils.showToast("Decor authorization removed");
    }

    void authorizeDecor(Context context, FakeDecorSettings settingsView) {
        Uri oauthUrl = discordOAuthUrl();
        try {
            context.startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, oauthUrl));
            Utils.showToast("Authorize in the system browser, then copy the returned token");
        } catch (Throwable error) {
            logger.error("Failed to open Decor authorization in browser", error);
            Utils.showToast("Could not open browser for Decor authorization");
        }
    }

    void finishBrowserAuthorization(Context context, FakeDecorSettings settingsView) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Utils.showToast("Copy the returned Decor token first");
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Utils.showToast("Copy the returned Decor token first");
            return;
        }
        CharSequence copied = clip.getItemAt(0).coerceToText(context);
        if (copied == null || copied.toString().trim().isEmpty()) {
            Utils.showToast("Copy the returned Decor token first");
            return;
        }
        validateAndSaveToken(copied.toString().trim(), settingsView);
    }

    private static Uri discordOAuthUrl() {
        return Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", DISCORD_OAUTH_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", AUTHORIZE_URL)
                .appendQueryParameter("scope", "identify")
                .appendQueryParameter("permissions", "0")
                .build();
    }

    private void validateAndSaveToken(String candidate, FakeDecorSettings settingsView) {
        Utils.threadPool.execute(() -> {
            // The singular endpoint is readable without authentication. The plural
            // endpoint requires a valid Decor token and is therefore used for validation.
            try (Http.Request request = new Http.Request(API_URL + "/users/@me/decorations")) {
                request.setHeader("Authorization", "Bearer " + candidate);
                Http.Response response = request.execute();
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);

                setApiToken(candidate);
                Utils.mainThread.post(() -> {
                    if (settingsView != null) settingsView.refreshAuthState();
                    Utils.showToast("Decor authorization saved");
                });
            } catch (Throwable error) {
                logger.error("Failed to validate Decor authorization", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not authorize Decor"));
            }
        });
    }

    private Map<String, String> readApiTokens() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Map<?, ?> stored = com.aliucord.utils.GsonUtils.fromJson(
                    settings.getString(API_TOKENS_KEY, "{}"), Map.class);
            if (stored != null) {
                for (Map.Entry<?, ?> entry : stored.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }
        } catch (Throwable ignored) {
            // A malformed token map is treated as empty and replaced on the next save.
        }
        return result;
    }

    private void saveApiTokens(Map<String, String> tokens) {
        settings.setString(API_TOKENS_KEY, com.aliucord.utils.GsonUtils.toJson(tokens));
    }

    void refreshOwnDecoration() {
        String token = getApiToken();
        if (token.isEmpty()) {
            Utils.showToast("Authorize Decor first");
            return;
        }
        Utils.threadPool.execute(() -> {
            try (Http.Request request = new Http.Request(API_URL + "/users/@me/decoration")) {
                request.setHeader("Authorization", "Bearer " + token);
                Http.Response response = request.execute();
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                Map<?, ?> decoration = com.aliucord.utils.GsonUtils.fromJson(response.text(), Map.class);
                String asset = "";
                if (decoration != null) {
                    String hash = String.valueOf(decoration.get("hash"));
                    boolean animated = Boolean.TRUE.equals(decoration.get("animated"));
                    asset = "null".equals(hash) ? "" : (animated ? "a_" : "") + hash;
                }
                setSelectedAsset(asset);
                final String syncedAsset = asset;
                Utils.mainThread.post(() -> Utils.showToast(syncedAsset.isEmpty()
                        ? "Decor decoration removed" : "Decor decoration synced"));
            } catch (Throwable error) {
                logger.error("Failed to sync Decor decoration", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not sync Decor decoration"));
            }
        });
    }

    void uploadDecoration(Context context, Uri uri) {
        String token = getApiToken();
        if (token.isEmpty()) {
            Utils.showToast("Authorize Decor before uploading");
            return;
        }
        Utils.threadPool.execute(() -> {
            try (InputStream image = context.getContentResolver().openInputStream(uri);
                 Http.Request request = new Http.Request(API_URL + "/users/@me/decoration", "PUT")) {
                if (image == null) throw new IllegalStateException("Could not open selected image");
                request.setHeader("Authorization", "Bearer " + token);
                Map<String, Object> form = new HashMap<>();
                form.put("image", image);
                form.put("alt", "Custom mobile decoration");
                Http.Response response = request.executeWithMultipartForm(form);
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                Map<?, ?> decoration = com.aliucord.utils.GsonUtils.fromJson(response.text(), Map.class);
                String hash = String.valueOf(decoration.get("hash"));
                boolean animated = Boolean.TRUE.equals(decoration.get("animated"));
                setSelectedAsset((animated ? "a_" : "") + hash);
                Utils.mainThread.post(() -> Utils.showToast("Custom decoration uploaded and applied"));
            } catch (Throwable error) {
                logger.error("Failed to upload Decor decoration", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not upload custom decoration"));
            }
        });
    }

    void applyToDecorService() {
        String token = getApiToken();
        if (token.isEmpty()) {
            Utils.showToast("Saved locally; authorize Decor to sync it");
            return;
        }
        String selected = getSelectedAsset();
        Utils.threadPool.execute(() -> {
            try (Http.Request request = new Http.Request(API_URL + "/users/@me/decoration", "PUT")) {
                request.setHeader("Authorization", "Bearer " + token);
                Map<String, Object> form = new HashMap<>();
                form.put("hash", selected.isEmpty() ? "null" : selected.substring(selected.startsWith("a_") ? 2 : 0));
                Http.Response response = request.executeWithMultipartForm(form);
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                Utils.mainThread.post(() -> Utils.showToast("Decor decoration synced"));
            } catch (Throwable error) {
                logger.error("Failed to apply Decor decoration", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not sync Decor decoration"));
            }
        });
    }

    void fetchPresets(FakeDecorSettings settingsView) {
        Utils.threadPool.execute(() -> {
            try {
                String body = Http.simpleGet(API_URL + "/decorations/presets");
                List<?> presets = com.aliucord.utils.GsonUtils.fromJson(body, List.class);
                Utils.mainThread.post(() -> settingsView.showPresets(presets));
            } catch (Throwable error) {
                logger.error("Failed to fetch Decor presets", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not load Decor presets"));
            }
        });
    }

    private void configureDecoration(View parent, String asset) {
        if (parent == null) return;
        if (parent instanceof UserProfileHeaderView) {
            configureProfileDecoration((UserProfileHeaderView) parent, asset);
            return;
        }
        View decoration = parent.findViewById(decorationViewId);
        if (decoration == null) return;
        if (asset == null || asset.isEmpty()) {
            decoration.setVisibility(View.INVISIBLE);
            return;
        }
        decoration.setVisibility(View.VISIBLE);
        if (decoration instanceof StickerView) {
            ((StickerView) decoration).d(
                    new AvatarSticker(new AvatarDecoration(normalizeAsset(asset), DECOR_SKU_ID)), null);
        } else if (decoration instanceof ImageView) {
            IconUtils.setIcon((ImageView) decoration, assetUrl(asset, false));
        }
    }

    private void ensureProfileDecoration(UserProfileHeaderView profile) {
        if (profile == null) return;

        View coreDecoration = profile.findViewById(decorationViewId);
        if (coreDecoration != null) coreDecoration.setVisibility(View.INVISIBLE);

        View current = profile.findViewById(profileDecorationViewId);
        if (current != null) return;

        View container = profile.findViewById(Utils.getResId("avatar_container", "id"));
        if (!(container instanceof FrameLayout)) return;

        SimpleDraweeView decoration = new SimpleDraweeView(profile.getContext());
        decoration.setId(profileDecorationViewId);
        decoration.setScaleType(ImageView.ScaleType.FIT_XY);
        decoration.setClickable(false);
        decoration.setFocusable(false);
        int avatarSize = profile.getResources().getDimensionPixelSize(
                Utils.getResId("avatar_wrap_size_xxlarge", "dimen"));
        int spacing = (int) (8f * profile.getResources().getDisplayMetrics().density + 0.5f);
        int decorationSize = avatarSize + spacing;

        // Match AvatarDecorator.onProfileHeaderInit: the header avatar itself must be
        // enlarged before the decoration can be laid over it.
        View largeAvatar = profile.findViewById(Utils.getResId("large_avatar", "id"));
        if (largeAvatar != null && largeAvatar.getLayoutParams() != null) {
            largeAvatar.getLayoutParams().width = decorationSize;
            largeAvatar.getLayoutParams().height = decorationSize;
            largeAvatar.requestLayout();
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                decorationSize,
                decorationSize,
                Gravity.CENTER);
        ((FrameLayout) container).addView(decoration, params);
    }

    private void configureProfileDecoration(UserProfileHeaderView profile, String asset) {
        ensureProfileDecoration(profile);
        View decoration = profile.findViewById(profileDecorationViewId);
        if (!(decoration instanceof ImageView)) return;
        if (asset == null || asset.isEmpty()) {
            decoration.setVisibility(View.INVISIBLE);
            return;
        }
        decoration.setVisibility(View.VISIBLE);
        IconUtils.setIcon((ImageView) decoration, assetUrl(asset, false));
    }

    private void hideDecoration(View parent) {
        if (parent == null) return;
        if (parent instanceof UserProfileHeaderView) {
            View profileDecoration = parent.findViewById(profileDecorationViewId);
            if (profileDecoration != null) profileDecoration.setVisibility(View.INVISIBLE);
            return;
        }
        View decoration = parent.findViewById(decorationViewId);
        if (decoration != null) decoration.setVisibility(View.INVISIBLE);
    }

    private static String normalizeAsset(String value) {
        String asset = value.trim();
        int query = asset.indexOf('?');
        if (query >= 0) asset = asset.substring(0, query);
        int slash = asset.lastIndexOf('/');
        if (slash >= 0) asset = asset.substring(slash + 1);
        if (asset.endsWith(".png")) asset = asset.substring(0, asset.length() - 4);
        return asset;
    }

    private static String assetUrl(String asset, boolean canAnimate) {
        String normalized = normalizeAsset(asset);
        if (!canAnimate && normalized.startsWith("a_")) normalized = normalized.substring(2);
        return CDN_URL + "/" + normalized + ".png";
    }

    private static long currentUserId() {
        try {
            return StoreStream.getUsers().getMe().getId();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        userAssets.clear();
        fetchedUsers.clear();
    }
}
