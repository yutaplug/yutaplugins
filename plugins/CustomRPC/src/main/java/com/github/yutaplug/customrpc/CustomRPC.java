package com.github.yutaplug.customrpc;

import android.content.Context;

import androidx.annotation.NonNull;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.discord.app.AppActivity;
import com.discord.api.activity.Activity;
import com.discord.api.activity.ActivityAssets;
import com.discord.api.activity.ActivityType;
import com.discord.api.presence.ClientStatus;
import com.discord.gateway.GatewaySocket;
import com.discord.models.domain.ModelPayload;
import com.discord.models.domain.ModelUserSettings;
import com.discord.models.presence.Presence;
import com.discord.stores.Dispatcher;
import com.discord.stores.StoreGatewayConnection;
import com.discord.stores.StoreConnectionOpen;
import com.discord.stores.StoreStream;
import com.discord.stores.StoreUserPresence;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.lang.ref.WeakReference;

import kotlin.Unit;

@SuppressWarnings({"unused", "unchecked"})
@AliucordPlugin
public final class CustomRPC extends Plugin {
    public static final String ENABLED = "enabled";
    public static final String ACTIVITY_TYPE = "activityType";
    public static final String ACTIVITY_FLAGS = "activityFlags";
    public static final String APPLICATION_ID = "applicationId";
    public static final String NAME = "name";
    public static final String DETAILS = "details";
    public static final String STATE = "state";
    public static final String LARGE_IMAGE = "largeImage";
    public static final String LARGE_IMAGE_TEXT = "largeImageText";
    public static final String SMALL_IMAGE = "smallImage";
    public static final String SMALL_IMAGE_TEXT = "smallImageText";

    private static final String DEFAULT_NAME = "Custom RPC";
    private static final ActivityType DEFAULT_ACTIVITY_TYPE = ActivityType.PLAYING;
    // Match Vencord's known-working custom RPC payload for all local and gateway paths.
    private static final int DEFAULT_ACTIVITY_FLAGS = ActivityFlags.INSTANCE | ActivityFlags.EMBEDDED;

    private boolean updatingPresence;
    private boolean activitySharingEnabled;
    private WeakReference<AppActivity> lastActivity;

    public CustomRPC() {
        settingsTab = new SettingsTab(CustomRPCSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings, this);
    }

    @Override
    public void start(@NonNull Context context) {
        if (isEnabled()) enableActivitySharing(context);

        patcher.patch(
                AppActivity.class,
                "onResume",
                new Class<?>[]{},
                new Hook(param -> {
                    if (isEnabled()) {
                        enableActivitySharing((AppActivity) param.thisObject);
                        applyActivity();
                    }
                })
        );

        // Keep the custom activity in the exact payload that Discord sends through the gateway.
        patcher.patch(
                StoreGatewayConnection.class,
                "presenceUpdate",
                new Class<?>[]{ClientStatus.class, Long.class, List.class, Boolean.class},
                new PreHook(param -> {
                    if (!isEnabled()) return;
                    param.args[2] = addGatewayActivity(param.args[2]);
                })
        );
        patcher.patch(
                GatewaySocket.class,
                "presenceUpdate",
                new Class<?>[]{ClientStatus.class, Long.class, List.class, Boolean.class},
                new PreHook(param -> {
                    if (!isEnabled()) return;
                    param.args[2] = addGatewayActivity(param.args[2]);
                })
        );

        patcher.patch(
                StoreUserPresence.class,
                "updateActivity",
                new Class<?>[]{ActivityType.class, Activity.class, boolean.class},
                new Hook(param -> reapplyIfEnabled())
        );

        // StoreUserPresence rebuilds local activities after these events. Reapply afterwards.
        patcher.patch(
                StoreUserPresence.class,
                "handleConnectionOpen",
                new Class<?>[]{ModelPayload.class},
                new Hook(param -> reapplyIfEnabled())
        );
        patcher.patch(
                StoreUserPresence.class,
                "handleUserSettingsUpdate",
                new Class<?>[]{ModelUserSettings.class},
                new Hook(param -> reapplyIfEnabled())
        );
        patcher.patch(
                StoreUserPresence.class,
                "handleSessionsReplace",
                new Class<?>[]{List.class},
                new Hook(param -> reapplyIfEnabled())
        );
        patcher.patch(
                StoreConnectionOpen.class,
                "handleConnectionOpen",
                new Class<?>[]{},
                new Hook(param -> reapplyIfEnabled())
        );

        if (isEnabled()) applyActivity();
    }

    @Override
    public void stop(@NonNull Context context) {
        patcher.unpatchAll();
        activitySharingEnabled = false;
        clearActivity();
    }

    void setEnabled(boolean enabled) {
        settings.setBool(ENABLED, enabled);
        if (enabled) {
            enableActivitySharing(null);
            applyActivity();
        } else {
            activitySharingEnabled = false;
            clearActivity();
        }
    }

    void setActivityType(ActivityType activityType) {
        if (!isSupportedActivityType(activityType)) return;
        ActivityType previous = getActivityType();
        settings.setString(ACTIVITY_TYPE, activityType.name());
        if (isEnabled()) {
            if (previous != activityType) updatePresence(previous, null);
            applyActivity();
        }
    }

    ActivityType getActivityType() {
        String saved = settings.getString(ACTIVITY_TYPE, DEFAULT_ACTIVITY_TYPE.name());
        if (saved != null) {
            try {
                ActivityType activityType = ActivityType.valueOf(saved);
                if (isSupportedActivityType(activityType)) return activityType;
            } catch (IllegalArgumentException ignored) {
                // Use Playing for old or invalid settings.
            }
        }
        return DEFAULT_ACTIVITY_TYPE;
    }

    String getActivityTypeLabel() {
        return activityTypeLabel(getActivityType());
    }

    int getActivityFlags() {
        return settings.getInt(ACTIVITY_FLAGS, DEFAULT_ACTIVITY_FLAGS);
    }

    void setActivityFlags(int flags) {
        settings.setInt(ACTIVITY_FLAGS, flags);
        if (isEnabled()) applyActivity();
    }

    String getActivityFlagsLabel() {
        return ActivityFlags.label(getActivityFlags());
    }

    boolean saveAndApply(String applicationId, String name, String details, String state,
            String largeImage, String largeImageText, String smallImage, String smallImageText) {
        settings.setString(APPLICATION_ID, clean(applicationId));
        settings.setString(NAME, clean(name));
        settings.setString(DETAILS, clean(details));
        settings.setString(STATE, clean(state));
        settings.setString(LARGE_IMAGE, clean(largeImage));
        settings.setString(LARGE_IMAGE_TEXT, clean(largeImageText));
        settings.setString(SMALL_IMAGE, clean(smallImage));
        settings.setString(SMALL_IMAGE_TEXT, clean(smallImageText));
        enableActivitySharing(null);
        setEnabled(true);
        return true;
    }

    void saveField(String key, String value) {
        settings.setString(key, clean(value));
        if (isEnabled()) applyActivity();
    }

    private boolean isEnabled() {
        return settings.getBool(ENABLED, false);
    }

    boolean hasValidApplicationId() {
        return parseApplicationId() != null;
    }

    private void reapplyIfEnabled() {
        if (isEnabled() && !updatingPresence) {
            applyActivity();
        }
    }

    void enableActivitySharing(Context context) {
        AppActivity activity = context instanceof AppActivity ? (AppActivity) context : null;
        if (activity != null) {
            lastActivity = new WeakReference<>(activity);
        } else if (lastActivity != null) {
            activity = lastActivity.get();
        }
        if (activitySharingEnabled && activity == null) return;
        StoreStream.getUserSettings().setIsShowCurrentGameEnabled(activity, true);
        if (activity != null) activitySharingEnabled = true;
    }

    private void applyActivity() {
        if (!isEnabled()) return;
        updatePresence(getActivityType(), createActivity());
    }

    private void clearActivity() {
        updatePresence(getActivityType(), null);
    }

    private void updatePresence(ActivityType activityType, Activity activity) {
        try {
            Dispatcher dispatcher = StoreStream.getDispatcherYesThisIsIntentional();
            dispatcher.schedule(() -> {
                synchronized (CustomRPC.this) {
                    if (updatingPresence) return Unit.a;
                    updatingPresence = true;
                }
                try {
                    StoreUserPresence presences = StoreStream.getPresences();
                    presences.updateActivity(activityType, activity, true);

                    // StoreUserPresence normally sends this during its snapshot. Send the
                    // current local presence immediately too, so a custom activity is not
                    // left visible only in the local UI when no snapshot is scheduled.
                    Presence localPresence = presences.getLocalPresence$app_productionGoogleRelease();
                    if (localPresence != null) {
                        StoreStream.getGatewaySocket().presenceUpdate(
                                localPresence.getStatus(),
                                System.currentTimeMillis(),
                                localPresence.getActivities(),
                                null
                        );
                    }
                } catch (Throwable error) {
                    logger.error("Failed to update CustomRPC presence", error);
                } finally {
                    synchronized (CustomRPC.this) {
                        updatingPresence = false;
                    }
                }
                return Unit.a;
            });
        } catch (Throwable error) {
            logger.error("Failed to schedule CustomRPC presence update", error);
        }
    }

    private Activity createActivity() {
        return createActivity(getActivityFlags());
    }

    private ArrayList<Activity> addGatewayActivity(Object value) {
        List<Activity> existing = value instanceof List
                ? (List<Activity>) value
                : null;
        ArrayList<Activity> activities = existing == null
                ? new ArrayList<>()
                : new ArrayList<>(existing);
        ActivityType customType = getActivityType();
        Iterator<Activity> iterator = activities.iterator();
        while (iterator.hasNext()) {
            Activity activity = iterator.next();
            if (activity != null && activity.p() == customType) iterator.remove();
        }
        activities.add(createActivity());
        return activities;
    }

    private Activity createActivity(int flags) {
        String name = value(NAME, DEFAULT_NAME);
        String details = optional(DETAILS);
        String state = optional(STATE);
        String largeImage = optional(LARGE_IMAGE);
        String largeImageText = optional(LARGE_IMAGE_TEXT);
        String smallImage = optional(SMALL_IMAGE);
        String smallImageText = optional(SMALL_IMAGE_TEXT);
        Long applicationId = parseApplicationId();

        // Discord assets belong to a Developer Application. Keep text-only
        // activities valid when no application ID was configured.
        ActivityAssets assets = applicationId != null && (largeImage != null || largeImageText != null
                || smallImage != null || smallImageText != null)
                ? new ActivityAssets(largeImage, largeImageText, smallImage, smallImageText)
                : null;

        return new Activity(
                name,
                getActivityType(),
                null,
                System.currentTimeMillis(),
                null,
                applicationId,
                details,
                state,
                null,
                null,
                assets,
                flags,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private Long parseApplicationId() {
        String raw = optional(APPLICATION_ID);
        if (raw == null) return null;
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("SameParameterValue")
    private String value(String key, String fallback) {
        String value = optional(key);
        return value == null ? fallback : value;
    }

    private String optional(String key) {
        String value = settings.getString(key, "");
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isSupportedActivityType(ActivityType activityType) {
        return activityType == ActivityType.PLAYING
                || activityType == ActivityType.STREAMING
                || activityType == ActivityType.LISTENING
                || activityType == ActivityType.WATCHING
                || activityType == ActivityType.COMPETING;
    }

    private static String activityTypeLabel(ActivityType activityType) {
        return switch (activityType) {
            case STREAMING -> "Streaming";
            case LISTENING -> "Listening to";
            case WATCHING -> "Watching";
            case COMPETING -> "Competing in";
            default -> "Playing";
        };
    }
}
