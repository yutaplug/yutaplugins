package com.github.yutaplug.newdiscordbadges;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.GsonUtils;
import com.discord.api.user.UserProfile;
import com.discord.models.user.User;
import com.discord.utilities.images.MGImages;
import com.discord.widgets.user.Badge;
import com.discord.widgets.user.profile.UserProfileHeaderView;
import com.discord.widgets.user.profile.UserProfileHeaderViewModel;
import com.facebook.drawee.view.SimpleDraweeView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"unused", "unchecked", "rawtypes"})
@AliucordPlugin
public final class NewDiscordBadges extends Plugin {
    private static final String CDN = "https://cdn.discordapp.com";
    private static final long CACHE_DURATION_MS = 10L * 60L * 1000L;

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

    private static final String[] GIFT_IDS = {
            "gifting_patron", "gifting_champion", "gifting_luminary",
            "gifting_icon", "gifting_hero", "gifting_legend"
    };
    private static final String[] GIFT_LABELS = {
            "Patron", "Champion", "Luminary", "Icon", "Hero", "Legend"
    };
    private static final int[] GIFT_MILESTONES = {1, 2, 3, 6, 10, 20};
    private static final String[] GIFT_ICONS = {
            "ac305d1b9481f312ce4419e7f8296558",
            "8b7792c4f65953d3ff564f23429cb79e",
            "3119f5504b2cd09576a323908c7c3517",
            "64f2413c9b9803661322aaad25826b62",
            "77d65b1f210014a11eb1582ee06ab684",
            "7fe346cfc5da1340087d8759a9e7a395"
    };

    private final Map<String, CachedBadges> badgeCache = new ConcurrentHashMap<>();
    private final Set<String> requestsInFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> usersWithEvolvingNitro = ConcurrentHashMap.newKeySet();
    private final Map<UserProfileHeaderView, String> boundProfiles =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UserProfileHeaderView, UserProfileHeaderViewModel.ViewState.Loaded> boundStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<RecyclerView, RemoteBadgeAdapter> adapters =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<RecyclerView, RecyclerView.Adapter<?>> originalAdapters =
            Collections.synchronizedMap(new WeakHashMap<>());

    private volatile boolean running;
    private volatile long generation;

    @Override
    public void start(Context context) throws Throwable {
        running = true;
        generation++;

        patcher.patch(
                Badge.Companion.class,
                "getBadgesForUser",
                new Class<?>[]{User.class, UserProfile.class, boolean.class, boolean.class, Context.class},
                new Hook(frame -> {
                    if (!(frame.getResult() instanceof List<?>)) return;
                    if (frame.args.length == 0 || !(frame.args[0] instanceof User)) return;

                    User user = (User) frame.args[0];
                    if (!usersWithEvolvingNitro.contains(String.valueOf(user.getId()))) return;

                    List<Badge> badges = new ArrayList<>();
                    boolean changed = false;
                    for (Object value : (List<?>) frame.getResult()) {
                        if (value instanceof Badge
                                && "PREMIUM".equals(((Badge) value).getObjectType())) {
                            changed = true;
                        } else if (value instanceof Badge) {
                            badges.add((Badge) value);
                        }
                    }
                    if (changed) frame.setResult(badges);
                })
        );

        patcher.patch(
                UserProfileHeaderView.class,
                "updateViewState",
                new Class<?>[]{UserProfileHeaderViewModel.ViewState.Loaded.class},
                new Hook(frame -> {
                    if (!(frame.thisObject instanceof UserProfileHeaderView)) return;
                    if (frame.args.length == 0
                            || !(frame.args[0] instanceof UserProfileHeaderViewModel.ViewState.Loaded)) {
                        return;
                    }

                    UserProfileHeaderView header = (UserProfileHeaderView) frame.thisObject;
                    if (!isProfileHeader(header)) return;

                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) frame.args[0];
                    User user = state.getUser();
                    if (user == null || user.getId() <= 0L) return;

                    RecyclerView recycler = findBadgeRecycler(header);
                    if (recycler == null) return;

                    String userId = String.valueOf(user.getId());
                    Long guildId = null;
                    if (state.getGuildMember() != null
                            && state.getGuildMember().getGuildId() > 0L) {
                        guildId = state.getGuildMember().getGuildId();
                    }
                    String key = profileKey(user.getId(), guildId);
                    boundProfiles.put(header, key);
                    boundStates.put(header, state);

                    RemoteBadgeAdapter adapter = ensureAdapter(recycler);
                    if (adapter == null) return;

                    CachedBadges cached = badgeCache.get(key);
                    if (isFresh(cached)) {
                        boolean previouslyHadNitro = usersWithEvolvingNitro.contains(userId);
                        boolean hasNitro = hasEvolvingNitro(cached.badges);
                        if (hasNitro) {
                            usersWithEvolvingNitro.add(userId);
                        } else {
                            usersWithEvolvingNitro.remove(userId);
                        }
                        adapter.setBadges(cached.badges);
                        if (previouslyHadNitro != hasNitro) {
                            refreshNativeBadges(header, state);
                        }
                    } else {
                        adapter.setBadges(Collections.emptyList());
                        requestBadges(header, user.getId(), guildId, key);
                    }
                })
        );
    }

    private boolean isProfileHeader(UserProfileHeaderView header) {
        try {
            String resourceName = header.getResources().getResourceEntryName(header.getId());
            return "user_sheet_profile_header_view".equals(resourceName)
                    || "user_settings_profile_header_view".equals(resourceName);
        } catch (RuntimeException error) {
            logger.error("Could not identify the profile header", error);
            return false;
        }
    }

    private static RecyclerView findBadgeRecycler(UserProfileHeaderView header) {
        int id = Utils.getResId("user_profile_header_badges_recycler", "id");
        if (id == 0) return null;

        View view = header.findViewById(id);
        return view instanceof RecyclerView ? (RecyclerView) view : null;
    }

    private static boolean isFresh(CachedBadges cached) {
        return cached != null
                && System.currentTimeMillis() - cached.fetchedAt < CACHE_DURATION_MS;
    }

    private RemoteBadgeAdapter ensureAdapter(RecyclerView recycler) {
        RecyclerView.Adapter<?> current = recycler.getAdapter();
        RemoteBadgeAdapter existing = current == null ? null : findRemoteAdapter(current);
        if (existing != null) {
            removeDuplicateRemoteAdapters(current, existing);
            adapters.put(recycler, existing);
            return existing;
        }
        if (current == null) return null;

        RemoteBadgeAdapter adapter = adapters.get(recycler);
        if (adapter == null) adapter = new RemoteBadgeAdapter();
        originalAdapters.putIfAbsent(recycler, current);

        if (current instanceof ConcatAdapter) {
            ((ConcatAdapter) current).addAdapter(adapter);
        } else {
            recycler.setAdapter(new ConcatAdapter((RecyclerView.Adapter) current, adapter));
        }
        adapters.put(recycler, adapter);
        return adapter;
    }

    private static RemoteBadgeAdapter findRemoteAdapter(RecyclerView.Adapter<?> adapter) {
        if (adapter instanceof RemoteBadgeAdapter) return (RemoteBadgeAdapter) adapter;
        if (!(adapter instanceof ConcatAdapter)) return null;

        for (Object child : ((ConcatAdapter) adapter).getAdapters()) {
            if (child instanceof RecyclerView.Adapter<?>) {
                RemoteBadgeAdapter result = findRemoteAdapter((RecyclerView.Adapter<?>) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void removeDuplicateRemoteAdapters(
            RecyclerView.Adapter<?> adapter, RemoteBadgeAdapter keep) {
        if (!(adapter instanceof ConcatAdapter)) return;

        ConcatAdapter concat = (ConcatAdapter) adapter;
        for (Object child : new ArrayList<>(concat.getAdapters())) {
            if (child instanceof RemoteBadgeAdapter) {
                if (child != keep) concat.removeAdapter((RecyclerView.Adapter) child);
            } else if (child instanceof RecyclerView.Adapter<?>) {
                removeDuplicateRemoteAdapters((RecyclerView.Adapter<?>) child, keep);
            }
        }
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
                result = parseBadges(requestJson(profileRoute(userId, guildId)));
            } catch (Exception error) {
                logger.error("Failed to load Discord profile badges", error);
            }

            if (!running || generation != requestGeneration) {
                requestsInFlight.remove(key);
                return;
            }

            final List<RemoteBadge> fetched = result;
            String userKey = String.valueOf(userId);
            boolean previouslyHadNitro = usersWithEvolvingNitro.contains(userKey);
            boolean hasNitro = hasEvolvingNitro(fetched);
            if (hasNitro) {
                usersWithEvolvingNitro.add(userKey);
            } else {
                usersWithEvolvingNitro.remove(userKey);
            }

            badgeCache.put(key, new CachedBadges(fetched));
            requestsInFlight.remove(key);

            UserProfileHeaderView target = headerReference.get();
            if (target == null) return;
            Utils.mainThread.post(() -> applyFetchedBadges(
                    target, key, fetched, previouslyHadNitro != hasNitro));
        });
    }

    private void applyFetchedBadges(
            UserProfileHeaderView header,
            String key,
            List<RemoteBadge> badges,
            boolean nativeBadgesChanged) {
        if (!running || !key.equals(boundProfiles.get(header))) return;

        RecyclerView recycler = findBadgeRecycler(header);
        RemoteBadgeAdapter adapter = recycler == null ? null : adapters.get(recycler);
        if (adapter != null) adapter.setBadges(badges);

        if (!nativeBadgesChanged) return;

        refreshNativeBadges(header, boundStates.get(header));
    }

    private void refreshNativeBadges(
            UserProfileHeaderView header,
            UserProfileHeaderViewModel.ViewState.Loaded state) {
        if (state == null) return;
        try {
            // Re-run Discord's native badge binding so the old Nitro badge disappears
            // when an evolved tier is available, without replacing any other badges.
            header.updateViewState(state);
        } catch (RuntimeException error) {
            logger.error("Could not refresh the profile-sheet badges", error);
        }
    }

    private Object requestJson(String route) throws Exception {
        try (Http.Request request = Http.Request.newDiscordRNRequest(route)) {
            String fingerprint = com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE
                    .getFingerprint();
            if (fingerprint != null) request.setHeader("X-Fingerprint", fingerprint);

            Http.Response response = request.execute();
            if (!response.ok()) {
                throw new IllegalStateException("HTTP " + response.statusCode);
            }
            return GsonUtils.fromJson(response.text(), Object.class);
        }
    }

    private static List<RemoteBadge> parseBadges(Object... bodies) {
        List<RemoteBadge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object body : bodies) {
            appendBadges(body, result, seen);
        }
        if (result.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(result);
    }

    private static void appendBadges(Object raw, List<RemoteBadge> result, Set<String> seen) {
        if (raw instanceof List<?>) {
            for (Object value : (List<?>) raw) {
                appendBadges(value, result, seen);
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

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey()).toLowerCase(Locale.ROOT);
            if (isBadgeContainer(key)) {
                appendBadges(entry.getValue(), result, seen);
            }
        }
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
                "icon",
                "icon_hash",
                "iconHash",
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
        int existingIndex = findFamily(result, family);
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

    private static int giftIndexForCount(int count) {
        for (int i = GIFT_MILESTONES.length - 1; i >= 0; i--) {
            if (count >= GIFT_MILESTONES[i]) return i + 1;
        }
        return 0;
    }

    private static String knownIcon(String id) {
        for (int i = 0; i < NITRO_V1_IDS.length; i++) {
            if (NITRO_V1_IDS[i].equals(id)) return NITRO_V1_ICONS[i];
            if (NITRO_V2_IDS[i].equals(id)) return NITRO_V2_ICONS[i];
        }
        for (int i = 0; i < GIFT_IDS.length; i++) {
            if (GIFT_IDS[i].equals(id)) return GIFT_ICONS[i];
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
        if (gift > 0 && gift <= GIFT_ICONS.length) return GIFT_ICONS[gift - 1];
        return "";
    }

    private static String knownDescription(String id) {
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



    private static String iconUrl(String icon) {
        if (icon.startsWith("http://") || icon.startsWith("https://")) return icon;
        if (icon.startsWith("/")) return CDN + icon + "?size=32";
        if (icon.startsWith("badge-icons/")) return CDN + "/" + icon + "?size=32";
        String suffix = hasImageExtension(icon) ? "" : ".png";
        return CDN + "/badge-icons/" + icon + suffix + "?size=32";
    }

    private static String iconFallbackUrl(String icon) {
        if (icon.startsWith("http://") || icon.startsWith("https://")) return icon;
        if (icon.startsWith("/")) return CDN + icon;
        if (icon.startsWith("badge-icons/")) return CDN + "/" + icon;
        String suffix = hasImageExtension(icon) ? "" : ".png";
        return CDN + "/badge-icons/" + icon + suffix;
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

        for (RecyclerView recycler : new ArrayList<>(adapters.keySet())) {
            try {
                RecyclerView.Adapter<?> current = recycler.getAdapter();
                RecyclerView.Adapter<?> original = originalAdapters.get(recycler);
                boolean hadRemoteAdapter = current != null && containsRemoteAdapter(current);
                if (current != null) removeAllRemoteAdapters(current);
                if (hadRemoteAdapter
                        && original != null && current != original) {
                    recycler.setAdapter(original);
                }
            } catch (RuntimeException error) {
                logger.error("Could not remove profile badge adapter", error);
            }
        }

        requestsInFlight.clear();
        badgeCache.clear();
        usersWithEvolvingNitro.clear();
        boundProfiles.clear();
        boundStates.clear();
        adapters.clear();
        originalAdapters.clear();
    }

    private static boolean containsRemoteAdapter(RecyclerView.Adapter<?> adapter) {
        if (adapter instanceof RemoteBadgeAdapter) return true;
        if (!(adapter instanceof ConcatAdapter)) return false;
        for (Object child : ((ConcatAdapter) adapter).getAdapters()) {
            if (child instanceof RecyclerView.Adapter<?>
                    && containsRemoteAdapter((RecyclerView.Adapter<?>) child)) {
                return true;
            }
        }
        return false;
    }

    private static void removeAllRemoteAdapters(RecyclerView.Adapter<?> adapter) {
        if (!(adapter instanceof ConcatAdapter)) return;

        ConcatAdapter concat = (ConcatAdapter) adapter;
        for (Object child : new ArrayList<>(concat.getAdapters())) {
            if (child instanceof RemoteBadgeAdapter) {
                concat.removeAdapter((RecyclerView.Adapter) child);
            } else if (child instanceof RecyclerView.Adapter<?>) {
                removeAllRemoteAdapters((RecyclerView.Adapter<?>) child);
            }
        }
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

    private static final class RemoteBadgeAdapter
            extends RecyclerView.Adapter<RemoteBadgeAdapter.BadgeViewHolder> {
        private List<RemoteBadge> badges = Collections.emptyList();

        private void setBadges(List<RemoteBadge> badges) {
            this.badges = badges == null ? Collections.emptyList() : badges;
            notifyDataSetChanged();
        }

        @Override
        public BadgeViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            SimpleDraweeView image = new SimpleDraweeView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);

            float density = context.getResources().getDisplayMetrics().density;
            int size = Math.round(20f * density);
            int margin = Math.round(6f * density);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(size, size);
            params.setMargins(margin, margin, 0, 0);
            image.setLayoutParams(params);
            return new BadgeViewHolder(image);
        }

        @Override
        public void onBindViewHolder(BadgeViewHolder holder, int position) {
            holder.bind(badges.get(position));
        }

        @Override
        public int getItemCount() {
            return badges.size();
        }

        private static final class BadgeViewHolder extends RecyclerView.ViewHolder {
            private final ImageView image;

            private BadgeViewHolder(ImageView image) {
                super(image);
                this.image = image;
            }

            private void bind(RemoteBadge badge) {
                image.setContentDescription(badge.description);
                image.setOnClickListener(view -> Utils.showToast(badge.description));
                String primary = iconUrl(badge.icon);
                String fallback = iconFallbackUrl(badge.icon);
                List<String> sources = primary.equals(fallback)
                        ? Collections.singletonList(primary)
                        : Arrays.asList(primary, fallback);
                MGImages.setImage(image, sources, 0, 0, false);
            }
        }
    }
}
