package com.github.yutaplug.fakedecor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.coreplugins.decorations.avatar.AvatarSticker;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.ChannelUtils;
import com.aliucord.utils.GsonUtils;
import com.discord.api.channel.Channel;
import com.discord.api.sticker.BaseSticker;
import com.discord.api.user.AvatarDecoration;
import com.discord.models.member.GuildMember;
import com.discord.models.user.User;
import com.discord.stores.StoreStream;
import com.discord.utilities.icon.IconUtils;
import com.discord.utilities.stickers.StickerUtils;
import com.discord.views.sticker.StickerView;
import com.discord.widgets.channels.list.WidgetChannelsListAdapter;
import com.discord.widgets.channels.list.items.ChannelListItem;
import com.discord.widgets.channels.list.items.ChannelListItemPrivate;
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter;
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.ChatListEntry;
import com.discord.widgets.chat.list.entries.MessageEntry;
import com.discord.widgets.user.profile.UserProfileHeaderView;
import com.discord.widgets.user.profile.UserProfileHeaderViewModel;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.jvm.functions.Function0;

@SuppressWarnings({"unused", "unchecked"})
@AliucordPlugin
public final class FakeDecor extends Plugin {
    static final String API_URL = "https://decor.fieryflames.dev/api";
    static final String CDN_URL = "https://ugc.decor.fieryflames.dev";
    static final String AUTHORIZE_URL = API_URL + "/authorize";
    static final String DISCORD_OAUTH_CLIENT_ID = "1096966363416899624";
    static final long DECOR_SKU_ID = 100101099111114L;

    private static final long DECORATION_FETCH_COOLDOWN = 4L * 60L * 60L * 1000L;
    private static final String SELECTED_ASSETS_KEY = "selectedAssets";
    private static final String LEGACY_SELECTED_ASSET_KEY = "selectedAsset";
    private static final String API_TOKENS_KEY = "apiTokens";
    private static final String LEGACY_API_TOKEN_KEY = "apiToken";
    private static final String UNLOADED = "\u0000unloaded";

    private final Map<Long, CachedDecoration> userDecorations = new ConcurrentHashMap<>();
    private final Set<Long> decorationRequests = ConcurrentHashMap.newKeySet();
    private final Map<View, Long> boundViews = Collections.synchronizedMap(new WeakHashMap<>());
    private int decorationViewId;

    public FakeDecor() {
        settingsTab = new SettingsTab(FakeDecorSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings, this);
    }

    @Override
    public void start(Context context) throws Throwable {
        decorationViewId = findDecorationViewId();

        // Aliucord's Decorations core plugin owns the frame views and layout.
        // FakeDecor only supplies the Decor asset and keeps the core renderer's
        // URL handling pointed at Decor's CDN.
        patchDecorStickerUrl();
        patchDecorationConfigureCallbacks();
    }

    private void patchDecorStickerUrl() {
        patcher.patch(
                StickerUtils.class,
                "getCDNAssetUrl",
                new Class<?>[]{BaseSticker.class, Integer.class, boolean.class},
                new PreHook(param -> {
                    if (!(param.args[0] instanceof AvatarSticker)) return;
                    AvatarDecoration data = ((AvatarSticker) param.args[0]).getData();
                    if (data != null && data.getSkuId() == DECOR_SKU_ID) {
                        param.setResult(assetUrl(data.getAsset(), true));
                    }
                })
        );
    }

    private void patchDecorationConfigureCallbacks() {
        // DM list rows.
        patcher.patch(
                WidgetChannelsListAdapter.ItemChannelPrivate.class,
                "onConfigure",
                new Class<?>[]{int.class, ChannelListItem.class},
                new Hook(param -> {
                    WidgetChannelsListAdapter.ItemChannelPrivate holder =
                            (WidgetChannelsListAdapter.ItemChannelPrivate) param.thisObject;
                    ChannelListItemPrivate item = (ChannelListItemPrivate) param.args[1];
                    User user = null;
                    if (item != null) {
                        Channel channel = item.getChannel();
                        user = channel == null ? null : ChannelUtils.getDMRecipient(channel);
                    }
                    scheduleRender(holder.itemView, user == null ? 0L : user.getId());
                })
        );

        // Server member list rows.
        patcher.patch(
                ChannelMembersListViewHolderMember.class,
                "bind",
                new Class<?>[]{ChannelMembersListAdapter.Item.Member.class, Function0.class},
                new Hook(param -> {
                    ChannelMembersListViewHolderMember holder =
                            (ChannelMembersListViewHolderMember) param.thisObject;
                    ChannelMembersListAdapter.Item.Member member =
                            (ChannelMembersListAdapter.Item.Member) param.args[0];
                    scheduleRender(holder.itemView, member == null ? 0L : member.getUserId());
                })
        );

        // Chat message avatars.
        patcher.patch(
                WidgetChatListAdapterItemMessage.class,
                "onConfigure",
                new Class<?>[]{int.class, ChatListEntry.class},
                new Hook(param -> {
                    WidgetChatListAdapterItemMessage holder =
                            (WidgetChatListAdapterItemMessage) param.thisObject;
                    MessageEntry entry = (MessageEntry) param.args[1];
                    long userId = 0L;
                    if (entry != null) {
                        GuildMember author = entry.getAuthor();
                        if (author != null) userId = author.getUserId();
                        if (userId == 0L && entry.getMessage() != null
                                && entry.getMessage().getAuthor() != null) {
                            userId = entry.getMessage().getAuthor().getId();
                        }
                    }
                    scheduleRender(holder.itemView, userId);
                })
        );

        // User profile headers.
        patcher.patch(
                UserProfileHeaderView.class,
                "updateViewState",
                new Class<?>[]{UserProfileHeaderViewModel.ViewState.Loaded.class},
                new Hook(param -> {
                    UserProfileHeaderView view = (UserProfileHeaderView) param.thisObject;
                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) param.args[0];
                    long userId = 0L;
                    if (state != null) {
                        if (state.getGuildMember() != null) {
                            userId = state.getGuildMember().getUserId();
                        }
                        if (userId == 0L && state.getUser() != null) {
                            userId = state.getUser().getId();
                        }
                    }
                    scheduleRender(view, userId);
                })
        );
    }

    private void scheduleRender(View parent, long userId) {
        if (parent == null) return;
        synchronized (boundViews) {
            boundViews.put(parent, userId);
        }

        // Posting is intentional: the core Decorations callback is also an
        // after-hook and may hide its view after this callback returns.
        parent.post(() -> renderIfStillBound(parent, userId));
    }

    private void renderIfStillBound(View parent, long userId) {
        synchronized (boundViews) {
            Long boundUser = boundViews.get(parent);
            if (boundUser == null || boundUser != userId) return;
        }

        if (userId == 0L) {
            hideDecoration(parent);
            return;
        }

        String asset = assetForUser(userId, parent);
        if (UNLOADED.equals(asset)) {
            hideDecoration(parent);
            return;
        }
        configureDecoration(parent, asset);
    }

    private String assetForUser(long userId, View parent) {
        if (userId == currentUserId()) {
            return emptyToNull(getSelectedAsset());
        }

        CachedDecoration cached = userDecorations.get(userId);
        if (cached == null) {
            fetchUserDecoration(userId, parent);
            return UNLOADED;
        }

        if (System.currentTimeMillis() - cached.fetchedAt >= DECORATION_FETCH_COOLDOWN) {
            fetchUserDecoration(userId, parent);
        }
        return cached.asset;
    }

    private void fetchUserDecoration(long userId, View parent) {
        if (!decorationRequests.add(userId)) return;

        Utils.threadPool.execute(() -> {
            String asset = null;
            try {
                String ids = URLEncoder.encode("[\"" + userId + "\"]", "UTF-8");
                String body = Http.simpleGet(API_URL + "/users?ids=" + ids);
                Map<?, ?> response = GsonUtils.fromJson(body, Map.class);
                if (response != null) {
                    Object value = response.get(String.valueOf(userId));
                    if (value != null && !"null".equals(String.valueOf(value))) {
                        asset = normalizeAsset(String.valueOf(value));
                    }
                }
                userDecorations.put(userId, new CachedDecoration(asset));
            } catch (Throwable error) {
                logger.error("Failed to fetch Decor decoration for " + userId, error);
            } finally {
                decorationRequests.remove(userId);
            }

            String result = asset;
            if (parent != null) {
                parent.post(() -> {
                    synchronized (boundViews) {
                        Long boundUser = boundViews.get(parent);
                        if (boundUser == null || boundUser != userId) return;
                    }
                    configureDecoration(parent, result);
                });
            }
        });
    }

    private void configureDecoration(View parent, String asset) {
        if (parent == null) return;
        View decoration = parent.findViewById(decorationViewId);
        if (decoration == null) return;

        if (asset == null || asset.isEmpty()) {
            decoration.setVisibility(View.INVISIBLE);
            return;
        }

        decoration.setVisibility(View.VISIBLE);
        String normalized = normalizeAsset(asset);
        if (decoration instanceof StickerView) {
            ((StickerView) decoration).d(
                    new AvatarSticker(new AvatarDecoration(normalized, DECOR_SKU_ID, null)), null);
        } else if (decoration instanceof ImageView) {
            IconUtils.setIcon((ImageView) decoration, assetUrl(normalized, false));
        }
    }

    private void hideDecoration(View parent) {
        if (parent == null) return;
        View decoration = parent.findViewById(decorationViewId);
        if (decoration != null) decoration.setVisibility(View.INVISIBLE);
    }

    private static int findDecorationViewId() {
        try {
            Class<?> decoratorKt = Class.forName(
                    "com.aliucord.coreplugins.decorations.avatar.AvatarDecoratorKt");
            java.lang.reflect.Method accessor = decoratorKt.getDeclaredMethod("access$getDecoId$p");
            accessor.setAccessible(true);
            return (Integer) accessor.invoke(null);
        } catch (Throwable error) {
            return View.generateViewId();
        }
    }

    void setSelectedAsset(String asset) {
        long userId = currentUserId();
        if (userId == 0L) {
            Utils.showToast("Discord account is not ready yet");
            return;
        }

        Map<String, String> assets = readSelectedAssets();
        String normalized = asset == null ? "" : normalizeAsset(asset);
        if (normalized.isEmpty()) assets.remove(String.valueOf(userId));
        else assets.put(String.valueOf(userId), normalized);
        saveSelectedAssets(assets);

        userDecorations.put(userId, new CachedDecoration(emptyToNull(normalized)));
    }

    String getSelectedAsset() {
        long userId = currentUserId();
        if (userId == 0L) return "";

        Map<String, String> assets = readSelectedAssets();
        String selected = assets.get(String.valueOf(userId));
        if (selected != null) return selected;

        // Migrate the old single-account setting used by the first draft.
        String legacy = settings.getString(LEGACY_SELECTED_ASSET_KEY, "").trim();
        if (!legacy.isEmpty()) {
            String normalized = normalizeAsset(legacy);
            assets.put(String.valueOf(userId), normalized);
            saveSelectedAssets(assets);
            settings.remove(LEGACY_SELECTED_ASSET_KEY);
            return normalized;
        }
        return "";
    }

    private Map<String, String> readSelectedAssets() {
        return readStringMap(SELECTED_ASSETS_KEY);
    }

    private void saveSelectedAssets(Map<String, String> assets) {
        settings.setString(SELECTED_ASSETS_KEY, GsonUtils.toJson(assets));
    }

    String getApiToken() {
        long userId = currentUserId();
        if (userId == 0L) return "";

        Map<String, String> tokens = readApiTokens();
        String token = tokens.get(String.valueOf(userId));
        if (token != null && !token.isEmpty()) return token;

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
        if (userId == 0L) return;

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

    void authorizeDecor(Context context) {
        Uri oauthUrl = Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", DISCORD_OAUTH_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", AUTHORIZE_URL)
                .appendQueryParameter("scope", "identify")
                .appendQueryParameter("permissions", "0")
                .build();
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, oauthUrl));
            Utils.showToast("Authorize in the browser, copy the returned token, then tap Finish authorization");
        } catch (Throwable error) {
            logger.error("Failed to open Decor authorization in browser", error);
            Utils.showToast("Could not open Decor authorization");
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

    private void validateAndSaveToken(String candidate, FakeDecorSettings settingsView) {
        Utils.threadPool.execute(() -> {
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
                Map<?, ?> decoration = GsonUtils.fromJson(response.text(), Map.class);
                String asset = decorationAsset(decoration);
                setSelectedAsset(asset);
                Utils.mainThread.post(() -> Utils.showToast(asset.isEmpty()
                        ? "Decor decoration removed" : "Decor decoration synced"));
            } catch (Throwable error) {
                logger.error("Failed to sync Decor decoration", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not sync Decor decoration"));
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
                form.put("hash", selected.isEmpty() ? "null" : apiHash(selected));
                Http.Response response = request.executeWithMultipartForm(form);
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                Utils.mainThread.post(() -> Utils.showToast("Decor decoration synced"));
            } catch (Throwable error) {
                logger.error("Failed to apply Decor decoration", error);
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

                Map<?, ?> decoration = GsonUtils.fromJson(response.text(), Map.class);
                String asset = decorationAsset(decoration);
                boolean pending = decoration != null && Boolean.FALSE.equals(decoration.get("reviewed"));
                if (!pending && !asset.isEmpty()) setSelectedAsset(asset);
                Utils.mainThread.post(() -> Utils.showToast(pending
                        ? "Decoration submitted for review" : "Custom decoration uploaded and applied"));
            } catch (Throwable error) {
                logger.error("Failed to upload Decor decoration", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not upload custom decoration"));
            }
        });
    }

    void fetchPresets(FakeDecorSettings settingsView) {
        Utils.threadPool.execute(() -> {
            try {
                String body = Http.simpleGet(API_URL + "/decorations/presets");
                List<?> presets = GsonUtils.fromJson(body, List.class);
                Utils.mainThread.post(() -> settingsView.showPresets(presets));
            } catch (Throwable error) {
                logger.error("Failed to fetch Decor presets", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not load Decor presets"));
            }
        });
    }

    void fetchOwnDecorations(FakeDecorSettings settingsView) {
        String token = getApiToken();
        if (token.isEmpty()) {
            Utils.showToast("Authorize Decor first");
            return;
        }

        Utils.threadPool.execute(() -> {
            try (Http.Request request = new Http.Request(API_URL + "/users/@me/decorations")) {
                request.setHeader("Authorization", "Bearer " + token);
                Http.Response response = request.execute();
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                List<?> decorations = GsonUtils.fromJson(response.text(), List.class);
                Utils.mainThread.post(() -> settingsView.showOwnDecorations(decorations));
            } catch (Throwable error) {
                logger.error("Failed to fetch own Decor decorations", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not load your decorations"));
            }
        });
    }

    void deleteDecoration(String hash) {
        String token = getApiToken();
        if (token.isEmpty()) {
            Utils.showToast("Authorize Decor first");
            return;
        }
        Utils.threadPool.execute(() -> {
            try (Http.Request request = new Http.Request(
                    API_URL + "/decorations/" + URLEncoder.encode(hash, "UTF-8"), "DELETE")) {
                request.setHeader("Authorization", "Bearer " + token);
                Http.Response response = request.execute();
                if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
                if (apiHash(getSelectedAsset()).equals(hash)) setSelectedAsset("");
                Utils.mainThread.post(() -> Utils.showToast("Decoration deleted"));
            } catch (Throwable error) {
                logger.error("Failed to delete Decor decoration", error);
                Utils.mainThread.post(() -> Utils.showToast("Could not delete decoration"));
            }
        });
    }

    private Map<String, String> readApiTokens() {
        return readStringMap(API_TOKENS_KEY);
    }

    private void saveApiTokens(Map<String, String> tokens) {
        settings.setString(API_TOKENS_KEY, GsonUtils.toJson(tokens));
    }

    private Map<String, String> readStringMap(String key) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Map<?, ?> stored = GsonUtils.fromJson(settings.getString(key, "{}"), Map.class);
            if (stored != null) {
                for (Map.Entry<?, ?> entry : stored.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }
        } catch (Throwable ignored) {
            // A malformed preference is treated as empty and replaced on save.
        }
        return result;
    }

    private static String decorationAsset(Map<?, ?> decoration) {
        if (decoration == null) return "";
        Object hash = decoration.get("hash");
        if (hash == null || "null".equals(String.valueOf(hash))) return "";
        boolean animated = Boolean.TRUE.equals(decoration.get("animated"));
        return normalizeAsset((animated ? "a_" : "") + hash);
    }

    private static String apiHash(String asset) {
        String normalized = normalizeAsset(asset);
        return normalized.startsWith("a_") ? normalized.substring(2) : normalized;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String normalizeAsset(String value) {
        if (value == null) return "";
        String asset = value.trim();
        if (asset.isEmpty() || "null".equals(asset)) return "";
        if (asset.startsWith("http://") || asset.startsWith("https://")) {
            int query = asset.indexOf('?');
            String base = query >= 0 ? asset.substring(0, query) : asset;
            if (base.endsWith(".png")) base = base.substring(0, base.length() - 4);
            return base;
        }
        int query = asset.indexOf('?');
        if (query >= 0) asset = asset.substring(0, query);
        int slash = asset.lastIndexOf('/');
        if (slash >= 0) asset = asset.substring(slash + 1);
        if (asset.endsWith(".png")) asset = asset.substring(0, asset.length() - 4);
        return asset;
    }

    private static String assetUrl(String asset, boolean canAnimate) {
        String normalized = normalizeAsset(asset);
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized + ".png";
        if (!canAnimate && normalized.startsWith("a_")) normalized = normalized.substring(2);
        return CDN_URL + "/" + normalized + ".png";
    }

    private static long currentUserId() {
        try {
            return StoreStream.getUsers().getMe().getId();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        userDecorations.clear();
        decorationRequests.clear();
        synchronized (boundViews) {
            boundViews.clear();
        }
    }

    private static final class CachedDecoration {
        private final String asset;
        private final long fetchedAt;

        private CachedDecoration(String asset) {
            this.asset = asset;
            this.fetchedAt = System.currentTimeMillis();
        }
    }
}
