package com.github.yutaplug.profileeffects;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.WebView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
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
import androidx.core.widget.NestedScrollView;

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

@SuppressWarnings({"unused", "unchecked"})
@AliucordPlugin
public final class ProfileEffects extends Plugin {
    private static final String TAG = "ProfileEffects";
    private static final String CDN = "https://cdn.discordapp.com";
    private static final long CACHE_DURATION = 10L * 60L * 1000L;

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
        patcher.patch(
                UserProfileHeaderView.class,
                "updateViewState",
                new Class<?>[]{UserProfileHeaderViewModel.ViewState.Loaded.class},
                new Hook(frame -> {
                    UserProfileHeaderView header = (UserProfileHeaderView) frame.thisObject;
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
                String staticFrame = string(item.get("staticFrameSrc"));
                String reducedMotion = string(item.get("reducedMotionSrc"));
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
        // Keep the frame container as a sibling of the native header. This matches the
        // stable Android sheet layout and avoids measuring the full-size web asset as a
        // child of the wrap-content header.
        ViewGroup frameHost = header.getParent() instanceof ViewGroup
                ? (ViewGroup) header.getParent()
                : header;
        ProfileOverlay backOverlay = backOverlays.get(header);
        if (backOverlay == null) {
            backOverlay = new ProfileOverlay(header.getContext(), false);
            backOverlay.setClickable(false);
            attachFrameOverlay(frameHost, backOverlay, header, true);
            backOverlays.put(header, backOverlay);
        } else if (backOverlay.getParent() != frameHost) {
            ViewParent oldParent = backOverlay.getParent();
            if (oldParent instanceof ViewGroup) {
                ((ViewGroup) oldParent).removeView(backOverlay);
            }
            attachFrameOverlay(frameHost, backOverlay, header, true);
        }
        backOverlay.setFrame(profile.frame);

        ProfileOverlay frontOverlay = frontOverlays.get(header);
        if (frontOverlay == null) {
            frontOverlay = new ProfileOverlay(header.getContext(), true);
            frontOverlay.setClickable(false);
            attachFrameOverlay(frameHost, frontOverlay, header, false);
            frontOverlay.setElevation(1000f);
            frontOverlays.put(header, frontOverlay);
        } else if (frontOverlay.getParent() != frameHost) {
            ViewParent oldParent = frontOverlay.getParent();
            if (oldParent instanceof ViewGroup) {
                ((ViewGroup) oldParent).removeView(frontOverlay);
            }
            attachFrameOverlay(frameHost, frontOverlay, header, false);
        }
        frameHost.bringChildToFront(frontOverlay);
        frontOverlay.setFrame(profile.frame);

        ViewGroup effectHost = findEffectHost(header, frameHost);
        EffectOverlay effectOverlay = effectOverlays.get(header);
        if (effectOverlay == null) {
            effectOverlay = new EffectOverlay(header.getContext());
            effectOverlay.setClickable(false);
            attachEffectOverlay(effectHost, effectOverlay);
            effectOverlays.put(header, effectOverlay);
        } else if (effectOverlay.getParent() != effectHost) {
            ViewParent oldParent = effectOverlay.getParent();
            if (oldParent instanceof ViewGroup) {
                ((ViewGroup) oldParent).removeView(effectOverlay);
            }
            attachEffectOverlay(effectHost, effectOverlay);
        }
        effectOverlay.setEffect(profile.effect);
    }

    private static ViewGroup findEffectHost(UserProfileHeaderView header, ViewGroup fallback) {
        ViewParent parent = header.getParent();
        while (parent instanceof View) {
            if (parent instanceof NestedScrollView) {
                NestedScrollView scrollView = (NestedScrollView) parent;
                View child = scrollView.getChildCount() == 0 ? null : scrollView.getChildAt(0);
                return child instanceof ViewGroup ? (ViewGroup) child : fallback;
            }
            parent = parent.getParent();
        }
        return fallback;
    }

    private static void attachFrameOverlay(ViewGroup host, ProfileOverlay overlay,
                                           UserProfileHeaderView header, boolean back) {
        host.setClipChildren(false);
        host.setClipToPadding(false);
        header.setClipChildren(false);
        header.setClipToPadding(false);

        // The frame intentionally extends above and below the profile header. Discord's
        // web renderer leaves these ancestors unclipped, so mirror that behavior here.
        ViewParent parent = host.getParent();
        for (int depth = 0; depth < 4 && parent instanceof ViewGroup; depth++) {
            ViewGroup ancestor = (ViewGroup) parent;
            ancestor.setClipChildren(false);
            ancestor.setClipToPadding(false);
            parent = ancestor.getParent();
        }

        if (host instanceof FrameLayout) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            host.addView(overlay, back ? 0 : host.getChildCount(), params);
        } else if (host instanceof ConstraintLayout) {
            ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
            params.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID;
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            host.addView(overlay, back ? 0 : host.getChildCount(), params);
        } else {
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            host.addView(overlay, back ? 0 : host.getChildCount(), params);
        }
    }

    private static void attachEffectOverlay(ViewGroup host, EffectOverlay overlay) {
        host.setClipChildren(false);
        host.setClipToPadding(false);
        ViewParent parent = host.getParent();
        for (int depth = 0; depth < 3 && parent instanceof ViewGroup; depth++) {
            ViewGroup ancestor = (ViewGroup) parent;
            ancestor.setClipChildren(false);
            ancestor.setClipToPadding(false);
            parent = ancestor.getParent();
        }

        // Effects are a middle layer over the profile backgrounds. The outer sheet
        // contains opaque section backgrounds, so putting this at index 0 hides it.
        // Keep it above the sheet content; the asset itself is transparent and its
        // WebView clips to the profile-effect viewport.
        if (host instanceof FrameLayout) {
            host.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            host.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
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
            layers.add(new FrameLayer(
                    id,
                    "front".equalsIgnoreCase(string(object.get("order"))),
                    "bottom".equalsIgnoreCase(string(object.get("anchor")))
            ));
        }
        return layers;
    }

    private static String firstEffectSource(Object raw) {
        for (Object value : asList(raw)) {
            Map<?, ?> effect = asMap(value);
            if (effect == null) continue;
            String source = string(effect.get("src"));
            if (!source.isEmpty()) return source;
        }
        return "";
    }

    private static List<EffectLayer> parseEffects(Object raw) {
        List<EffectLayer> effects = new ArrayList<>();
        for (Object value : asList(raw)) {
            Map<?, ?> object = asMap(value);
            if (object == null) continue;
            String source = string(object.get("src"));
            if (source.isEmpty()) continue;

            Map<?, ?> position = asMap(object.get("position"));
            effects.add(new EffectLayer(
                    source,
                    number(object.get("start"), 0L),
                    number(object.get("duration"), 0L),
                    booleanValue(object.get("loop")),
                    number(position == null ? null : position.get("x"), 0L),
                    number(position == null ? null : position.get("y"), 0L),
                    number(object.get("width"), 450L),
                    number(object.get("height"), 880L),
                    number(object.get("zIndex"), 0L)));
        }
        effects.sort(Comparator.comparingLong(effect -> effect.zIndex));
        return effects;
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
        private final long x;
        private final long y;
        private final long width;
        private final long height;
        private final long zIndex;

        private EffectLayer(String source, long start, long duration, boolean loop,
                            long x, long y, long width, long height, long zIndex) {
            this.source = source;
            this.start = Math.max(0L, start);
            this.duration = Math.max(0L, duration);
            this.loop = loop;
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
        private final boolean bottom;

        private FrameLayer(long id, boolean front, boolean bottom) {
            this.id = id;
            this.front = front;
            this.bottom = bottom;
        }
    }

    private static final class ProfileOverlay extends FrameLayout {
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
                    // The native sheet does not have the web client's full-card frame
                    // container. The bottom staple would otherwise become a large rail
                    // over the profile body, so keep the reliable top frame only.
                    if (!layer.bottom && layer.front == front) addFrameLayer(frame, layer);
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
                int height = layerView.imageWidth > 0 && layerView.imageHeight > 0
                        ? Math.max(1, Math.round(layerWidth
                        * (layerView.imageHeight / (float) layerView.imageWidth)))
                        : Math.max(1, Math.round(layerWidth * 0.75f));
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                        layerView.image.getLayoutParams();
                params.width = layerWidth;
                params.height = height;
                params.leftMargin = -Math.round(metrics.overflowHorizontal * scale);
                if (layerView.layer.bottom) {
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

    private static final class EffectOverlay extends FrameLayout {
        private Product effect;
        private WebView webView;

        private EffectOverlay(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        private void setEffect(Product effect) {
            if (this.effect == effect) return;
            this.effect = effect;
            rebuild();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            restartAnimation();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == View.VISIBLE && getWindowToken() != null) restartAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (webView != null) {
                webView.onPause();
                webView.pauseTimers();
            }
            super.onDetachedFromWindow();
        }

        private void restartAnimation() {
            if (webView == null || effect == null || effect.effectLayers.isEmpty()) return;
            // Keep the already-loaded WebView and reset its image elements instead of
            // reloading the page. A full reload makes every APNG layer wait on the CDN
            // again when the same profile sheet is opened a second time.
            webView.onResume();
            webView.resumeTimers();
            webView.evaluateJavascript(
                    "(function(){if(window.restartEffects)window.restartEffects();})();",
                    null);
        }

        private void rebuild() {
            removeAllViews();
            webView = null;
            if (effect == null || effect.effectSource.isEmpty()) return;

            if (!effect.effectLayers.isEmpty()) {
                addStaticEffectLayer(effect);
                webView = new WebView(getContext());
                webView.setBackgroundColor(Color.TRANSPARENT);
                webView.setAlpha(1f);
                webView.setClickable(false);
                webView.setFocusable(false);
                webView.setVerticalScrollBarEnabled(false);
                webView.setHorizontalScrollBarEnabled(false);
                webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(false);
                webView.getSettings().setLoadsImagesAutomatically(true);
                webView.getSettings().setSupportZoom(false);
                addView(webView, new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                webView.loadDataWithBaseURL(
                        CDN + "/",
                        effectHtml(effect),
                        "text/html",
                        "UTF-8",
                        null);
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

        private void addStaticEffectLayer(Product effect) {
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
                            Log.e(TAG, "Static effect image failed: " + id, error);
                        }
                    });
        }

        private static void updateAspectRatio(SimpleDraweeView image, ImageInfo info) {
            if (info != null && info.getWidth() > 0 && info.getHeight() > 0) {
                image.setAspectRatio(info.getWidth() / (float) info.getHeight());
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
            // Fetch every layer while the first layer is decoding so later skull
            // layers do not wait for a separate network request.
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
                    // Put the first layer directly on the element so Chromium starts
                    // decoding it while parsing the document, instead of waiting for
                    // the JavaScript timer to run after the first layout pass.
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
