package com.github.yutaplug.readall;

import android.content.Context;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.api.CommandsAPI;
import com.discord.api.channel.Channel;
import com.discord.stores.StoreChannels;
import com.discord.stores.StoreReadStates;
import com.discord.stores.StoreStream;

import java.util.LinkedHashSet;
import java.util.Set;

import rx.Observable;
import rx.Subscriber;

/** Clears unread notifications across the current Discord account. */
@SuppressWarnings("unused")
@AliucordPlugin
public final class ReadAll extends Plugin {
    static final String EXCLUDED_SERVERS = "excludedServers";
    static final String EXCLUDED_DMS = "excludedDMs";

    private static final long COOLDOWN_MS = 60_000L;

    private long lastUsed;

    @Override
    public void start(Context context) throws Throwable {
        settingsTab = new SettingsTab(ReadAllSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings);

        commands.registerCommand("read", "Clear all unread notifications", ignored -> {
            runRead(Scope.ALL);
            return null;
        });
        commands.registerCommand("read all", "Clear server unread notifications only", ignored -> {
            runRead(Scope.SERVER);
            return null;
        });
        commands.registerCommand("read server", "Clear server unread notifications only", ignored -> {
            runRead(Scope.SERVER);
            return null;
        });
        commands.registerCommand("read dm", "Clear direct-message unread notifications only", ignored -> {
            runRead(Scope.DM);
            return null;
        });
    }

    private enum Scope {
        ALL,
        SERVER,
        DM
    }

    private void runRead(Scope scope) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            long elapsed = now - lastUsed;
            if (elapsed < COOLDOWN_MS) {
                long seconds = (COOLDOWN_MS - elapsed + 999L) / 1000L;
                Utils.showToast("Please wait " + seconds + "s before using again");
                return;
            }
            lastUsed = now;
        }

        try {
            StoreReadStates readStates = StoreStream.Companion.getReadStates();
            StoreChannels channels = StoreStream.Companion.getChannels();
            if (readStates == null || channels == null) {
                Utils.showToast("Unread state is unavailable");
                return;
            }

            Observable<Set<Long>> unreadObservable = readStates.getUnreadChannelIds();
            Set<Long> unreadIds = currentUnreadIds(unreadObservable);
            if (unreadIds == null) unreadIds = new LinkedHashSet<>();

            Set<Long> cleared = new LinkedHashSet<>();
            for (Long channelId : unreadIds) {
                if (channelId == null || !cleared.add(channelId)) continue;

                Channel channel = channels.getChannel(channelId.longValue());
                if (channel == null || !isInScope(channel, scope)) {
                    cleared.remove(channelId);
                    continue;
                }

                // This is Discord's native acknowledgement path. It resolves the
                // current most-recent message and handles forum/category channels.
                readStates.markAsRead(channelId);
            }

            String label = scope == Scope.ALL ? "" : scope == Scope.DM ? " DM" : " server";
            if (cleared.isEmpty()) {
                Utils.showToast("No unread" + label + " notifications found!");
            } else {
                Utils.showToast("Cleared " + cleared.size() + " unread" + label + " notifications!");
            }
        } catch (Throwable error) {
            logger.error("ReadAll could not clear unread notifications", error);
            Utils.showToast("Could not clear unread notifications");
        }
    }

    private Set<Long> currentUnreadIds(Observable<Set<Long>> observable) {
        if (observable == null) return new LinkedHashSet<>();

        final Set<Long>[] result = new Set[]{null};
        final Throwable[] error = new Throwable[]{null};
        observable.U(new Subscriber<Set<Long>>() {
            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Throwable throwable) {
                error[0] = throwable;
            }

            @Override
            public void onNext(Set<Long> ids) {
                result[0] = ids;
                unsubscribe();
            }
        });
        if (error[0] != null) throw new IllegalStateException("Could not read unread state", error[0]);
        return result[0];
    }

    private boolean isInScope(Channel channel, Scope scope) {
        long guildId = channel.i();
        boolean isDm = guildId == 0L;
        if (scope == Scope.SERVER && isDm) return false;
        if (scope == Scope.DM && !isDm) return false;
        if (isDm) return !isDMExcluded(channel.k());
        return !isServerExcluded(guildId);
    }

    boolean isServerExcluded(long guildId) {
        return getIds(EXCLUDED_SERVERS).contains(String.valueOf(guildId));
    }

    boolean isDMExcluded(long channelId) {
        return getIds(EXCLUDED_DMS).contains(String.valueOf(channelId));
    }

    Set<String> getIds(String key) {
        Set<String> ids = new LinkedHashSet<>();
        String raw = settings.getString(key, "");
        if (raw == null) return ids;
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) ids.add(trimmed);
        }
        return ids;
    }

    void setIds(String key, Set<String> ids) {
        StringBuilder value = new StringBuilder();
        for (String id : ids) {
            if (value.length() != 0) value.append(',');
            value.append(id);
        }
        settings.setString(key, value.toString());
    }

    void clearExceptions() {
        settings.setString(EXCLUDED_SERVERS, "");
        settings.setString(EXCLUDED_DMS, "");
    }

    @Override
    public void stop(Context context) {
        commands.unregisterAll();
    }
}
