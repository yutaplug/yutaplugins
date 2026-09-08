package com.github.yutaplug.newdiscordbadges;

import android.content.Context;
import android.util.Base64;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.GsonUtils;
import com.aliucord.api.rn.user.ProfileBadge;
import com.aliucord.api.rn.user.RNUserProfile;
import com.discord.models.user.User;
import com.discord.databinding.WidgetUserSheetBinding;
import com.discord.widgets.user.usersheet.WidgetUserSheet;
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel;
import com.discord.widgets.user.Badge;
import com.discord.widgets.user.profile.UserProfileHeaderView;
import com.discord.widgets.user.profile.UserProfileHeaderViewModel;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

@SuppressWarnings({"unused", "unchecked", "rawtypes"})
@AliucordPlugin
public final class NewDiscordBadges extends Plugin {
    private static final String CDN = "https://cdn.discordapp.com";
    private static final String RN_ASSET_CDN =
            CDN + "/assets/mana/asset-library/generated/";
    // The supplied RN client is Discord 343.12 (build 343012). Aliucord's
    // compatibility helper identifies itself as the much older build 283.10.
    private static final String RN_BADGE_USER_AGENT = "Discord-Android/343012";
    private static final String RN_BADGE_CLIENT_VERSION = "343.12 - Stable";
    private static final int RN_BADGE_BUILD_NUMBER = 343012;
    private static final long CACHE_DURATION_MS = 10L * 60L * 1000L;
    // These private Discord fields are stable in the pinned client.
    private static final String BADGES_ADAPTER_FIELD = "badgesAdapter";
    private static final String RECYCLER_ADAPTER_DATA_FIELD = "data";
    private static final String PLUGIN_BADGE_PREFIX = "newdiscordbadges:";
    private static final String BADGE_VIEW_HOLDER_BINDING_FIELD = "binding";
    private static final String BADGE_IMAGE_FIELD = "b";

    // These are the two Nitro badge generations currently used by Discord.
    private static final int[] NITRO_MONTHS = {1, 3, 6, 12, 24, 36, 60, 72};
    private static final String[] NITRO_LABELS = {
            "Bronze", "Silver", "Gold", "Platinum",
            "Diamond", "Emerald", "Ruby", "Opal"
    };
    private static final String[] NITRO_V1_IDS = {
            "premium_tenure_1_month", "premium_tenure_3_month",
            "premium_tenure_6_month", "premium_tenure_12_month",
            "premium_tenure_24_month", "premium_tenure_36_month",
            "premium_tenure_60_month", "premium_tenure_72_month"
    };
    private static final String[] NITRO_V1_ICONS = {
            "19a1562a9ce21227116624daaf69e450",
            "3d533bea11ec4f7bdbf23a4bdc7a373f",
            "850a7f5909f9d54d6ad986c096937911",
            "3393b2ca6e25e40d4bb3bd23d60d0cdd",
            "7c85d3834db671b01e6d0fd1538663a0",
            "2447661dbda1a992a616a583f8492ae3",
            "ddb868782712aa9f4ef98bef4d6e14f6",
            "cff7119d4417261c3f52fde8a94ba8e5"
    };
    private static final String[] NITRO_V2_IDS = {
            "premium_tenure_1_month_v2", "premium_tenure_3_month_v2",
            "premium_tenure_6_month_v2", "premium_tenure_12_month_v2",
            "premium_tenure_24_month_v2", "premium_tenure_36_month_v2",
            "premium_tenure_60_month_v2", "premium_tenure_72_month_v2"
    };
    private static final String[] NITRO_V2_ICONS = {
            "4f33c4a9c64ce221936bd256c356f91f",
            "4514fab914bdbfb4ad2fa23df76121a6",
            "2895086c18d5531d499862e41d1155a6",
            "0334688279c8359120922938dcb1d6f8",
            "0d61871f72bb9a33a7ae568c1fb4f20a",
            "11e2d339068b55d3a506cff34d3780f3",
            "cd5e2cfd9d7f27a8cdcd3e8a8d5dc9f4",
            "5b154df19c53dce2af92c9b61e6be5e2"
    };
    private static final String[] RN_NITRO_SMALL_ASSETS = {
            "NitroBronzeBadgeSmallBadge-2x.png",
            "NitroSilverBadgeSmallBadge-2x.png",
            "NitroGoldBadgeSmallBadge-2x.png",
            "NitroPlatinumBadgeSmallBadge-2x.png",
            "NitroDiamondBadgeSmallBadge-2x.png",
            "NitroEmeraldBadgeSmallBadge-2x.png",
            "NitroRubyBadgeSmallBadge-2x.png",
            "NitroOpalBadgeSmallBadge-2x.png"
    };
    private static final String[] RN_GIFTING_SMALL_ASSETS = {
            "GiftingTier1SmallBadge-2x.png",
            "GiftingTier2SmallBadge-2x.png",
            "GiftingTier3SmallBadge-2x.png",
            "GiftingTier4SmallBadge-2x.png",
            "GiftingTier5SmallBadge-2x.png",
            "GiftingTier6SmallBadge-2x.png"
    };

    private static final String GIFTING_ICON = "6b999dd181afe4868cf2f22681ec23b5";
    private static final String[] GIFT_IDS = {
            "gifting_patron", "gifting_champion", "gifting_luminary",
            "gifting_icon", "gifting_hero", "gifting_legend"
    };
    private static final String[] GIFT_LABELS = {
            "Patron", "Champion", "Luminary", "Icon", "Hero", "Legend"
    };
    private static final int[] GIFT_MILESTONES = {1, 2, 3, 6, 10, 20};

    private final Map<String, CachedBadges> badgeCache = new ConcurrentHashMap<>();
    private final Set<String> requestsInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UserProfileHeaderView, String> boundProfiles =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, UserProfileHeaderView> profileAdapters =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> updatingAdapters =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private volatile boolean running;
    private volatile long generation;

    @Override
    public void start(Context context) throws Throwable {
        running = true;
        generation++;

        // Keep the remote badges in every subsequent native adapter update. Discord and
        // other badge providers can call setData again while a profile sheet is reused.
        try {
            patcher.patch(
                    com.discord.utilities.views.SimpleRecyclerAdapter.class,
                    "setData",
                    new Class<?>[]{List.class},
                    new PreHook(frame -> {
                        try {
                            if (frame.args.length == 0
                                    || !(frame.args[0] instanceof List<?>)
                                    || updatingAdapters.contains(frame.thisObject)) {
                                return;
                            }
                            UserProfileHeaderView header = profileAdapters.get(frame.thisObject);
                            if (header == null) return;

                            String key = boundProfiles.get(header);
                            CachedBadges cached = key == null ? null : badgeCache.get(key);
                            if (!isFresh(cached)) return;

                            List<Object> merged = mergeBadges(
                                    (List<?>) frame.args[0], cached.badges);
                            if (!merged.equals(frame.args[0])) frame.args[0] = merged;
                        } catch (RuntimeException error) {
                            logger.error("Could not merge remote profile badges", error);
                        }
                    })
            );
        } catch (RuntimeException error) {
            logger.error("Could not keep profile badges during native adapter updates", error);
        }

        // Build the list before either profile-sheet variant hands it to its adapter.
        // This is the earliest stable point where the RN profile badge model is still
        // available, including the single `gifting` badge id used for all six tiers.
        try {
            patcher.patch(
                    Badge.Companion.class,
                    "getBadgesForUser",
                    new Class<?>[]{
                            User.class,
                            com.discord.api.user.UserProfile.class,
                            boolean.class,
                            boolean.class,
                            Context.class
                    },
                    new Hook(frame -> {
                        if (!(frame.getResult() instanceof List<?>)) return;
                        if (frame.args.length < 2
                                || !(frame.args[1] instanceof com.discord.api.user.UserProfile)) {
                            return;
                        }
                        List<RemoteBadge> rnBadges = parseRnBadges(
                                (com.discord.api.user.UserProfile) frame.args[1]
                        );
                        if (rnBadges.isEmpty()) return;
                        logger.debug(
                                "Profile badges: " + rnBadges.size()
                                        + ", gifting=" + hasEvolvingGiftingBadge(rnBadges)
                        );
                        frame.setResult(mergeBadges((List<?>) frame.getResult(), rnBadges));
                    })
            );
        } catch (RuntimeException error) {
            logger.error("Could not hook the native profile badge builder", error);
        }

        patcher.patch(
                UserProfileHeaderView.BadgeViewHolder.class,
                "bind",
                new Class<?>[]{Badge.class},
                new Hook(frame -> {
                    if (!(frame.thisObject instanceof UserProfileHeaderView.BadgeViewHolder)
                            || frame.args.length == 0
                            || !(frame.args[0] instanceof Badge)) {
                        return;
                    }
                    Badge badge = (Badge) frame.args[0];
                    if (!isPluginBadge(badge)) return;

                    android.widget.ImageView image;
                    try {
                        image = getBadgeImage(
                                (UserProfileHeaderView.BadgeViewHolder) frame.thisObject
                        );
                    } catch (NoSuchFieldException | IllegalAccessException | SecurityException error) {
                        logger.error("Could not access the native profile badge image", error);
                        return;
                    }
                    if (image == null) return;

                    String imageUrl = pluginBadgeImageUrl(badge);
                    if (imageUrl.isEmpty()) return;
                    image.setVisibility(View.VISIBLE);
                    image.setContentDescription(badge.getTooltip());
                    image.setOnClickListener(view -> Utils.showToast(String.valueOf(badge.getTooltip())));
                    com.aliucord.coreplugins.badges.UtilsKt.setCacheableImage(
                            image,
                            imageUrl
                    );
                })
        );
        patcher.patch(
                UserProfileHeaderView.class,
                "updateViewState",
                new Class<?>[]{UserProfileHeaderViewModel.ViewState.Loaded.class},
                new Hook(frame -> {
                    if (!(frame.thisObject instanceof UserProfileHeaderView)
                            || frame.args.length == 0
                            || !(frame.args[0] instanceof UserProfileHeaderViewModel.ViewState.Loaded)) {
                        return;
                    }
                    UserProfileHeaderView header = (UserProfileHeaderView) frame.thisObject;
                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) frame.args[0];
                    User user = state.getUser();
                    if (user == null || user.getId() <= 0L) return;
                    Long guildId = null;
                    if (state.getGuildMember() != null
                            && state.getGuildMember().getGuildId() > 0L) {
                        guildId = state.getGuildMember().getGuildId();
                    }
                    // This hook runs after Discord has finished replacing the native
                    // adapter data. Bind synchronously so an old posted callback cannot
                    // overwrite a newer custom-status/profile state.
                    bindProfileHeader(header, user, guildId, parseRnBadges(state.getUserProfile()));
                })
        );

        // Modern profile sheets populate the header after the header callback.
        // Bind from the sheet once its real header is attached as well.
        try {
            patcher.patch(
                    WidgetUserSheet.class,
                    "configureUI",
                    new Class<?>[]{WidgetUserSheetViewModel.ViewState.class},
                    new Hook(frame -> {
                        if (frame.args.length == 0
                                || !(frame.args[0] instanceof WidgetUserSheetViewModel.ViewState.Loaded)
                                || !(frame.thisObject instanceof WidgetUserSheet)) {
                            return;
                        }
                        try {
                            bindSheetHeader(
                                    (WidgetUserSheet) frame.thisObject,
                                    (WidgetUserSheetViewModel.ViewState.Loaded) frame.args[0]
                            );
                        } catch (RuntimeException error) {
                            logger.error("Could not bind badges in the modern profile sheet", error);
                        }
                    })
            );
        } catch (RuntimeException error) {
            logger.error("Could not hook the modern profile-sheet UI", error);
        }
    }

    private void bindSheetHeader(
            WidgetUserSheet sheet, WidgetUserSheetViewModel.ViewState.Loaded state) {
        WidgetUserSheetBinding binding = WidgetUserSheet.access$getBinding$p(sheet);
        if (binding == null || binding.J == null) return;

        Long guildId = state.getCurrentGuildId();
        if (state.getGuildId() != null && state.getGuildId() > 0L) {
            guildId = state.getGuildId();
        }
        if (state.getGuildMember() != null && state.getGuildMember().getGuildId() > 0L) {
            guildId = state.getGuildMember().getGuildId();
        }

        User user = state.getUser();
        List<RemoteBadge> rnBadges = parseRnBadges(state.getUserProfile());
        Long boundGuildId = guildId;
        if (user != null) {
            // configureUI runs after the native header has been initialized.
            bindProfileHeader(binding.J, user, boundGuildId, rnBadges);
        }
    }

    private void bindProfileHeader(
            UserProfileHeaderView header,
            UserProfileHeaderViewModel.ViewState.Loaded state) {
        Long guildId = null;
        if (state.getGuildMember() != null && state.getGuildMember().getGuildId() > 0L) {
            guildId = state.getGuildMember().getGuildId();
        }
        bindProfileHeader(header, state.getUser(), guildId, parseRnBadges(state.getUserProfile()));
    }

    private void bindProfileHeader(UserProfileHeaderView header, User user, Long guildId) {
        bindProfileHeader(header, user, guildId, Collections.emptyList());
    }

    private void bindProfileHeader(
            UserProfileHeaderView header,
            User user,
            Long guildId,
            List<RemoteBadge> rnBadges) {
        if (user == null || user.getId() <= 0L) return;

        String key = profileKey(user.getId(), guildId);
        String previousKey = boundProfiles.put(header, key);
        boolean newProfile = !key.equals(previousKey);
        registerProfileAdapter(header);

        CachedBadges cached = badgeCache.get(key);
        boolean freshCache = isFresh(cached);
        List<RemoteBadge> knownBadges = freshCache
                ? combineBadges(rnBadges, cached.badges) : rnBadges;
        logger.debug(
                "Binding profile badges: rn=" + rnBadges.size()
                        + ", cached=" + knownBadges.size()
                        + ", gifting=" + hasEvolvingGiftingBadge(knownBadges)
                        + ", ids=" + badgeIds(knownBadges)
        );
        if (newProfile) {
            // A newly created header is not registered with the setData pre-hook
            // until this method runs, so seed it once when cached/RN badges exist.
            if (!rnBadges.isEmpty()) applyRemoteBadges(header, rnBadges);
            if (freshCache) {
                applyRemoteBadges(header, knownBadges);
            } else if (rnBadges.isEmpty()) {
                applyRemoteBadges(header, Collections.emptyList());
            }
        }
        // The native badge builder and the setData pre-hook already merge these
        // entries before RecyclerView receives its data. Mutating the adapter here
        // would notify it again on every custom-status/profile-state update.
        // A fresh response is authoritative, including a response with no gifting
        // badge. Retrying that negative result on every render causes profile sheets
        // to refresh repeatedly and can make custom status appear to flicker.
        if (!freshCache) {
            requestBadges(header, user.getId(), guildId, key);
        }
    }

    private static boolean isFresh(CachedBadges cached) {
        return cached != null
                && System.currentTimeMillis() - cached.fetchedAt < CACHE_DURATION_MS;
    }

    private void requestBadges(
            UserProfileHeaderView header, long userId, Long guildId, String key) {
        if (!requestsInFlight.add(key)) return;

        long requestGeneration = generation;
        WeakReference<UserProfileHeaderView> headerReference = new WeakReference<>(header);
        Utils.threadPool.execute(() -> {
            List<RemoteBadge> result = Collections.emptyList();

            try {
                // The mobile API exposes profile badges on the profile response.
                // /users/{id}/badges is a desktop/catalog route and returns 404 here.
                result = fetchBadges(profileRoute(userId, guildId), true);
                if (!hasEvolvingGiftingBadge(result)) {
                    // The RN client requests this endpoint without the web-only
                    // `type=popout` parameter. That response can include gifting when
                    // the legacy popout response contains only Nitro and regular badges.
                    result = combineBadges(
                            result,
                            fetchBadges(rnProfileRoute(userId, guildId), true)
                    );
                }
                if (!hasEvolvingGiftingBadge(result)) {
                    // Some profile consumers request the full profile variant. It
                    // uses the same badge model but can have a different rollout.
                    result = combineBadges(
                            result,
                            fetchBadges(fullProfileRoute(userId, guildId), true)
                    );
                }
                if (!hasEvolvingGiftingBadge(result)) {
                    // The current native profile request uses the app's current
                    // User-Agent and super-properties. The legacy RN helper keeps an
                    // older fixed User-Agent for compatibility, and the profile
                    // endpoint can omit newly rolled-out badges for that client.
                    result = combineBadges(
                            result,
                            fetchBadges(profileRoute(userId, guildId), false)
                    );
                }
                // Some profile-sheet states carry a guild id. The guild-scoped response
                // can contain Nitro and regular badges while omitting the global gifting
                // badge, so also check the global response when gifting is absent.
                if (guildId != null && !hasEvolvingGiftingBadge(result)) {
                    result = combineBadges(
                            result,
                            fetchBadges(profileRoute(userId, null), true)
                    );
                    if (!hasEvolvingGiftingBadge(result)) {
                        result = combineBadges(
                                result,
                                fetchBadges(rnProfileRoute(userId, null), true)
                        );
                    }
                    if (!hasEvolvingGiftingBadge(result)) {
                        result = combineBadges(
                                result,
                                fetchBadges(profileRoute(userId, null), false)
                        );
                    }
                    if (!hasEvolvingGiftingBadge(result)) {
                        result = combineBadges(
                                result,
                                fetchBadges(fullProfileRoute(userId, null), true)
                        );
                    }
                }
            } catch (Exception error) {
                if (guildId != null) {
                    try {
                        result = combineBadges(
                                result,
                                fetchBadges(profileRoute(userId, null), true)
                        );
                    } catch (Exception fallbackError) {
                        logger.error("Failed to load Discord profile badges", fallbackError);
                    }
                } else {
                    logger.error("Failed to load Discord profile badges", error);
                }
            }

            if (!running || generation != requestGeneration) {
                requestsInFlight.remove(key);
                return;
            }

            requestsInFlight.remove(key);
            logger.debug(
                    "Fetched profile badges: " + result.size()
                            + ", gifting=" + hasEvolvingGiftingBadge(result)
                            + ", ids=" + badgeIds(result)
            );

            final List<RemoteBadge> fetched = result;
            badgeCache.put(key, new CachedBadges(fetched));

            // Cache empty responses too. Otherwise every native state update retries
            // all profile variants and can make the sheet appear to refresh.
            if (fetched.isEmpty()) return;

            UserProfileHeaderView target = headerReference.get();
            if (target == null) return;
            Utils.mainThread.post(() -> applyFetchedBadges(target, key, fetched));
        });
    }

    private void applyFetchedBadges(
            UserProfileHeaderView header,
            String key,
            List<RemoteBadge> badges) {
        if (!running) return;

        List<UserProfileHeaderView> targets = new ArrayList<>();
        synchronized (boundProfiles) {
            for (Map.Entry<UserProfileHeaderView, String> entry : boundProfiles.entrySet()) {
                if (key.equals(entry.getValue())) targets.add(entry.getKey());
            }
        }
        if (targets.isEmpty() && key.equals(boundProfiles.get(header))) targets.add(header);
        for (UserProfileHeaderView target : targets) applyRemoteBadges(target, badges);
    }

    private void applyRemoteBadges(UserProfileHeaderView header, List<RemoteBadge> remoteBadges) {
        try {
            Object badgesAdapter = getBadgesAdapter(header);
            if (!(badgesAdapter instanceof RecyclerView.Adapter<?>)) return;

            Field dataField = findField(badgesAdapter.getClass(), RECYCLER_ADAPTER_DATA_FIELD);
            dataField.setAccessible(true);
            Object value = dataField.get(badgesAdapter);
            if (!(value instanceof List<?>)) return;

            List<Object> badges = mergeBadges((List<?>) value, remoteBadges);
            if (!badges.equals(value)) {
                // Do not call SimpleRecyclerAdapter.setData here: that method is also
                // used by Discord while its core DiscordBadges hook is appending RN
                // entries. Replace the backing list and notify once after the complete
                // merge, avoiding re-entry and stale row counts.
                dataField.set(badgesAdapter, badges);
                ((RecyclerView.Adapter<?>) badgesAdapter).notifyDataSetChanged();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            logger.error("Could not update profile badges", error);
        }
    }

    private void registerProfileAdapter(UserProfileHeaderView header) {
        try {
            Object badgesAdapter = getBadgesAdapter(header);
            if (badgesAdapter != null) profileAdapters.put(badgesAdapter, header);
        } catch (ReflectiveOperationException | RuntimeException error) {
            logger.error("Could not register the native profile badge adapter", error);
        }
    }

    private static Object getBadgesAdapter(UserProfileHeaderView header)
            throws NoSuchFieldException, IllegalAccessException {
        Field badgesAdapterField = UserProfileHeaderView.class
                .getDeclaredField(BADGES_ADAPTER_FIELD);
        badgesAdapterField.setAccessible(true);
        return badgesAdapterField.get(header);
    }

    private static List<Object> mergeBadges(
            List<?> nativeBadges, List<RemoteBadge> remoteBadges) {
        List<Object> badges = new ArrayList<>(nativeBadges);
        boolean hasEvolvingNitro = hasEvolvingNitro(remoteBadges);
        Set<String> remoteBadgeIdentities = new HashSet<>();
        for (RemoteBadge badge : remoteBadges) {
            if (!isEvolvingNitroBadge(badge) && !isEvolvingGiftingBadge(badge)) continue;
            remoteBadgeIdentities.add(normalizeBadgeIdentity(badgeImageUrl(badge)));
            remoteBadgeIdentities.add(normalizeBadgeIdentity(iconFallbackUrl(badge.icon)));
            remoteBadgeIdentities.add(normalizeBadgeIdentity(badge.icon));
            remoteBadgeIdentities.add(normalizeBadgeIdentity(badge.id));
        }
        Set<String> nativeBadgeIdentities = new HashSet<>();
        for (Iterator<Object> iterator = badges.iterator(); iterator.hasNext();) {
            Object badge = iterator.next();
            boolean remove = isPluginBadge(badge)
                    || (hasEvolvingNitro && isNativeNitroBadge(badge))
                    || isRemoteBadgeDuplicate(badge, remoteBadgeIdentities, remoteBadges);
            if (!remove && badge instanceof Badge) {
                String objectType = ((Badge) badge).getObjectType();
                if (objectType != null && !objectType.isEmpty()) {
                    remove = !nativeBadgeIdentities.add(normalizeBadgeIdentity(objectType));
                }
            }
            if (remove) {
                iterator.remove();
            }
        }

        Set<String> existingBadgeImages = new HashSet<>();
        for (Object badge : badges) {
            if (!(badge instanceof Badge)) continue;
            String objectType = ((Badge) badge).getObjectType();
            if (objectType != null) existingBadgeImages.add(normalizeBadgeIdentity(objectType));
        }
        for (RemoteBadge badge : remoteBadges) {
            // Aliucord's built-in DiscordBadges plugin already renders regular
            // profile badges. Inject only evolving badges the old client lacks.
            if (!isEvolvingNitroBadge(badge) && !isEvolvingGiftingBadge(badge)) continue;
            String imageUrl = badgeImageUrl(badge);
            if (!existingBadgeImages.add(normalizeBadgeIdentity(imageUrl))) continue;
            badges.add(new Badge(
                    0,
                    PLUGIN_BADGE_PREFIX + imageUrl,
                    badge.description,
                    false,
                    imageUrl
            ));
        }
        return badges;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean isPluginBadge(Object value) {
        if (!(value instanceof Badge)) return false;
        CharSequence text = ((Badge) value).getText();
        return text != null && text.toString().startsWith(PLUGIN_BADGE_PREFIX);
    }

    private static String pluginBadgeImageUrl(Badge badge) {
        CharSequence text = badge.getText();
        if (text == null) return "";
        String value = text.toString();
        if (!value.startsWith(PLUGIN_BADGE_PREFIX)) return "";

        String imageUrl = value.substring(PLUGIN_BADGE_PREFIX.length());
        return imageUrl.startsWith("http://") || imageUrl.startsWith("https://")
                ? imageUrl : iconFallbackUrl(imageUrl);
    }

    private static android.widget.ImageView getBadgeImage(
            UserProfileHeaderView.BadgeViewHolder holder)
            throws NoSuchFieldException, IllegalAccessException {
        Field bindingField = UserProfileHeaderView.BadgeViewHolder.class
                .getDeclaredField(BADGE_VIEW_HOLDER_BINDING_FIELD);
        bindingField.setAccessible(true);
        Object binding = bindingField.get(holder);
        if (binding == null) return null;

        Field imageField = findField(binding.getClass(), BADGE_IMAGE_FIELD);
        imageField.setAccessible(true);
        Object image = imageField.get(binding);
        return image instanceof android.widget.ImageView
                ? (android.widget.ImageView) image : null;
    }

    private static boolean isNativeNitroBadge(Object value) {
        return value instanceof Badge
                && ((Badge) value).getIcon() != 0
                && "PREMIUM".equals(((Badge) value).getObjectType());
    }

    private static boolean isRemoteBadgeDuplicate(
            Object value,
            Set<String> remoteBadgeIdentities,
            List<RemoteBadge> remoteBadges) {
        if (!(value instanceof Badge)) return false;
        String objectType = ((Badge) value).getObjectType();
        if (objectType == null || objectType.isEmpty()) return false;

        String normalizedObjectType = normalizeBadgeIdentity(objectType);
        if (remoteBadgeIdentities.contains(normalizedObjectType)) {
            return true;
        }
        for (RemoteBadge badge : remoteBadges) {
            if (!isEvolvingNitroBadge(badge) && !isEvolvingGiftingBadge(badge)) continue;
            if (badge.id.equalsIgnoreCase(objectType)) return true;
        }
        return false;
    }

    private Object requestJson(String route) throws Exception {
        return requestJson(route, true);
    }

    private List<RemoteBadge> fetchBadges(String route, boolean useRnHeaders) {
        try {
            return parseBadges(requestJson(route, useRnHeaders));
        } catch (Exception ignored) {
            // Profile variants are optional and differ between Discord rollouts.
            // A missing variant must not prevent the remaining variants from running.
            logger.debug("Profile badge response variant unavailable");
            return Collections.emptyList();
        }
    }

    private Object requestJson(String route, boolean useRnHeaders) throws Exception {
        try (Http.Request request = useRnHeaders
                ? Http.Request.newDiscordRNRequest(route)
                : Http.Request.newDiscordRequest(route)) {
            if (useRnHeaders) {
                request.setHeader("User-Agent", RN_BADGE_USER_AGENT);
                request.setHeader("X-Super-Properties", rnBadgeSuperProperties());
            }
            String fingerprint = com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE
                    .getFingerprint();
            if (fingerprint != null) request.setHeader("X-Fingerprint", fingerprint);
            // This feature header is returned by Discord for the profile payload used
            // by the current clients. Supplying it also enables the newer badge fields
            // when the legacy RN helper is used by an older Aliucord API layer.
            request.setHeader("X-Discord-Features", "user-profile");

            Http.Response response = request.execute();
            if (!response.ok()) {
                throw new IllegalStateException("HTTP " + response.statusCode);
            }
            Object body = GsonUtils.fromJson(response.text(), Object.class);
            logger.debug("Profile response contains gifting data: " + containsGiftingData(body));
            return body;
        }
    }

    private static String rnBadgeSuperProperties() {
        try {
            JSONObject properties = new JSONObject(
                    com.aliucord.utils.RNSuperProperties.getSuperProperties().toString()
            );
            properties.put("client_version", RN_BADGE_CLIENT_VERSION);
            properties.put("client_build_number", RN_BADGE_BUILD_NUMBER);
            properties.put("release_channel", "stable");
            return Base64.encodeToString(
                    properties.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
            );
        } catch (Exception error) {
            // Keep the helper's valid fallback if the compatibility object changes.
            return com.aliucord.utils.RNSuperProperties.getSuperPropertiesBase64();
        }
    }

    private static boolean containsGiftingData(Object raw) {
        if (raw instanceof String) return "gifting".equalsIgnoreCase((String) raw);
        if (raw instanceof List<?>) {
            for (Object value : (List<?>) raw) {
                if (containsGiftingData(value)) return true;
            }
            return false;
        }
        if (!(raw instanceof Map<?, ?>)) return false;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if ("gifting".equalsIgnoreCase(stringValue(entry.getKey()))
                    || containsGiftingData(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static List<RemoteBadge> parseBadges(Object... bodies) {
        List<RemoteBadge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object body : bodies) {
            appendBadges(body, result, seen, "");
        }
        if (result.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(result);
    }

    private static List<RemoteBadge> parseRnBadges(com.discord.api.user.UserProfile profile) {
        if (!(profile instanceof RNUserProfile)) return Collections.emptyList();

        List<RemoteBadge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ProfileBadge> badges = ((RNUserProfile) profile).getBadges();
        if (badges == null) return Collections.emptyList();
        for (ProfileBadge badge : badges) {
            if (badge == null) continue;
            appendRemoteBadge(
                    result,
                    seen,
                    badge.getId(),
                    badge.getDescription(),
                    badge.getIcon()
            );
        }
        return result.isEmpty()
                ? Collections.emptyList() : Collections.unmodifiableList(result);
    }

    private static void appendBadges(
            Object raw,
            List<RemoteBadge> result,
            Set<String> seen,
            String impliedId) {
        if (raw instanceof List<?>) {
            for (Object value : (List<?>) raw) {
                appendBadges(value, result, seen, "");
            }
            return;
        }
        if (raw instanceof String) {
            appendKnownBadge((String) raw, result, seen);
            return;
        }
        if (!(raw instanceof Map<?, ?>)) return;

        Map<?, ?> map = (Map<?, ?>) raw;
        if (isCatalogBadge(map)) appendCatalogBadge(map, result, seen);

        String id = firstString(map, "id", "badge_id", "badgeId");
        if (!id.isEmpty()) appendBadgeObject(map, result, seen);
        else if (!impliedId.isEmpty() && hasBadgeImage(map)) {
            // Some Android profile payloads use a badge map keyed by its id:
            // {"gifting": {"description": ..., "icon": ...}}. The web
            // payload uses an array with an explicit `id`, so preserve both forms.
            Map<String, Object> keyedBadge = new HashMap<>();
            keyedBadge.put("id", impliedId);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                keyedBadge.put(stringValue(entry.getKey()), entry.getValue());
            }
            appendBadgeObject(keyedBadge, result, seen);
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey()).toLowerCase(Locale.ROOT);
            if (isBadgeContainer(key)) {
                String childId = isBadgeIdKey(key) ? key : "";
                appendBadges(entry.getValue(), result, seen, childId);
            }
        }
    }

    private static boolean isBadgeIdKey(String key) {
        return "gifting".equals(key)
                || key.startsWith("gifting_")
                || key.startsWith("premium_tenure")
                || key.startsWith("account_age_")
                || key.startsWith("streaming_")
                || key.startsWith("game_time_")
                || key.startsWith("game_variety_");
    }

    private static boolean hasBadgeImage(Map<?, ?> map) {
        return !firstString(
                map,
                "badge_image",
                "badgeImage",
                "image_url",
                "icon_url",
                "complex_icon_static_url",
                "complex_icon_animated_url",
                "icon",
                "icon_hash",
                "iconHash",
                "simple_icon_url",
                "asset",
                "badge_icon"
        ).isEmpty();
    }

    private static boolean isBadgeContainer(String key) {
        return key.contains("badge")
                || key.contains("gift")
                || key.contains("experimental")
                || "profile".equals(key)
                || "user_profile".equals(key)
                || "data".equals(key)
                || "items".equals(key)
                || "results".equals(key);
    }

    private static void appendBadgeObject(
            Map<?, ?> badge, List<RemoteBadge> result, Set<String> seen) {
        String id = firstString(badge, "id", "badge_id", "badgeId");
        if (id.isEmpty()) return;

        String icon = firstString(
                badge,
                "badge_image",
                "badgeImage",
                "image_url",
                "icon_url",
                "complex_icon_static_url",
                "complex_icon_animated_url",
                "icon",
                "icon_hash",
                "iconHash",
                "simple_icon_url",
                "asset",
                "badge_icon"
        );
        boolean evolvingPremium = isEvolvingPremiumBadge(id, icon);
        if (isNativeBadge(id) && !evolvingPremium) return;
        if (icon.isEmpty()) icon = knownIcon(id);
        if (icon.isEmpty()) return;

        String description = firstString(badge, "description", "label", "title", "name");
        if (description.isEmpty()) description = knownDescription(id);
        if (description.isEmpty()) description = id;

        // Discord keeps the id as "premium" for the evolving icon. Use an internal
        // family id so it replaces the native premium badge instead of being dropped.
        String renderedId = evolvingPremium ? "premium_tenure_current" : id;
        appendRemoteBadge(result, seen, renderedId, description, icon);
    }

    private static boolean isCatalogBadge(Map<?, ?> badge) {
        return !firstString(badge, "badge_id", "badgeId", "id").isEmpty()
                && !firstString(badge, "current_tier", "currentTier").isEmpty()
                && firstValue(badge, "tiers") instanceof List<?>;
    }

    private static void appendCatalogBadge(
            Map<?, ?> badge, List<RemoteBadge> result, Set<String> seen) {
        String owned = firstString(badge, "owned", "is_owned", "isOwned");
        if (!owned.isEmpty() && !"true".equalsIgnoreCase(owned)) return;

        String currentTier = firstString(badge, "current_tier", "currentTier");
        String familyId = firstString(badge, "badge_id", "badgeId", "id");
        String family = normalizeBadgeFamily(familyId);
        List<?> tiers = asList(firstValue(badge, "tiers"));
        int tierIndex = -1;
        Map<?, ?> tier = null;

        for (int i = 0; i < tiers.size(); i++) {
            if (!(tiers.get(i) instanceof Map<?, ?>)) continue;
            Map<?, ?> candidate = (Map<?, ?>) tiers.get(i);
            String tierKey = firstString(candidate, "key", "id", "tier", "name");
            if (sameTier(currentTier, tierKey) || sameTier(currentTier, String.valueOf(i))) {
                tier = candidate;
                tierIndex = i;
                break;
            }
        }
        if (tier == null) {
            int expectedIndex = "premium_tenure".equals(family)
                    ? nitroIndex(currentTier.toLowerCase(Locale.ROOT))
                    : "gifting".equals(family)
                    ? giftIndex(currentTier.toLowerCase(Locale.ROOT))
                    : -1;
            if (expectedIndex >= 0
                    && expectedIndex < tiers.size()
                    && tiers.get(expectedIndex) instanceof Map<?, ?>) {
                tier = (Map<?, ?>) tiers.get(expectedIndex);
                tierIndex = expectedIndex;
            }
        }
        if (tier == null) return;

        String tierKey = firstString(tier, "key", "id", "tier", "name");
        String tierName = firstString(tier, "name", "description", "label", "title");
        String id = catalogBadgeId(familyId, tierKey, tierName, tierIndex, tier);
        if (id.isEmpty() || isNativeBadge(id)) return;

        String icon = firstString(
                tier,
                "complex_icon_static_url",
                "complex_icon_animated_url",
                "simple_icon_url",
                "icon_url",
                "icon",
                "asset"
        );
        if (icon.isEmpty()) {
            icon = firstString(
                    badge,
                    "complex_icon_static_url",
                    "simple_icon_url",
                    "icon_url",
                    "icon",
                    "asset"
            );
        }
        if (icon.isEmpty()) icon = knownIcon(id);
        if (icon.isEmpty()) return;

        String description = tierName;
        if (description.isEmpty()) description = firstString(badge, "name", "description", "label");
        if (description.isEmpty()) description = knownDescription(id);
        if (description.isEmpty()) description = id;
        appendRemoteBadge(result, seen, id, description, icon);
    }

    private static String catalogBadgeId(
            String familyId,
            String tierKey,
            String tierName,
            int tierIndex,
            Map<?, ?> tier) {
        String explicitId = firstString(tier, "badge_id", "badgeId");
        if (isFullBadgeId(explicitId)) return explicitId;

        String family = normalizeBadgeFamily(familyId);
        String value = (tierKey + " " + tierName + " " + explicitId).toLowerCase(Locale.ROOT);
        if ("premium_tenure".equals(family)) {
            int index = nitroIndex(value);
            if (index < 0) index = tierIndex;
            if (index >= 0 && index < NITRO_V1_IDS.length) {
                return value.contains("v2") ? NITRO_V2_IDS[index] : NITRO_V1_IDS[index];
            }
        }
        if ("gifting".equals(family)) {
            int index = giftIndex(value);
            if (index < 0) index = tierIndex;
            if (index >= 0 && index < GIFT_IDS.length) return GIFT_IDS[index];
        }
        if (!explicitId.isEmpty()) {
            return family.isEmpty() || explicitId.startsWith(family + "_")
                    ? explicitId : family + "_" + explicitId;
        }
        return family.isEmpty() ? tierKey : family + (tierKey.isEmpty() ? "" : "_" + tierKey);
    }

    private static boolean isFullBadgeId(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        return value.startsWith("premium_tenure_")
                || value.startsWith("gifting_")
                || value.startsWith("account_age_")
                || value.startsWith("streaming_")
                || value.startsWith("game_time_")
                || value.startsWith("game_variety_");
    }

    private static String normalizeBadgeFamily(String familyId) {
        String family = familyId.toLowerCase(Locale.ROOT);
        switch (family) {
            case "1":
            case "1.0":
            case "premium":
            case "premium_tenure":
            case "nitro":
                return "premium_tenure";
            case "17":
            case "17.0":
            case "gift":
            case "gifting":
                return "gifting";
            case "18":
            case "18.0":
                return "account_age";
            case "19":
            case "19.0":
                return "streaming";
            case "20":
            case "20.0":
                return "game_time";
            case "21":
            case "21.0":
                return "game_variety";
            default:
                return family;
        }
    }

    private static int nitroIndex(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < NITRO_LABELS.length; i++) {
            if (lower.contains(NITRO_LABELS[i].toLowerCase(Locale.ROOT))) return i;
        }
        if (lower.contains("fire")) return NITRO_LABELS.length - 1;
        for (int i = 0; i < NITRO_MONTHS.length; i++) {
            if (lower.contains("_" + NITRO_MONTHS[i] + "_month")
                    || lower.contains(NITRO_MONTHS[i] + " month")
                    || lower.equals(String.valueOf(NITRO_MONTHS[i]))) {
                return i;
            }
        }
        return -1;
    }

    private static int giftIndex(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < GIFT_LABELS.length; i++) {
            if (lower.contains(GIFT_LABELS[i].toLowerCase(Locale.ROOT))) return i;
        }
        for (int i = GIFT_MILESTONES.length - 1; i >= 0; i--) {
            if (lower.contains(String.valueOf(GIFT_MILESTONES[i]))) return i;
        }
        return -1;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private static boolean sameTier(String left, String right) {
        if (left.equalsIgnoreCase(right)) return true;
        try {
            return Double.parseDouble(left) == Double.parseDouble(right);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void appendRemoteBadge(
            List<RemoteBadge> result,
            Set<String> seen,
            String id,
            String description,
            String icon) {
        String normalizedId = id.trim();
        String normalizedIcon = icon.trim();
        if (normalizedId.isEmpty() || normalizedIcon.isEmpty()) return;

        String exactKey = normalizedId + "\u0000" + normalizedIcon;
        if (!seen.add(exactKey)) return;

        String family = badgeFamily(normalizedId);
        // Discord can return more than one owned gifting tier. Unlike Nitro,
        // gifting tiers are separate badges and must not be reduced to one
        // family entry.
        int existingIndex = "gifting".equals(family) ? -1 : findFamily(result, family);
        if (existingIndex >= 0) {
            if (isPreferred(new RemoteBadge(normalizedId, description, normalizedIcon),
                    result.get(existingIndex), family)) {
                result.set(existingIndex, new RemoteBadge(normalizedId, description, normalizedIcon));
            }
            return;
        }
        result.add(new RemoteBadge(normalizedId, description, normalizedIcon));
    }

    private static int findFamily(List<RemoteBadge> badges, String family) {
        if (family.isEmpty()) return -1;
        for (int i = 0; i < badges.size(); i++) {
            if (family.equals(badgeFamily(badges.get(i).id))) return i;
        }
        return -1;
    }

    private static boolean isPreferred(RemoteBadge candidate, RemoteBadge current, String family) {
        if ("premium_tenure".equals(family)) {
            if ("premium_tenure_current".equals(candidate.id)) return true;
            if ("premium_tenure_current".equals(current.id)) return false;

            int candidateMonths = nitroMonths(candidate);
            int currentMonths = nitroMonths(current);
            if (candidateMonths != currentMonths) return candidateMonths > currentMonths;
            return candidate.id.toLowerCase(Locale.ROOT).contains("_v2")
                    && !current.id.toLowerCase(Locale.ROOT).contains("_v2");
        }
        if ("gifting".equals(family)) {
            return giftLevel(candidate.id, candidate.description)
                    > giftLevel(current.id, current.description);
        }
        return false;
    }

    private static String badgeFamily(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if (value.startsWith("premium_tenure")) return "premium_tenure";
        if (value.startsWith("gifting")) return "gifting";
        if (value.startsWith("account_age")) return "account_age";
        if (value.startsWith("streaming")) return "streaming";
        if (value.startsWith("game_time")) return "game_time";
        if (value.startsWith("game_variety")) return "game_variety";
        return "";
    }

    private static void appendKnownBadge(
            String id, List<RemoteBadge> result, Set<String> seen) {
        String normalizedId = id.trim();
        if (normalizedId.isEmpty() || isNativeBadge(normalizedId)) return;
        String icon = knownIcon(normalizedId);
        if (icon.isEmpty()) return;
        String description = knownDescription(normalizedId);
        appendRemoteBadge(
                result,
                seen,
                normalizedId,
                description.isEmpty() ? normalizedId : description,
                icon
        );
    }

    private static boolean hasEvolvingNitro(List<RemoteBadge> badges) {
        for (RemoteBadge badge : badges) {
            if (isEvolvingNitroBadge(badge)) return true;
        }
        return false;
    }

    private static boolean hasEvolvingGiftingBadge(List<RemoteBadge> badges) {
        for (RemoteBadge badge : badges) {
            if (isEvolvingGiftingBadge(badge)) return true;
        }
        return false;
    }

    private static String badgeIds(List<RemoteBadge> badges) {
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < badges.size(); i++) {
            if (i > 0) ids.append(", ");
            ids.append(badges.get(i).id);
        }
        return ids.append(']').toString();
    }

    private static List<RemoteBadge> combineBadges(
            List<RemoteBadge> first, List<RemoteBadge> second) {
        if (second.isEmpty()) return first;

        List<RemoteBadge> combined = new ArrayList<>(first);
        Set<String> seen = new HashSet<>();
        for (RemoteBadge badge : first) {
            seen.add(badge.id + "\u0000" + badge.icon);
        }
        for (RemoteBadge badge : second) {
            appendRemoteBadge(combined, seen, badge.id, badge.description, badge.icon);
        }
        return Collections.unmodifiableList(combined);
    }

    private static boolean isEvolvingNitroBadge(RemoteBadge badge) {
        String value = (badge.id + " " + badge.description).toLowerCase(Locale.ROOT);
        if ("premium_tenure".equals(badgeFamily(badge.id))
                || value.contains("nitro")
                || value.contains("premium_tenure")) {
            return true;
        }

        // Keep replacement working if Discord sends a localized tier label without
        // the premium_tenure identifier (for example, "3 months: Silver").
        for (String label : NITRO_LABELS) {
            if (value.contains(label.toLowerCase(Locale.ROOT)) && value.contains("month")) {
                return true;
            }
        }
        return nitroMonths(badge) > 0;
    }

    private static boolean isEvolvingGiftingBadge(RemoteBadge badge) {
        String value = (badge.id + " " + badge.description).toLowerCase(Locale.ROOT);
        return GIFTING_ICON.equalsIgnoreCase(badge.icon)
                || "gifting".equalsIgnoreCase(badge.id)
                || "gifting".equals(badgeFamily(badge.id))
                || value.contains("gifting")
                || value.contains("gift badge");
    }

    private static String badgeImageUrl(RemoteBadge badge) {
        if (isEvolvingNitroBadge(badge)) {
            int index = nitroIndex(badge.id + " " + badge.description);
            if (index < 0) index = nitroIndexForIcon(badge.icon);
            if (index >= 0 && index < RN_NITRO_SMALL_ASSETS.length) {
                return RN_ASSET_CDN + RN_NITRO_SMALL_ASSETS[index];
            }
        }
        if (isEvolvingGiftingBadge(badge)) {
            // Match Aliucord's DiscordBadges core plugin and use the profile icon
            // hash through /badge-icons/{hash}.png. simple_icon_url is a web-only
            // fallback and is not consistently usable by the mobile image loader.
            if (!badge.icon.isEmpty() && !badge.icon.contains("/")) {
                return iconFallbackUrl(badge.icon);
            }
            if (badge.icon.startsWith("http://") || badge.icon.startsWith("https://")) {
                return badge.icon;
            }
            if (!badge.icon.isEmpty()) return iconFallbackUrl(badge.icon);

            int index = giftLevel(badge.id, badge.description) - 1;
            if (index >= 0 && index < RN_GIFTING_SMALL_ASSETS.length) {
                return RN_ASSET_CDN + RN_GIFTING_SMALL_ASSETS[index];
            }
        }
        // RN's generic profile-badge path is /badge-icons/{hash}.png. This is
        // the fallback for profiles whose tier label is not available.
        return iconFallbackUrl(badge.icon);
    }

    private static int nitroIndexForIcon(String icon) {
        for (int i = 0; i < NITRO_V1_ICONS.length; i++) {
            if (NITRO_V1_ICONS[i].equalsIgnoreCase(icon)
                    || NITRO_V2_ICONS[i].equalsIgnoreCase(icon)) {
                return i;
            }
        }
        return -1;
    }

    private static int nitroMonths(RemoteBadge badge) {
        int months = nitroMonths(badge.id);
        return months > 0 ? months : nitroMonths(badge.description);
    }

    private static int nitroMonths(String value) {
        if (value == null) return 0;
        String lower = value.toLowerCase(Locale.ROOT);
        for (int months : NITRO_MONTHS) {
            if (lower.contains("_" + months + "_month")
                    || lower.contains(months + " month")) {
                return months;
            }
        }
        return 0;
    }

    private static boolean isEvolvingPremiumBadge(String id, String icon) {
        return "premium".equalsIgnoreCase(id)
                && !icon.isEmpty()
                && !isLegacyPremiumIcon(icon);
    }

    private static boolean isLegacyPremiumIcon(String icon) {
        return icon.toLowerCase(Locale.ROOT)
                .contains("2ba85e8026a8614b640c2837bcdfe21b");
    }

    private static boolean isNativeBadge(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        switch (value) {
            case "staff":
            case "partner":
            case "certified_moderator":
            case "hypesquad":
            case "hypesquad_house_1":
            case "hypesquad_house_2":
            case "hypesquad_house_3":
            case "bug_hunter_level_1":
            case "bug_hunter_level_2":
            case "verified_developer":
            case "early_supporter":
            case "premium_early_supporter":
            case "premium":
            case "guild_booster":
                return true;
            default:
                return value.startsWith("guild_booster_");
        }
    }

    private static int giftLevel(String id, String description) {
        String value = (id + " " + description).toLowerCase(Locale.ROOT);
        for (int i = 0; i < GIFT_LABELS.length; i++) {
            if (value.contains(GIFT_LABELS[i].toLowerCase(Locale.ROOT))) return i + 1;
        }
        if (!value.contains("gift")) return 0;

        int explicitTier = giftTierNumber(value);
        if (explicitTier > 0) return explicitTier;

        for (int end = value.length() - 1; end >= 0; end--) {
            if (!Character.isDigit(value.charAt(end))) continue;
            int start = end;
            while (start > 0 && Character.isDigit(value.charAt(start - 1))) start--;
            try {
                return giftIndexForCount(Integer.parseInt(value.substring(start, end + 1)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 1;
    }

    private static int giftTierNumber(String value) {
        int tierIndex = value.indexOf("tier");
        if (tierIndex < 0) return 0;

        int start = tierIndex + "tier".length();
        while (start < value.length()) {
            char separator = value.charAt(start);
            if (separator != ' ' && separator != '_' && separator != '-') break;
            start++;
        }
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        if (start == end) return 0;

        try {
            int tier = Integer.parseInt(value.substring(start, end));
            return tier >= 1 && tier <= RN_GIFTING_SMALL_ASSETS.length ? tier : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int giftIndexForCount(int count) {
        for (int i = GIFT_MILESTONES.length - 1; i >= 0; i--) {
            if (count >= GIFT_MILESTONES[i]) return i + 1;
        }
        return 0;
    }

    private static String knownIcon(String id) {
        if ("gifting".equalsIgnoreCase(id)) return GIFTING_ICON;
        for (int i = 0; i < NITRO_V1_IDS.length; i++) {
            if (NITRO_V1_IDS[i].equals(id)) return NITRO_V1_ICONS[i];
            if (NITRO_V2_IDS[i].equals(id)) return NITRO_V2_ICONS[i];
        }
        int months = nitroMonths(id);
        if (months > 0) {
            for (int i = 0; i < NITRO_MONTHS.length; i++) {
                if (NITRO_MONTHS[i] == months) {
                    return id.toLowerCase(Locale.ROOT).contains("_v2")
                            ? NITRO_V2_ICONS[i] : NITRO_V1_ICONS[i];
                }
            }
        }

        int gift = giftLevel(id, "");
        if (gift > 0 && gift <= RN_GIFTING_SMALL_ASSETS.length) {
            return RN_ASSET_CDN + RN_GIFTING_SMALL_ASSETS[gift - 1];
        }

        return "";
    }

    private static String knownDescription(String id) {
        if ("gifting".equalsIgnoreCase(id)) return "Gifting Icon";
        int months = nitroMonths(id);
        if (months > 0) {
            for (int i = 0; i < NITRO_MONTHS.length; i++) {
                if (NITRO_MONTHS[i] == months) {
                    return months + " months: " + NITRO_LABELS[i];
                }
            }
        }
        for (int i = 0; i < GIFT_IDS.length; i++) {
            if (GIFT_IDS[i].equals(id)) return GIFT_LABELS[i];
        }
        int gift = giftLevel(id, "");
        if (gift > 0 && gift <= GIFT_LABELS.length) return GIFT_LABELS[gift - 1];
        return "";
    }

    private static String firstString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String value = stringValue(firstValue(map, key));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static Object firstValue(Map<?, ?> map, String key) {
        Object direct = map.get(key);
        if (direct != null) return direct;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(stringValue(entry.getKey()))) return entry.getValue();
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String profileKey(long userId, Long guildId) {
        return userId + ":" + (guildId == null ? "global" : guildId);
    }

    private static String profileRoute(long userId, Long guildId) {
        String route = "/users/" + userId
                + "/profile?type=popout"
                + "&with_mutual_guilds=true"
                + "&with_mutual_friends=true"
                + "&with_mutual_friends_count=false";
        return guildId == null ? route : route + "&guild_id=" + guildId;
    }

    private static String rnProfileRoute(long userId, Long guildId) {
        String route = "/users/" + userId
                + "/profile?with_mutual_guilds=true"
                + "&with_mutual_friends=true"
                + "&with_mutual_friends_count=false";
        return guildId == null ? route : route + "&guild_id=" + guildId;
    }

    private static String fullProfileRoute(long userId, Long guildId) {
        String route = "/users/" + userId
                + "/profile?type=full"
                + "&with_mutual_guilds=true"
                + "&with_mutual_friends=true"
                + "&with_mutual_friends_count=false";
        return guildId == null ? route : route + "&guild_id=" + guildId;
    }



    private static String iconFallbackUrl(String icon) {
        if (icon.startsWith("http://") || icon.startsWith("https://")) return icon;
        if (icon.startsWith("/")) return CDN + icon;
        if (icon.startsWith("badge-icons/")) return CDN + "/" + icon;
        String suffix = hasImageExtension(icon) ? "" : ".png";
        return CDN + "/badge-icons/" + icon + suffix;
    }

    private static String normalizeImageUrl(String value) {
        int queryIndex = value.indexOf('?');
        return queryIndex < 0 ? value : value.substring(0, queryIndex);
    }

    private static String normalizeBadgeIdentity(String value) {
        return normalizeImageUrl(value).toLowerCase(Locale.ROOT);
    }

    private static boolean hasImageExtension(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif");
    }

    @Override
    public void stop(Context context) {
        running = false;
        generation++;
        patcher.unpatchAll();
        requestsInFlight.clear();
        badgeCache.clear();
        boundProfiles.clear();
        profileAdapters.clear();
        updatingAdapters.clear();
    }
    private static final class CachedBadges {
        private final List<RemoteBadge> badges;
        private final long fetchedAt;

        private CachedBadges(List<RemoteBadge> badges) {
            this.badges = badges;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    private static final class RemoteBadge {
        private final String id;
        private final String description;
        private final String icon;

        private RemoteBadge(String id, String description, String icon) {
            this.id = id;
            this.description = description;
            this.icon = icon;
        }
    }

}
