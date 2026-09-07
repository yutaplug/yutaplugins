package com.github.yutaplug.profileeffects;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.WebView;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.entities.Plugin.SettingsTab;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.GsonUtils;
import b.f.g.c.c;
import com.discord.utilities.images.MGImages;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.image.ImageInfo;
import com.discord.models.member.GuildMember;
import com.discord.models.user.User;
import com.discord.databinding.WidgetUserSheetBinding;
import com.discord.widgets.user.usersheet.WidgetUserSheet;
import com.discord.widgets.user.profile.UserProfileHeaderView;
import com.discord.widgets.user.profile.UserProfileHeaderViewModel;
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel;

import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;

@SuppressWarnings({"unused", "unchecked"})
@AliucordPlugin
public final class ProfileEffects extends Plugin {
    static final String USE_WEBVIEW = "useWebView";
    private static final String TAG = "ProfileEffects";
    private static final String CDN = "https://cdn.discordapp.com";
    private static final int MAX_WEBVIEW_LAYER_SIZE = 8191;
    private static final long CACHE_DURATION = 10L * 60L * 1000L;
    // APNG decoding retains every frame. Serializing decodes prevents two large
    // profile layers from spiking the app's heap at the same time.
    private static final Semaphore APNG_DECODE_PERMIT = new Semaphore(1, true);
    private final Map<String, CachedProfile> profileCache = new ConcurrentHashMap<>();
    private final Set<String> profileRequests = ConcurrentHashMap.newKeySet();
    private final Map<UserProfileHeaderView, String> boundProfiles =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UserProfileHeaderView, ProfileOverlay> backOverlays =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UserProfileHeaderView, ProfileOverlay> frontOverlays =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UserProfileHeaderView, EffectOverlay> effectOverlays =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void start(Context context) throws Throwable {
        Log.i(TAG, "ProfileEffects started");
        settingsTab = new SettingsTab(ProfileEffectsSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings);
        patcher.patch(
                UserProfileHeaderView.class,
                "updateViewState",
                new Class<?>[]{UserProfileHeaderViewModel.ViewState.Loaded.class},
                new Hook(frame -> {
                    UserProfileHeaderView header = (UserProfileHeaderView) frame.thisObject;
                    // Do not attach anything to the Settings profile header. The normal
                    // user sheet is the only surface this plugin is intended to modify.
                    if (!isUserSheetHeader(header)) return;
                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) frame.args[0];
                    User user = state.getUser();
                    if (user == null || user.getId() <= 0L) return;
                    Log.i(TAG, "Profile header hook fired");

                    Long guildId = null;
                    GuildMember member = state.getGuildMember();
                    if (member != null && member.getGuildId() > 0L) {
                        guildId = member.getGuildId();
                    }
                    bindHeader(header, user.getId(), guildId);
                })
        );

        // ModernProfiles uses this callback because it runs after the user-sheet binding
        // has been populated. Keep this as a second path: on some Discord builds the
        // header state callback runs before the sheet is laid out, so attaching there can
        // leave the overlay on a temporary parent or behind the sheet's real container.
        try {
            patcher.patch(
                    WidgetUserSheet.class,
                    "configureDeveloperSection",
                    new Class<?>[]{WidgetUserSheetViewModel.ViewState.Loaded.class},
                    new Hook(frame -> bindSheetHeader(
                            (WidgetUserSheet) frame.thisObject,
                            (WidgetUserSheetViewModel.ViewState.Loaded) frame.args[0]))
            );
        } catch (Throwable error) {
            // Keep the legacy header hook alive if a future Discord build removes this
            // private helper. The second hook is only an attachment-timing fallback.
            logger.error("Could not hook modern profile sheet", error);
        }

        try {
            patcher.patch(
                    WidgetUserSheet.class,
                    "configureUI",
                    new Class<?>[]{WidgetUserSheetViewModel.ViewState.class},
                    new Hook(frame -> {
                        if (frame.args[0] instanceof WidgetUserSheetViewModel.ViewState.Loaded) {
                            bindSheetHeader(
                                    (WidgetUserSheet) frame.thisObject,
                                    (WidgetUserSheetViewModel.ViewState.Loaded) frame.args[0]);
                        }
                    })
            );
        } catch (Throwable error) {
            logger.error("Could not hook user-sheet UI", error);
        }

        try {
            patcher.patch(
                    WidgetUserSheet.class,
                    "onResume",
                    new Class<?>[]{},
                    new Hook(frame -> refreshResumedSheet((WidgetUserSheet) frame.thisObject))
            );
        } catch (Throwable error) {
            logger.error("Could not hook user-sheet resume", error);
        }
    }

    private static boolean isUserSheetHeader(UserProfileHeaderView header) {
        return hasResourceEntryName(header, "user_sheet_profile_header_view");
    }

    private static boolean hasResourceEntryName(View view, String name) {
        try {
            return name.equals(view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void bindSheetHeader(
            WidgetUserSheet sheet, WidgetUserSheetViewModel.ViewState.Loaded state) {
        User user = state.getUser();
        if (user == null || user.getId() <= 0L) return;

        WidgetUserSheetBinding binding = WidgetUserSheet.access$getBinding$p(sheet);
        if (binding == null || binding.J == null) return;

        Long guildId = state.getCurrentGuildId();
        GuildMember member = state.getGuildMember();
        if (member != null && member.getGuildId() > 0L) {
            guildId = member.getGuildId();
        }
        Log.i(TAG, "Modern profile sheet hook fired; header=" + binding.J.getWidth()
                + "x" + binding.J.getHeight());
        bindHeader(binding.J, user.getId(), guildId);
    }

    private void bindHeader(UserProfileHeaderView header, long userId, Long guildId) {
        String key = profileKey(userId, guildId);
        boundProfiles.put(header, key);
        CachedProfile cached = profileCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_DURATION) {
            render(header, cached.profile);
        } else {
            render(header, Profile.EMPTY);
            requestProfile(header, userId, guildId, key);
        }
        // Discord can finish attaching/rebinding the sheet after the header state
        // callback. Re-apply the cached product on the next UI turn so reopening a
        // profile does not leave the overlay detached or underneath the sheet.
        header.post(() -> renderCachedProfile(header));
    }

    private void renderCachedProfile(UserProfileHeaderView header) {
        String key = boundProfiles.get(header);
        if (key == null) return;
        CachedProfile cached = profileCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_DURATION) {
            render(header, cached.profile);
        }
    }

    private void refreshResumedSheet(WidgetUserSheet sheet) {
        try {
            WidgetUserSheetBinding binding = WidgetUserSheet.access$getBinding$p(sheet);
            if (binding == null || binding.J == null) return;

            UserProfileHeaderView header = binding.J;
            String key = boundProfiles.get(header);
            if (key == null) return;

            CachedProfile cached = profileCache.get(key);
            if (cached != null) render(header, cached.profile);
            header.post(() -> renderCachedProfile(header));

            EffectOverlay effectOverlay = effectOverlays.get(header);
            if (effectOverlay != null) effectOverlay.restartAnimation();
        } catch (Throwable error) {
            logger.error("Could not refresh resumed profile sheet", error);
        }
    }

    private void requestProfile(UserProfileHeaderView header, long userId, Long guildId, String key) {
        if (!profileRequests.add(key)) return;

        Utils.threadPool.execute(() -> {
            try {
                Map<?, ?> body = requestJson(profileRoute(userId, guildId));
                Map<?, ?> userProfile = asMap(body.get("user_profile"));
                Map<?, ?> guildProfile = asMap(body.get("guild_member_profile"));

                Long effectSku = skuId(userProfile == null ? null : userProfile.get("profile_effect"));
                Long frameSku = skuId(userProfile == null ? null : userProfile.get("profile_frame"));
                effectSku = firstCollectibleSku(userProfile, effectSku, 1L);
                frameSku = firstCollectibleSku(userProfile, frameSku, 3L);

                // Guild profiles can override the global profile. A null guild value falls
                // back to the global value, matching Discord's web client behavior.
                Long guildEffectSku = skuId(guildProfile == null
                        ? null : guildProfile.get("profile_effect"));
                Long guildFrameSku = skuId(guildProfile == null
                        ? null : guildProfile.get("profile_frame"));
                guildEffectSku = firstCollectibleSku(guildProfile, guildEffectSku, 1L);
                guildFrameSku = firstCollectibleSku(guildProfile, guildFrameSku, 3L);
                if (guildEffectSku != null) effectSku = guildEffectSku;
                if (guildFrameSku != null) frameSku = guildFrameSku;
                Log.i(TAG, "Profile data found: effect=" + effectSku + " frame=" + frameSku);

                ProfileParts parts = new ProfileParts();
                int productCount = (effectSku == null ? 0 : 1) + (frameSku == null ? 0 : 1);
                if (productCount == 0) {
                    finishProfileRequest(header, key, Profile.EMPTY);
                    return;
                }

                AtomicInteger remaining = new AtomicInteger(productCount);
                if (effectSku != null) {
                    requestProductPart(header, key, effectSku, true, parts, remaining);
                }
                if (frameSku != null) {
                    requestProductPart(header, key, frameSku, false, parts, remaining);
                }
            } catch (Throwable error) {
                logger.error("Failed to load Discord profile effects", error);
                finishProfileRequest(header, key, Profile.EMPTY);
            }
        });
    }

    private void requestProductPart(UserProfileHeaderView header, String key, long skuId,
                                    boolean effect, ProfileParts parts,
                                    AtomicInteger remaining) {
        try {
            Utils.threadPool.execute(() -> {
                Product product = requestProduct(skuId);
                if (effect) {
                    parts.effect = product;
                } else {
                    parts.frame = product;
                }

                Profile partial = new Profile(parts.effect, parts.frame);
                Utils.mainThread.post(() -> {
                    String boundKey = boundProfiles.get(header);
                    if (key.equals(boundKey)) render(header, partial);
                });

                if (remaining.decrementAndGet() == 0) {
                    finishProfileRequest(header, key, new Profile(parts.effect, parts.frame));
                }
            });
        } catch (Throwable error) {
            logger.error("Failed to schedule collectible product " + skuId, error);
            if (effect) {
                parts.effect = null;
            } else {
                parts.frame = null;
            }
            if (remaining.decrementAndGet() == 0) {
                finishProfileRequest(header, key, new Profile(parts.effect, parts.frame));
            }
        }
    }

    private void finishProfileRequest(UserProfileHeaderView header, String key, Profile profile) {
        profileCache.put(key, new CachedProfile(profile));
        profileRequests.remove(key);
        Utils.mainThread.post(() -> {
            String boundKey = boundProfiles.get(header);
            if (key.equals(boundKey)) render(header, profile);
        });
    }

    private Map<?, ?> requestJson(String route) throws Exception {
        try (Http.Request request = Http.Request.newDiscordRNRequest(route)) {
            String fingerprint = com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE
                    .getFingerprint();
            if (fingerprint != null) request.setHeader("X-Fingerprint", fingerprint);
            Http.Response response = request.execute();
            if (!response.ok()) throw new IllegalStateException("HTTP " + response.statusCode);
            Map<?, ?> body = GsonUtils.fromJson(response.text(), Map.class);
            return body == null ? Collections.emptyMap() : body;
        }
    }

    private Product requestProduct(long skuId) {
        try {
            Map<?, ?> body = requestJson("/collectibles-products/" + skuId);
            List<?> items = asList(body.get("items"));
            Map<?, ?> item = items.isEmpty() ? null : asMap(items.get(0));
            if (item == null) return null;

            long type = number(item.get("type"), 0L);
            if (type == 1L) {
                String staticFrame = cdnUrl(item.get("staticFrameSrc"));
                String reducedMotion = cdnUrl(item.get("reducedMotionSrc"));
                String fallback = firstEffectSource(item.get("effects"));
                return Product.effect(
                        firstNonEmpty(reducedMotion, staticFrame, fallback),
                        parseEffects(item.get("effects")));
            }
            if (type == 3L) {
                FrameMetrics metrics = new FrameMetrics(
                        number(item.get("inner_width"), 1200L),
                        number(item.get("overflow_top"), 0L),
                        number(item.get("overflow_bottom"), 0L),
                        number(item.get("overflow_horizontal"), 0L)
                );
                return Product.frame(skuId, metrics, parseLayers(item.get("layers")));
            }
        } catch (Throwable error) {
            logger.error("Failed to load collectible product " + skuId, error);
        }
        return null;
    }

    private void render(UserProfileHeaderView header, Profile profile) {
        Log.i(TAG, "Rendering profile overlay: effect=" + (profile.effect != null)
                + " frame=" + (profile.frame != null));
        ViewGroup host = findProfileCardHost(header);
        if (host == null) return;

        ProfileOverlay backOverlay = backOverlays.get(header);
        if (backOverlay == null) {
            backOverlay = new ProfileOverlay(header.getContext(), false);
            backOverlay.setClickable(false);
            attachBoundedChild(host, header, backOverlay, true);
            backOverlays.put(header, backOverlay);
        } else if (backOverlay.getParent() != host) {
            removeFromParent(backOverlay);
            attachBoundedChild(host, header, backOverlay, true);
        }
        backOverlay.setFrame(profile.frame);

        ProfileOverlay frontOverlay = frontOverlays.get(header);
        if (frontOverlay == null) {
            frontOverlay = new ProfileOverlay(header.getContext(), true);
            frontOverlay.setClickable(false);
            attachBoundedChild(host, header, frontOverlay, false);
            frontOverlay.setElevation(1000f);
            frontOverlays.put(header, frontOverlay);
        } else if (frontOverlay.getParent() != host) {
            removeFromParent(frontOverlay);
            attachBoundedChild(host, header, frontOverlay, false);
        }
        frontOverlay.setFrame(profile.frame);

        EffectOverlay effectOverlay = effectOverlays.get(header);
        if (effectOverlay == null) {
            effectOverlay = new EffectOverlay(header.getContext());
            effectOverlay.setClickable(false);
            attachBoundedChild(host, header, effectOverlay, false);
            effectOverlays.put(header, effectOverlay);
        } else if (effectOverlay.getParent() != host) {
            removeFromParent(effectOverlay);
            attachBoundedChild(host, header, effectOverlay, false);
        }
        effectOverlay.setUseWebView(settings.getBool(USE_WEBVIEW, false));
        effectOverlay.setEffect(profile.effect);
        host.bringChildToFront(frontOverlay);
        host.bringChildToFront(effectOverlay);
    }

    private static void attachBoundedChild(ViewGroup host, UserProfileHeaderView header,
                                           View overlay, boolean back) {
        // The fullscreen sheet's frame deliberately overflows the native profile
        // card at the top and sides. Keep the actual controls underneath it, but do
        // not let the card/root clip those overflow pixels.
        host.setClipChildren(false);
        host.setClipToPadding(false);
        ViewGroup ancestor = host;
        for (int depth = 0; depth < 4 && ancestor.getParent() instanceof ViewGroup; depth++) {
            ancestor = (ViewGroup) ancestor.getParent();
            ancestor.setClipChildren(false);
            ancestor.setClipToPadding(false);
        }
        ProfileBounds bounds = profileBounds(header, host);
        ViewGroup.LayoutParams params;
        if (host instanceof FrameLayout) {
            FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bounds.height);
            frameParams.gravity = Gravity.TOP;
            frameParams.topMargin = bounds.top;
            params = frameParams;
        } else if (host instanceof ConstraintLayout) {
            ConstraintLayout.LayoutParams constraintParams = new ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bounds.height);
            constraintParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
            constraintParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID;
            constraintParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            params = constraintParams;
        } else {
            params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bounds.height);
        }
        // Back frame layers belong behind the native profile content.
        // Appending them made the side rails cover the banner; front layers remain above
        // the content so the decorative top piece is still visible.
        host.addView(overlay, back ? 0 : host.getChildCount(), params);
        View content = findAncestorByResourceName(header, "user_sheet_content");
        View actions = content == null
                ? null
                : findDescendantByResourceName(content, "user_sheet_profile_actions_container");
        header.addOnLayoutChangeListener((view, left, top, right, bottom,
                                           oldLeft, oldTop, oldRight, oldBottom) ->
                resizeBoundedChild(host, header, overlay));
        host.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) ->
                resizeBoundedChild(host, header, overlay));
        if (actions != null) {
            actions.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) ->
                    resizeBoundedChild(host, header, overlay));
        }
        if (content != null) {
            content.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) ->
                    resizeBoundedChild(host, header, overlay));
        }
        header.post(() -> resizeBoundedChild(host, header, overlay));
    }

    private static void resizeBoundedChild(ViewGroup host, UserProfileHeaderView header,
                                           View overlay) {
        if (overlay.getParent() != host) return;
        ProfileBounds bounds = profileBounds(header, host);
        if (bounds.height <= 0) return;
        ViewGroup.LayoutParams params = overlay.getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = bounds.height;
        if (params instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) params).topMargin = bounds.top;
        }
        overlay.setLayoutParams(params);
    }

    private static ViewGroup findProfileCardHost(UserProfileHeaderView header) {
        View current = header;
        while (current.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (hasResourceEntryName(parent, "user_sheet_content")) {
                ViewParent root = parent.getParent();
                return root instanceof ViewGroup ? (ViewGroup) root : parent;
            }
            current = parent;
        }
        return header.getParent() instanceof ViewGroup
                ? (ViewGroup) header.getParent()
                : null;
    }

    private static ProfileBounds profileBounds(UserProfileHeaderView header, ViewGroup host) {
        View headerContainer = header.getParent() instanceof View
                ? (View) header.getParent()
                : header;
        int top = relativeTop(headerContainer, host);
        int bottom = top + headerContainer.getHeight();

        View content = findAncestorByResourceName(header, "user_sheet_content");
        if (content != null && content.getHeight() > 0) {
            // The fullscreen viewer is one scrollable profile card. Effects and
            // frames must follow that complete card, not stop at the action row.
            top = relativeTop(content, host);
            bottom = top + content.getHeight();
        }
        return new ProfileBounds(Math.max(0, top), Math.max(0, bottom - top));
    }

    private static View findAncestorByResourceName(View view, String name) {
        View current = view;
        while (current != null) {
            if (hasResourceEntryName(current, name)) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static View findDescendantByResourceName(View root, String name) {
        if (hasResourceEntryName(root, name)) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findDescendantByResourceName(group.getChildAt(index), name);
            if (result != null) return result;
        }
        return null;
    }

    private static int relativeTop(View view, ViewGroup ancestor) {
        int top = 0;
        View current = view;
        while (current != ancestor) {
            top += current.getTop();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return Math.max(0, top);
    }

    private static final class ProfileBounds {
        private final int top;
        private final int height;

        private ProfileBounds(int top, int height) {
            this.top = top;
            this.height = height;
        }
    }

    private static void removeFromParent(View view) {
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private static String profileRoute(long userId, Long guildId) {
        String route = "/users/" + userId + "/profile?with_mutual_guilds=true&with_mutual_friends=true";
        return guildId == null ? route : route + "&guild_id=" + guildId;
    }

    private static String profileKey(long userId, Long guildId) {
        return userId + ":" + (guildId == null ? "global" : guildId);
    }

    private static Long firstCollectibleSku(Map<?, ?> profile, Long current, long type) {
        if (current != null || profile == null) return current;
        List<?> collectibles = asList(profile.get("collectibles"));
        for (Object raw : collectibles) {
            Map<?, ?> collectible = asMap(raw);
            if (collectible != null && number(collectible.get("type"), 0L) == type) {
                Long sku = skuId(collectible.get("sku_id"));
                if (sku != null) return sku;
            }
        }
        return null;
    }

    private static Long skuId(Object raw) {
        Map<?, ?> object = asMap(raw);
        return object == null ? numberOrNull(raw) : numberOrNull(object.get("sku_id"));
    }

    private static List<FrameLayer> parseLayers(Object raw) {
        List<FrameLayer> layers = new ArrayList<>();
        for (Object value : asList(raw)) {
            Map<?, ?> object = asMap(value);
            if (object == null) continue;
            Long id = numberOrNull(object.get("id"));
            if (id == null) continue;
            String type = string(object.get("type"));
            if (type.isEmpty()) type = string(object.get("layer_type"));
            String anchor = normalizeFrameAnchor(object.get("anchor"));
            String order = string(object.get("order"));
            boolean front = "front".equalsIgnoreCase(order) || "0".equals(order);
            layers.add(new FrameLayer(
                    id,
                    front,
                    type,
                    anchor,
                    isBottomAnchor(anchor),
                    booleanValue(object.get("responsive"))
            ));
        }
        return layers;
    }

    private static String normalizeFrameAnchor(Object raw) {
        String anchor = string(raw).toLowerCase();
        if ("0".equals(anchor)) return "top";
        if ("1".equals(anchor)) return "bottom";
        if ("2".equals(anchor)) return "center";
        if ("middle".equals(anchor)) return "center";
        return anchor;
    }

    private static boolean isBottomAnchor(String anchor) {
        if ("bottom".equalsIgnoreCase(anchor)) return true;
        // Current collectibles responses encode TOP/BOTTOM as numeric enum
        // values. TOP is 0 and BOTTOM is 1; older responses used the names.
        return numberOrNull(anchor) != null && numberOrNull(anchor) == 1L;
    }

    private static String firstEffectSource(Object raw) {
        for (Object value : asList(raw)) {
            Map<?, ?> effect = asMap(value);
            if (effect == null) continue;
            String source = effectSource(effect);
            if (!source.isEmpty()) return source;
        }
        return "";
    }

    private static String effectSource(Map<?, ?> effect) {
        String source = cdnUrl(effect.get("src"));
        if (!source.isEmpty()) return source;
        for (Object value : asList(effect.get("randomizedSources"))) {
            Map<?, ?> randomized = asMap(value);
            if (randomized == null) continue;
            source = cdnUrl(randomized.get("src"));
            if (!source.isEmpty()) return source;
        }
        return "";
    }

    private static List<EffectLayer> parseEffects(Object raw) {
        List<EffectLayer> effects = new ArrayList<>();
        for (Object value : asList(raw)) {
            Map<?, ?> object = asMap(value);
            if (object == null) continue;
            String source = effectSource(object);
            if (source.isEmpty()) continue;

            Map<?, ?> position = asMap(object.get("position"));
            Object zIndex = object.containsKey("zIndex")
                    ? object.get("zIndex") : object.get("z_index");
            effects.add(new EffectLayer(
                    source,
                    number(object.get("start"), 0L),
                    number(object.get("duration"), 0L),
                    booleanValue(object.get("loop")),
                    number(object.get("loopDelay"), 0L),
                    number(position == null ? null : position.get("x"), 0L),
                    number(position == null ? null : position.get("y"), 0L),
                    number(object.get("width"), 450L),
                    number(object.get("height"), 880L),
                    number(zIndex, 0L)));
        }
        effects.sort(Comparator.comparingLong(effect -> effect.zIndex));
        return effects;
    }

    private static String cdnUrl(Object raw) {
        String source = string(raw).trim();
        if (source.startsWith("//")) return "https:" + source;
        if (source.startsWith("/")) return CDN + source;
        return source;
    }

    private static File effectCacheFile(Context context, String source) {
        File directory = new File(context.getCacheDir(), "profile-effects");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IllegalStateException("Could not create profile effect cache");
        }
        return new File(directory, Integer.toHexString(source.hashCode()) + ".png");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<?, ?>) value : null;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value, long fallback) {
        Long result = numberOrNull(value);
        return result == null ? fallback : result;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean
                ? (Boolean) value
                : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Long numberOrNull(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        profileRequests.clear();
        profileCache.clear();
        synchronized (backOverlays) {
            for (Map.Entry<UserProfileHeaderView, ProfileOverlay> entry : backOverlays.entrySet()) {
                ViewParent parent = entry.getValue().getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(entry.getValue());
                }
            }
            backOverlays.clear();
        }
        synchronized (frontOverlays) {
            for (Map.Entry<UserProfileHeaderView, ProfileOverlay> entry : frontOverlays.entrySet()) {
                ViewParent parent = entry.getValue().getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(entry.getValue());
                }
            }
            frontOverlays.clear();
        }
        synchronized (effectOverlays) {
            for (Map.Entry<UserProfileHeaderView, EffectOverlay> entry : effectOverlays.entrySet()) {
                ViewParent parent = entry.getValue().getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(entry.getValue());
                }
            }
            effectOverlays.clear();
        }
        boundProfiles.clear();
    }

    private static final class CachedProfile {
        private final Profile profile;
        private final long fetchedAt = System.currentTimeMillis();

        private CachedProfile(Profile profile) {
            this.profile = profile;
        }
    }

    private static final class Profile {
        private static final Profile EMPTY = new Profile(null, null);
        private final Product effect;
        private final Product frame;

        private Profile(Product effect, Product frame) {
            this.effect = effect;
            this.frame = frame;
        }
    }

    private static final class ProfileParts {
        private volatile Product effect;
        private volatile Product frame;
    }

    private static final class Product {
        private final String effectSource;
        private final List<EffectLayer> effectLayers;
        private final List<FrameLayer> frameLayers;
        private final long frameSku;
        private final FrameMetrics frameMetrics;

        private Product(String effectSource, List<EffectLayer> effectLayers,
                        List<FrameLayer> frameLayers, long frameSku, FrameMetrics frameMetrics) {
            this.effectSource = effectSource;
            this.effectLayers = effectLayers;
            this.frameLayers = frameLayers;
            this.frameSku = frameSku;
            this.frameMetrics = frameMetrics;
        }

        private static Product effect(String source, List<EffectLayer> layers) {
            return source.isEmpty()
                    ? null : new Product(source, layers, Collections.emptyList(), 0L, null);
        }

        private static Product frame(long skuId, FrameMetrics metrics, List<FrameLayer> layers) {
            return layers.isEmpty()
                    ? null
                    : new Product("", Collections.emptyList(), layers, skuId, metrics);
        }
    }

    private static final class EffectLayer {
        private final String source;
        private final long start;
        private final long duration;
        private final boolean loop;
        private final long loopDelay;
        private final long x;
        private final long y;
        private final long width;
        private final long height;
        private final long zIndex;

        private EffectLayer(String source, long start, long duration, boolean loop,
                            long loopDelay,
                            long x, long y, long width, long height, long zIndex) {
            this.source = source;
            this.start = Math.max(0L, start);
            this.duration = Math.max(0L, duration);
            this.loop = loop;
            this.loopDelay = Math.max(0L, loopDelay);
            this.x = x;
            this.y = y;
            this.width = width > 0L ? width : 450L;
            this.height = height > 0L ? height : 880L;
            this.zIndex = zIndex;
        }
    }

    private static final class FrameMetrics {
        private final long innerWidth;
        private final long overflowTop;
        private final long overflowBottom;
        private final long overflowHorizontal;

        private FrameMetrics(long innerWidth, long overflowTop, long overflowBottom,
                             long overflowHorizontal) {
            this.innerWidth = innerWidth > 0L ? innerWidth : 1200L;
            this.overflowTop = Math.max(0L, overflowTop);
            this.overflowBottom = Math.max(0L, overflowBottom);
            this.overflowHorizontal = Math.max(0L, overflowHorizontal);
        }
    }

    private static final class FrameLayer {
        private final long id;
        private final boolean front;
        private final String type;
        private final String anchor;
        private final boolean bottom;
        private final boolean responsive;

        private FrameLayer(long id, boolean front, String type, String anchor, boolean bottom,
                           boolean responsive) {
            this.id = id;
            this.front = front;
            this.type = type;
            this.anchor = anchor;
            this.bottom = bottom;
            this.responsive = responsive;
        }
    }

    /** A visual-only layer which must never steal clicks from the real profile UI. */
    private abstract static class TouchThroughFrameLayout extends FrameLayout {
        private TouchThroughFrameLayout(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            // The frame/effect can extend across buttons. Returning false before
            // dispatching to children lets the underlying Settings/profile controls
            // receive the event, including Restart, Log Out, and the overflow menu.
            return false;
        }
    }

    private static final class ProfileOverlay extends TouchThroughFrameLayout {
        private final boolean front;
        private final List<FrameLayerView> layerViews = new ArrayList<>();
        private Product frame;

        private ProfileOverlay(Context context, boolean front) {
            super(context);
            this.front = front;
            setClipChildren(false);
            setClipToPadding(false);
        }

        private void setFrame(Product frame) {
            if (this.frame == frame && (frame == null || !layerViews.isEmpty())) return;
            this.frame = frame;
            rebuild();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width != oldWidth || height != oldHeight) layoutLayers();
        }

        private void rebuild() {
            removeAllViews();
            layerViews.clear();

            if (frame != null) {
                List<FrameLayer> layers = new ArrayList<>(frame.frameLayers);
                layers.sort(Comparator.comparing(layer -> layer.front));
                for (FrameLayer layer : layers) {
                    // Keep the top/rail layers. A bottom staple belongs to the web
                    // profile footer and is intentionally not drawn in the native sheet.
                    if (layer.front == front
                            && !("staple".equals(layer.type) && layer.bottom)) {
                        addFrameLayer(frame, layer);
                    }
                }
            }
            layoutLayers();
        }

        private void addFrameLayer(Product frame, FrameLayer layer) {
            SimpleDraweeView image = new SimpleDraweeView(getContext());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayerView layerView = new FrameLayerView(layer, image);
            layerViews.add(layerView);
            addView(image, new FrameLayout.LayoutParams(1, 1));
            MGImages.setImage(image, Arrays.asList(
                    frameLayerUrl(frame.frameSku, layer.id),
                    frameLayerFallbackUrl(frame.frameSku, layer.id)),
                    0,
                    0,
                    false,
                    null,
                    MGImages.AlwaysUpdateChangeDetector.INSTANCE,
                    new c<ImageInfo>() {
                        @Override
                        public void onFinalImageSet(String id, ImageInfo info, Animatable animatable) {
                            layerView.setImageSize(info);
                        }

                        @Override
                        public void onIntermediateImageSet(String id, ImageInfo info) {
                            layerView.setImageSize(info);
                        }

                        @Override
                        public void onFailure(String id, Throwable error) {
                            Log.e(TAG, "Frame layer failed: " + id, error);
                        }
                    });
        }

        private void layoutLayers() {
            if (getWidth() <= 0 || frame == null) return;
            FrameMetrics metrics = frame.frameMetrics;
            float scale = getWidth() / (float) metrics.innerWidth;
            int layerWidth = Math.round(
                    (metrics.innerWidth + 2f * metrics.overflowHorizontal) * scale);
            for (FrameLayerView layerView : layerViews) {
                int height;
                if ("border".equals(layerView.layer.type)) {
                    // Discord repeats border layers to fill the frame container.
                    height = Math.max(1, getHeight());
                } else {
                    height = layerView.imageWidth > 0 && layerView.imageHeight > 0
                            ? Math.max(1, Math.round(layerWidth
                            * (layerView.imageHeight / (float) layerView.imageWidth)))
                            : Math.max(1, Math.round(layerWidth * 0.75f));
                }
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                        layerView.image.getLayoutParams();
                params.width = layerWidth;
                params.height = height;
                params.leftMargin = -Math.round(metrics.overflowHorizontal * scale);
                if ("rail".equals(layerView.layer.type)) {
                    // Rails are full-frame background layers. Discord uses the
                    // layer's anchor as background-position, not as a stretch flag.
                    if ("bottom".equals(layerView.layer.anchor)) {
                        params.gravity = Gravity.BOTTOM;
                    } else if ("center".equals(layerView.layer.anchor)) {
                        params.gravity = Gravity.CENTER_VERTICAL;
                    } else {
                        params.gravity = Gravity.TOP;
                    }
                    params.topMargin = 0;
                    params.bottomMargin = 0;
                } else if (layerView.layer.bottom) {
                    params.gravity = Gravity.BOTTOM;
                    params.bottomMargin = -Math.round(metrics.overflowBottom * scale);
                    params.topMargin = 0;
                } else {
                    params.gravity = Gravity.TOP;
                    params.topMargin = -Math.round(metrics.overflowTop * scale);
                    params.bottomMargin = 0;
                }
                layerView.image.setLayoutParams(params);
            }
        }

        private final class FrameLayerView {
            private final FrameLayer layer;
            private final SimpleDraweeView image;
            private int imageWidth;
            private int imageHeight;

            private FrameLayerView(FrameLayer layer, SimpleDraweeView image) {
                this.layer = layer;
                this.image = image;
            }

            private void setImageSize(ImageInfo info) {
                if (info == null || info.getWidth() <= 0 || info.getHeight() <= 0) return;
                imageWidth = info.getWidth();
                imageHeight = info.getHeight();
                layoutLayers();
            }
        }

        private static String frameLayerUrl(long skuId, long layerId) {
            return CDN + "/media/v1/collectibles-shop/" + skuId + "/" + layerId + "/static";
        }

        private static String frameLayerFallbackUrl(long skuId, long layerId) {
            return CDN + "/media/v1/collectibles-shop/" + skuId + "/" + layerId + "/static.png";
        }
    }

    private static final class EffectOverlay extends TouchThroughFrameLayout {
        private Product effect;
        private WebView webView;
        private final List<EffectLayerView> layerViews = new ArrayList<>();
        private boolean useWebView;
        private boolean rebuildOnAttach;
        private final Runnable restartTask = () -> {
            if (getWindowToken() == null || getWindowVisibility() != View.VISIBLE) return;
            if (rebuildOnAttach) {
                rebuildOnAttach = false;
                rebuild();
            } else {
                restartAnimationNow();
            }
        };

        private EffectOverlay(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            layoutLayers();
            resizeWebView();
        }

        private void setEffect(Product effect) {
            if (this.effect == effect) return;
            this.effect = effect;
            rebuild();
        }

        private void setUseWebView(boolean useWebView) {
            if (this.useWebView == useWebView) return;
            this.useWebView = useWebView;
            rebuild();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            scheduleRestart(false);
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == View.VISIBLE) {
                scheduleRestart(false);
            } else {
                rebuildOnAttach = true;
                removeCallbacks(restartTask);
                pauseWebView();
                disposeLayers();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            rebuildOnAttach = true;
            removeCallbacks(restartTask);
            destroyWebView();
            disposeLayers();
            removeAllViews();
            super.onDetachedFromWindow();
        }

        private void restartAnimation() {
            scheduleRestart(false);
        }

        private void scheduleRestart(boolean recreate) {
            if (recreate) rebuildOnAttach = true;
            removeCallbacks(restartTask);
            postDelayed(restartTask, 120L);
        }

        private void restartAnimationNow() {
            if (useWebView) {
                if (webView == null) return;
                webView.onResume();
                webView.resumeTimers();
                webView.postDelayed(() -> {
                    if (webView != null && getWindowToken() != null
                            && getWindowVisibility() == View.VISIBLE) {
                        webView.evaluateJavascript(
                                "(function(){if(window.restartEffects)window.restartEffects();})();",
                                null);
                    }
                }, 50L);
                return;
            }
            if (effect == null || effect.effectLayers.isEmpty()) return;
            for (EffectLayerView layerView : layerViews) layerView.restart();
        }

        private void rebuild() {
            destroyWebView();
            disposeLayers();
            removeAllViews();
            layerViews.clear();
            if (effect == null || effect.effectSource.isEmpty()) return;

            if (!effect.effectLayers.isEmpty()) {
                if (useWebView) {
                    createWebView();
                    return;
                }
                // React Native renders each profile-effect asset as an independent
                // native image layer. Decode the same APNG format used by Discord.
                for (EffectLayer layer : effect.effectLayers) addLayer(layer);
                layoutLayers();
                return;
            }

            SimpleDraweeView image = new SimpleDraweeView(getContext());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            MGImages.setImage(image, Collections.singletonList(effect.effectSource),
                    0,
                    0,
                    false,
                    null,
                    MGImages.AlwaysUpdateChangeDetector.INSTANCE,
                    new c<ImageInfo>() {
                        @Override
                        public void onFinalImageSet(String id, ImageInfo info, Animatable animatable) {
                            updateAspectRatio(image, info);
                        }

                        @Override
                        public void onIntermediateImageSet(String id, ImageInfo info) {
                            updateAspectRatio(image, info);
                        }

                        @Override
                        public void onFailure(String id, Throwable error) {
                            Log.e(TAG, "Effect image failed: " + id, error);
                        }
                    });
        }

        private void createWebView() {
            WebView view = new WebView(getContext());
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setAlpha(1f);
            view.setClickable(false);
            view.setFocusable(false);
            view.setVerticalScrollBarEnabled(false);
            view.setHorizontalScrollBarEnabled(false);
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(false);
            view.getSettings().setLoadsImagesAutomatically(true);
            view.getSettings().setSupportZoom(false);
            addView(view, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, effectSurfaceHeight()));
            webView = view;
            resizeWebView();
            view.loadDataWithBaseURL(
                    CDN + "/",
                    effectHtml(effect),
                    "text/html",
                    "UTF-8",
                    null);
        }

        private void resizeWebView() {
            if (webView == null) return;
            ViewGroup.LayoutParams params = webView.getLayoutParams();
            int height = effectSurfaceHeight();
            if (params.height != height) {
                params.height = height;
                webView.setLayoutParams(params);
            }
        }

        private int effectSurfaceHeight() {
            if (effect == null || effect.effectLayers.isEmpty()) return 1;
            int width = getWidth();
            if (width <= 0) return 1;

            float maxBottom = 1f;
            for (EffectLayer layer : effect.effectLayers) {
                float scale = width / (float) Math.max(1L, layer.width);
                maxBottom = Math.max(maxBottom, layer.y + layer.height * scale);
            }
            return Math.max(1, Math.min(MAX_WEBVIEW_LAYER_SIZE, Math.round(maxBottom)));
        }

        private void pauseWebView() {
            if (webView != null) {
                webView.onPause();
                webView.pauseTimers();
            }
        }

        private void destroyWebView() {
            if (webView == null) return;
            webView.stopLoading();
            webView.onPause();
            webView.pauseTimers();
            webView.destroy();
            webView = null;
        }

        private void addLayer(EffectLayer layer) {
            SimpleDraweeView image = new SimpleDraweeView(getContext());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setClickable(false);
            image.setFocusable(false);
            image.setVisibility(View.VISIBLE);
            image.setAlpha(1f);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            EffectLayerView layerView = new EffectLayerView(layer, image);
            layerViews.add(layerView);
            addView(image, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            loadLayer(layerView);
        }

        private void loadLayer(EffectLayerView layerView) {
            Context context = getContext().getApplicationContext();
            Utils.threadPool.execute(() -> {
                boolean permitAcquired = false;
                try {
                    if (!layerView.active) return;
                    APNG_DECODE_PERMIT.acquire();
                    permitAcquired = true;
                    if (!layerView.active) return;
                    java.io.File file = effectCacheFile(context, layerView.layer.source);
                    if (!file.isFile() || file.length() == 0L) {
                        Http.simpleDownload(layerView.layer.source, file);
                    }
                    b.l.a.a drawable;
                    try (InputStream input = new FileInputStream(file)) {
                        drawable = b.l.a.a.a(input, null, null);
                    }
                    b.l.a.a decoded = drawable;
                    Utils.mainThread.post(() -> {
                        if (!layerView.active || layerView.image.getParent() != EffectOverlay.this) {
                            decoded.stop();
                            return;
                        }
                        layerView.setApng(decoded);
                    });
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    Log.w(TAG, "APNG effect decode failed; using image fallback: "
                            + layerView.layer.source, error);
                    Utils.mainThread.post(() -> {
                        if (layerView.active && layerView.image.getParent() == EffectOverlay.this) {
                            if (error instanceof OutOfMemoryError) {
                                layerView.releaseImage();
                                layerView.image.setVisibility(View.INVISIBLE);
                            } else {
                                layerView.loadStatic();
                            }
                        }
                    });
                } finally {
                    if (permitAcquired) APNG_DECODE_PERMIT.release();
                }
            });
        }

        private void layoutLayers() {
            if (getWidth() <= 0) return;
            for (EffectLayerView layerView : layerViews) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                        layerView.image.getLayoutParams();
                params.gravity = Gravity.TOP | Gravity.START;
                params.leftMargin = 0;
                params.topMargin = 0;
                params.width = Math.max(1, getWidth());
                float aspect = layerView.imageWidth > 0 && layerView.imageHeight > 0
                        ? layerView.imageHeight / (float) layerView.imageWidth
                        : layerView.layer.height / (float) layerView.layer.width;
                params.height = Math.max(1, Math.round(getWidth() * aspect));
                layerView.image.setLayoutParams(params);
                layerView.image.setZ(layerView.layer.zIndex);
            }
        }

        private void disposeLayers() {
            for (EffectLayerView layerView : layerViews) layerView.dispose();
        }

        private static void updateAspectRatio(SimpleDraweeView image, ImageInfo info) {
            if (info != null && info.getWidth() > 0 && info.getHeight() > 0) {
                image.setAspectRatio(info.getWidth() / (float) info.getHeight());
            }
        }

        private final class EffectLayerView {
            private final EffectLayer layer;
            private final SimpleDraweeView image;
            private Animatable animatable;
            private int imageWidth;
            private int imageHeight;
            private volatile boolean active = true;
            private final Runnable startTask = this::startNow;
            private final Runnable stopTask = this::finishCycle;

            private EffectLayerView(EffectLayer layer, SimpleDraweeView image) {
                this.layer = layer;
                this.image = image;
            }

            private void setImage(ImageInfo info, Animatable animatable) {
                setImageSize(info);
                this.animatable = animatable;
                restart();
            }

            private void setApng(b.l.a.a drawable) {
                image.setImageDrawable(drawable);
                imageWidth = drawable.getIntrinsicWidth();
                imageHeight = drawable.getIntrinsicHeight();
                animatable = drawable;
                layoutLayers();
                restart();
            }

            private void loadStatic() {
                MGImages.setImage(image, Collections.singletonList(layer.source),
                        0,
                        0,
                        false,
                        null,
                        MGImages.AlwaysUpdateChangeDetector.INSTANCE,
                        new c<ImageInfo>() {
                            @Override
                            public void onFinalImageSet(String id, ImageInfo info,
                                                        Animatable loadedAnimatable) {
                                setImage(info, loadedAnimatable);
                            }

                            @Override
                            public void onIntermediateImageSet(String id, ImageInfo info) {
                                setImageSize(info);
                            }

                            @Override
                            public void onFailure(String id, Throwable error) {
                                Log.e(TAG, "Effect layer fallback failed: " + id, error);
                            }
                        });
            }

            private void setImageSize(ImageInfo info) {
                if (info == null || info.getWidth() <= 0 || info.getHeight() <= 0) return;
                imageWidth = info.getWidth();
                imageHeight = info.getHeight();
                layoutLayers();
            }

            private void restart() {
                removeCallbacks(startTask);
                removeCallbacks(stopTask);
                stopNow();
                if (layer.start > 0L) {
                    // Keep delayed layers out of the first intro. Their first
                    // decoded frame can contain visible pixels even while paused.
                    image.setVisibility(View.INVISIBLE);
                    postDelayed(startTask, layer.start);
                } else {
                    startNow();
                }
            }

            private void startNow() {
                image.setVisibility(View.VISIBLE);
                if (animatable != null) animatable.start();
                if (layer.duration > 0L && (!layer.loop || layer.loopDelay > 0L)) {
                    postDelayed(stopTask, layer.duration);
                }
            }

            private void stopNow() {
                removeCallbacks(startTask);
                removeCallbacks(stopTask);
                if (animatable != null) animatable.stop();
            }

            private void finishCycle() {
                if (animatable != null) animatable.stop();
                if (!layer.loop) {
                    // Discord profile effects can contain a one-shot intro layer
                    // alongside a delayed looping layer. Do not leave the intro's
                    // final frame frozen over the looping renderer.
                    image.setVisibility(View.INVISIBLE);
                    return;
                }
                if (active && layer.loop && layer.loopDelay > 0L
                        && getWindowToken() != null && getWindowVisibility() == View.VISIBLE) {
                    postDelayed(startTask, layer.loopDelay);
                }
            }

            private void stop() {
                removeCallbacks(startTask);
                stopNow();
            }

            private void releaseImage() {
                stopNow();
                animatable = null;
                image.setImageDrawable(null);
                imageWidth = 0;
                imageHeight = 0;
            }

            private void dispose() {
                active = false;
                releaseImage();
            }
        }

        private static String effectHtml(Product effect) {
            StringBuilder html = new StringBuilder(
                    "<!doctype html><html><head>"
                            + "<meta name=\"viewport\" content=\"width=device-width,"
                            + "initial-scale=1,maximum-scale=1,user-scalable=no\">"
                            + "<style>"
                            + "html,body{margin:0;padding:0;width:100%;height:100%;"
                            + "overflow:hidden;background:transparent;}"
                            + "#effects{position:absolute;inset:0;overflow:hidden;"
                            + "pointer-events:none;}"
                            + ".effect{position:absolute;left:0;top:0;width:100%;"
                            + "height:auto;display:block;}"
                            + "</style>");
            for (EffectLayer layer : effect.effectLayers) {
                html.append("<link rel=\"preload\" as=\"image\" href=\"")
                        .append(htmlEscape(layer.source))
                        .append("\">");
            }
            html.append("</head><body><div id=\"effects\">");
            for (EffectLayer layer : effect.effectLayers) {
                html.append("<img class=\"effect\" data-src=\"")
                        .append(htmlEscape(layer.source))
                        .append("\" data-start=\"")
                        .append(layer.start)
                        .append("\"");
                if (layer.start == 0L) {
                    html.append(" src=\"")
                            .append(htmlEscape(layer.source))
                            .append("\"");
                }
                html.append(" style=\"left:")
                        .append(layer.x)
                        .append("px;top:")
                        .append(layer.y)
                        .append("px;width:100%;z-index:")
                        .append(layer.zIndex)
                        .append("\" aria-hidden=\"true\">");
            }
            html.append("</div><script>"
                    + "var effectTimers=[];"
                    + "function startEffects(){"
                    + "effectTimers.forEach(function(timer){window.clearTimeout(timer);});"
                    + "effectTimers=[];"
                    + "document.querySelectorAll('[data-src]').forEach(function(img){"
                    + "var start=Number(img.getAttribute('data-start'))||0;"
                    + "effectTimers.push(window.setTimeout(function(){"
                    + "img.src=img.getAttribute('data-src');"
                    + "},start));"
                    + "});"
                    + "}"
                    + "window.restartEffects=function(){"
                    + "document.querySelectorAll('[data-src]').forEach(function(img){"
                    + "img.removeAttribute('src');"
                    + "});"
                    + "window.setTimeout(startEffects,0);"
                    + "};"
                    + "startEffects();"
                    + "</script></body></html>");
            return html.toString();
        }

        private static String htmlEscape(String value) {
            return value.replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("'", "&#39;");
        }

    }
}
