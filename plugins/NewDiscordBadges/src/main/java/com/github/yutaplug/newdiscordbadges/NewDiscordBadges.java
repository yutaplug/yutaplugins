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
import com.discord.widgets.user.Badge;
import com.facebook.drawee.view.SimpleDraweeView;
import com.discord.utilities.images.MGImages;
import com.discord.widgets.user.profile.UserProfileHeaderView;
import com.discord.widgets.user.profile.UserProfileHeaderViewModel;

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

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
                Badge.Companion.class,
                "getBadgesForUser",
                new Class<?>[]{User.class, UserProfile.class, boolean.class, boolean.class, Context.class},
                new Hook(frame -> {
                    Object result = frame.getResult();
                    if (!(result instanceof List<?>)) return;

                    User user = frame.args.length > 0 && frame.args[0] instanceof User
                            ? (User) frame.args[0] : null;
                    String userId = user == null ? "" : String.valueOf(user.getId());

                    List<Badge> filtered = new ArrayList<>();
                    for (Object value : (List<?>) result) {
                        if (value instanceof Badge
                                && "PREMIUM".equals(((Badge) value).getObjectType())
                                && usersWithEvolvingNitro.contains(userId)) {
                            continue;
                        }
                        if (value instanceof Badge) filtered.add((Badge) value);
                    }
                    frame.setResult(filtered);
                })
        );

        patcher.patch(
                UserProfileHeaderView.class,
                "updateViewState",
                new Class<?>[]{UserProfileHeaderViewModel.ViewState.Loaded.class},
                new Hook(frame -> {
                    UserProfileHeaderView header = (UserProfileHeaderView) frame.thisObject;
                    if (!isUserSheetHeader(header)) return;

                    UserProfileHeaderViewModel.ViewState.Loaded state =
                            (UserProfileHeaderViewModel.ViewState.Loaded) frame.args[0];
                    if (state.getUser() == null || state.getUser().getId() <= 0L) return;

                    RecyclerView recycler = findBadgeRecycler(header);
                    if (recycler == null) return;

                    boundStates.put(header, state);

                    RemoteBadgeAdapter adapter = ensureAdapter(recycler);
                    if (adapter == null) return;

                    Long guildId = null;
                    if (state.getGuildMember() != null
                            && state.getGuildMember().getGuildId() > 0L) {
                        guildId = state.getGuildMember().getGuildId();
                    }

                    String key = profileKey(state.getUser().getId(), guildId);
                    boundProfiles.put(header, key);

                    CachedBadges cached = badgeCache.get(key);
                    if (cached != null
                            && System.currentTimeMillis() - cached.fetchedAt < CACHE_DURATION_MS) {
                        adapter.setBadges(cached.badges);
                    } else {
                        adapter.setBadges(Collections.emptyList());
                        requestBadges(header, state.getUser().getId(), guildId, key);
                    }
                })
        );
    }

    private RecyclerView findBadgeRecycler(UserProfileHeaderView header) {
        int id = Utils.getResId("user_profile_header_badges_recycler", "id");
        if (id == 0) return null;
        return header.findViewById(id);
    }

    private static boolean isUserSheetHeader(UserProfileHeaderView header) {
        try {
            return "user_sheet_profile_header_view".equals(
                    header.getResources().getResourceEntryName(header.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private RemoteBadgeAdapter ensureAdapter(RecyclerView recycler) {
        RecyclerView.Adapter<?> nativeAdapter = recycler.getAdapter();
        RemoteBadgeAdapter adapter = nativeAdapter == null
                ? null : findRemoteAdapter(nativeAdapter);
        if (adapter != null) {
            removeDuplicateRemoteAdapters(nativeAdapter, adapter);
            adapters.put(recycler, adapter);
            return adapter;
        }

        adapter = adapters.get(recycler);
        if (adapter != null && nativeAdapter != null) {
            if (nativeAdapter instanceof ConcatAdapter) {
                ((ConcatAdapter) nativeAdapter).addAdapter(adapter);
            } else {
                recycler.setAdapter(new ConcatAdapter(
                        (RecyclerView.Adapter) nativeAdapter,
                        adapter
                ));
            }
            return adapter;
        }
        if (nativeAdapter == null) return null;

        adapter = new RemoteBadgeAdapter();
        if (nativeAdapter instanceof ConcatAdapter) {
            ((ConcatAdapter) nativeAdapter).addAdapter(adapter);
        } else {
            recycler.setAdapter(new ConcatAdapter(
                    (RecyclerView.Adapter) nativeAdapter,
                    adapter
            ));
        }
        adapters.put(recycler, adapter);
        return adapter;
    }

    private static RemoteBadgeAdapter findRemoteAdapter(RecyclerView.Adapter<?> adapter) {
        if (adapter instanceof RemoteBadgeAdapter) {
            return (RemoteBadgeAdapter) adapter;
        }
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

    private void requestBadges(UserProfileHeaderView header, long userId, Long guildId, String key) {
        if (!requestsInFlight.add(key)) return;

        Utils.threadPool.execute(() -> {
            Map<?, ?> profileBody = Collections.emptyMap();
            Map<?, ?> catalogBody = Collections.emptyMap();
            try {
                profileBody = requestJson(profileRoute(userId, guildId));
            } catch (Throwable error) {
                logger.error("Failed to load profile data for badges", error);
            }
            try {
                catalogBody = requestJson(badgeCatalogRoute(userId));
            } catch (Throwable error) {
                logger.error("Failed to load badge catalog", error);
            }

            List<RemoteBadge> result = parseBadges(profileBody, catalogBody);
            if (hasEvolvingNitro(result)) {
                usersWithEvolvingNitro.add(String.valueOf(userId));
            } else {
                usersWithEvolvingNitro.remove(String.valueOf(userId));
            }
            badgeCache.put(key, new CachedBadges(result));
            requestsInFlight.remove(key);

            Utils.mainThread.post(() -> {
                String boundKey = boundProfiles.get(header);
                if (!key.equals(boundKey)) return;

                UserProfileHeaderViewModel.ViewState.Loaded state = boundStates.get(header);
                if (state != null) {
                    header.updateViewState(state);
                    return;
                }

                RecyclerView recycler = findBadgeRecycler(header);
                if (recycler == null) return;
                RemoteBadgeAdapter adapter = adapters.get(recycler);
                if (adapter != null) adapter.setBadges(result);
            });
        });
    }

    private Map<?, ?> requestJson(String route) throws Exception {
        try (Http.Request request = Http.Request.newDiscordRNRequest(route)) {
            String fingerprint = com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE
                    .getFingerprint();
            if (fingerprint != null) request.setHeader("X-Fingerprint", fingerprint);

            Http.Response response = request.execute();
            if (!response.ok()) {
                throw new IllegalStateException("HTTP " + response.statusCode);
            }

            Map<?, ?> body = GsonUtils.fromJson(response.text(), Map.class);
            return body == null ? Collections.emptyMap() : body;
        }
    }

    private static List<RemoteBadge> parseBadges(
            Map<?, ?> profileBody, Map<?, ?> catalogBody) {
        List<RemoteBadge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // The catalog contains the authoritative current tier. Parse it first so
        // an older profile badge cannot win the family-level deduplication.
        appendCatalogBadges(catalogBody, result, seen);
        appendBadges(profileBody, result, seen);
        return result.isEmpty() ? Collections.emptyList() : result;
    }

    private static void appendBadges(Object raw, List<RemoteBadge> result, Set<String> seen) {
        if (raw instanceof List<?>) {
            for (Object value : (List<?>) raw) {
                appendBadges(value, result, seen);
            }
            return;
        }
        if (raw instanceof String) {
            appendKnownBadge(stringValue(raw), result, seen);
            return;
        }
        if (!(raw instanceof Map<?, ?>)) return;

        Map<?, ?> map = (Map<?, ?>) raw;
        String id = firstString(map, "id", "badge_id", "badgeId");
        if (!id.isEmpty()) {
            appendBadgeObject(map, result, seen);
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey()).toLowerCase(Locale.ROOT);
            if (key.contains("badge")
                    || key.contains("gift")
                    || key.contains("experimental")
                    || "profile".equals(key)
                    || "user_profile".equals(key)) {
                appendBadges(entry.getValue(), result, seen);
            }
        }
    }

    private static void appendBadgeObject(
            Map<?, ?> badge, List<RemoteBadge> result, Set<String> seen) {
        String id = firstString(badge, "id", "badge_id", "badgeId");
        if (id.isEmpty() || isAlreadyRenderedNatively(id)) return;

        String icon = firstString(
                badge,
                "icon",
                "icon_hash",
                "iconHash",
                "asset",
                "badge_icon",
                "badge_image"
        );
        if (icon.isEmpty()) icon = knownIcon(id);
        if (icon.isEmpty()) return;

        String description = firstString(badge, "description", "label", "title", "name");
        if (description.isEmpty()) description = knownDescription(id);
        if (description.isEmpty()) description = id;
        appendRemoteBadge(result, seen, id, description, icon);
    }

    private static void appendCatalogBadges(
            Object raw, List<RemoteBadge> result, Set<String> seen) {
        if (raw instanceof List<?>) {
            for (Object value : (List<?>) raw) {
                appendCatalogBadges(value, result, seen);
            }
            return;
        }
        if (!(raw instanceof Map<?, ?>)) return;

        Map<?, ?> map = (Map<?, ?>) raw;
        if (!firstString(map, "badge_id", "badgeId", "id").isEmpty()
                && !firstString(map, "current_tier", "currentTier").isEmpty()) {
            appendCatalogBadge(map, result, seen);
        }

        for (String key : new String[]{"badges", "data", "items", "results"}) {
            Object value = map.get(key);
            if (value != null) appendCatalogBadges(value, result, seen);
        }
    }

    private static void appendCatalogBadge(
            Map<?, ?> badge, List<RemoteBadge> result, Set<String> seen) {
        String owned = firstString(badge, "owned", "is_owned", "isOwned");
        if (!owned.isEmpty() && !"true".equalsIgnoreCase(owned)) return;

        String currentTier = firstString(badge, "current_tier", "currentTier");
        if (currentTier.isEmpty()) return;

        String familyId = firstString(badge, "badge_id", "badgeId", "id");
        String family = normalizeBadgeFamily(familyId);
        List<?> tiers = asList(badge.get("tiers"));
        int tierIndex = -1;
        Map<?, ?> tier = null;
        for (int i = 0; i < tiers.size(); i++) {
            Object value = tiers.get(i);
            if (!(value instanceof Map<?, ?>)) continue;
            Map<?, ?> candidate = (Map<?, ?>) value;
            String key = firstString(candidate, "key", "id", "tier", "name");
            if (sameTier(currentTier, key) || sameTier(currentTier, String.valueOf(i))) {
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
            if (expectedIndex >= 0 && expectedIndex < tiers.size()
                    && tiers.get(expectedIndex) instanceof Map<?, ?>) {
                tier = (Map<?, ?>) tiers.get(expectedIndex);
                tierIndex = expectedIndex;
            }
        }
        if (tier == null) return;

        String tierKey = firstString(tier, "key", "id", "tier", "name");
        String tierName = firstString(tier, "name", "description", "label", "title");
        String id = catalogBadgeId(familyId, tierKey, tierName, tierIndex, tier);
        if (id.isEmpty() || isAlreadyRenderedNatively(id)) return;

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
        if (description.isEmpty()) {
            description = firstString(badge, "name", "description", "label", "title");
        }
        if (description.isEmpty()) description = knownDescription(id);
        if (description.isEmpty()) description = id;

        appendRemoteBadge(result, seen, id, description, icon);
    }

    private static String catalogBadgeId(
            String familyId, String tierKey, String tierName, int tierIndex, Map<?, ?> tier) {
        String explicitId = firstString(tier, "badge_id", "badgeId");
        if (isFullBadgeId(explicitId)) return explicitId;

        String family = normalizeBadgeFamily(familyId);
        String value = (tierKey + " " + tierName + " " + explicitId)
                .toLowerCase(Locale.ROOT);

        if ("premium_tenure".equals(family)) {
            int index = nitroIndex(value);
            if (index < 0) index = tierIndex;
            if (index >= 0 && index < NITRO_V1_IDS.length) {
                String[] ids = value.contains("v2") ? NITRO_V2_IDS : NITRO_V1_IDS;
                return ids[index];
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
        if (family.isEmpty()) return tierKey;
        return family + (tierKey.isEmpty() ? "" : "_" + tierKey);
    }

    private static boolean isFullBadgeId(String id) {
        return id.startsWith("premium_tenure_")
                || id.startsWith("gifting_")
                || id.startsWith("account_age_")
                || id.startsWith("streaming_")
                || id.startsWith("game_time_")
                || id.startsWith("game_variety_");
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
        for (int i = 0; i < NITRO_LABELS.length; i++) {
            if (value.contains(NITRO_LABELS[i].toLowerCase(Locale.ROOT))) return i;
        }
        for (int i = 0; i < NITRO_MONTHS.length; i++) {
            if (value.contains("_" + NITRO_MONTHS[i] + "_month")
                    || value.contains(NITRO_MONTHS[i] + " month")
                    || value.equals(String.valueOf(NITRO_MONTHS[i]))) return i;
        }
        return -1;
    }

    private static int giftIndex(String value) {
        for (int i = 0; i < GIFT_LABELS.length; i++) {
            if (value.contains(GIFT_LABELS[i].toLowerCase(Locale.ROOT))) return i;
        }
        for (int i = GIFT_MILESTONES.length - 1; i >= 0; i--) {
            if (value.contains(String.valueOf(GIFT_MILESTONES[i]))) return i;
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
            List<RemoteBadge> result, Set<String> seen,
            String id, String description, String icon) {
        String key = id + "\u0000" + icon;
        if (!seen.add(key) || containsBadgeId(result, id)) return;
        result.add(new RemoteBadge(id, description, icon));
    }

    private static void appendKnownBadge(
            String id, List<RemoteBadge> result, Set<String> seen) {
        if (id.isEmpty() || isAlreadyRenderedNatively(id)) return;

        String icon = knownIcon(id);
        if (icon.isEmpty()) return;

        String description = knownDescription(id);
        appendRemoteBadge(
                result,
                seen,
                id,
                description.isEmpty() ? id : description,
                icon
        );
    }

    private static boolean hasEvolvingNitro(List<RemoteBadge> badges) {
        for (RemoteBadge badge : badges) {
            if (nitroMonths(badge.id) > 0) return true;
        }
        return false;
    }

    private static boolean containsBadgeId(List<RemoteBadge> badges, String id) {
        for (RemoteBadge badge : badges) {
            if (id.equals(badge.id)) return true;
            if (nitroMonths(id) > 0 && nitroMonths(badge.id) > 0) return true;
            if (isGiftingBadge(id) && isGiftingBadge(badge.id)) return true;
        }
        return false;
    }

    private static boolean isGiftingBadge(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).contains("gift");
    }

    private static int nitroMonths(String id) {
        if (id == null || !id.startsWith("premium_tenure_")) return 0;
        for (int months : NITRO_MONTHS) {
            if (id.contains("_" + months + "_month")) return months;
        }
        return 0;
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
                    return id.endsWith("_v2") ? NITRO_V2_ICONS[i] : NITRO_V1_ICONS[i];
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
                    return months + " months: "
                            + (id.endsWith("_v2") ? NITRO_LABELS[i] : (i == 7 ? "Fire" : NITRO_LABELS[i]));
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

    private static boolean isAlreadyRenderedNatively(String id) {
        switch (id) {
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
            case "premium":
                return true;
            default:
                return id.startsWith("guild_booster_");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String profileRoute(long userId, Long guildId) {
        String route = "/users/" + userId
                + "/profile?with_mutual_guilds=false&with_mutual_friends=false";
        return guildId == null ? route : route + "&guild_id=" + guildId;
    }

    private static String badgeCatalogRoute(long userId) {
        return "/users/" + userId + "/badges";
    }

    private static String profileKey(long userId, Long guildId) {
        return userId + ":" + (guildId == null ? "global" : guildId);
    }

    private static String iconUrl(String icon) {
        if (icon.startsWith("http://") || icon.startsWith("https://")) return icon;
        if (icon.startsWith("/")) return "https://cdn.discordapp.com" + icon + "?size=32";
        if (icon.startsWith("badge-icons/")) return CDN + "/" + icon + "?size=32";
        String suffix = icon.endsWith(".png") ? "" : ".png";
        return CDN + "/badge-icons/" + icon + suffix + "?size=32";
    }

    private static String iconFallbackUrl(String icon) {
        if (icon.startsWith("http://") || icon.startsWith("https://")) return icon;
        if (icon.startsWith("/")) return "https://cdn.discordapp.com" + icon;
        if (icon.startsWith("badge-icons/")) return CDN + "/" + icon;
        String suffix = icon.endsWith(".png") ? "" : ".png";
        return CDN + "/badge-icons/" + icon + suffix;
    }

    @Override
    public void stop(Context context) {
        for (RecyclerView recycler : new ArrayList<>(adapters.keySet())) {
            RecyclerView.Adapter<?> adapter = recycler.getAdapter();
            if (adapter != null) removeAllRemoteAdapters(adapter);
        }
        patcher.unpatchAll();
        requestsInFlight.clear();
        badgeCache.clear();
        usersWithEvolvingNitro.clear();
        boundProfiles.clear();
        boundStates.clear();
        adapters.clear();
    }

    private static void removeAllRemoteAdapters(RecyclerView.Adapter<?> adapter) {
        if (adapter instanceof RemoteBadgeAdapter) return;
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
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

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
                MGImages.setImage(
                        image,
                        Arrays.asList(iconUrl(badge.icon), iconFallbackUrl(badge.icon)),
                        0,
                        0,
                        false
                );
            }
        }
    }
}
