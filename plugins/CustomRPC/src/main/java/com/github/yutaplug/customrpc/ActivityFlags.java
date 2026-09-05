package com.github.yutaplug.customrpc;

// Discord's activity flags. ActivityFlags in 126021 only exposes JOIN, SPECTATE, SYNC and PLAY.
public final class ActivityFlags {
    public static final int INSTANCE = 1;
    public static final int JOIN = 1 << 1;
    public static final int SPECTATE = 1 << 2;
    public static final int JOIN_REQUEST = 1 << 3;
    public static final int SYNC = 1 << 4;
    public static final int PLAY = 1 << 5;
    public static final int PARTY_PRIVACY_FRIENDS = 1 << 6;
    public static final int PARTY_PRIVACY_VOICE_CHANNEL = 1 << 7;
    public static final int EMBEDDED = 1 << 8;

    public static final int[] VALUES = {
            INSTANCE,
            JOIN,
            SPECTATE,
            JOIN_REQUEST,
            SYNC,
            PLAY,
            PARTY_PRIVACY_FRIENDS,
            PARTY_PRIVACY_VOICE_CHANNEL,
            EMBEDDED
    };

    public static final String[] LABELS = {
            "Instance",
            "Join",
            "Spectate",
            "Join request",
            "Sync",
            "Play",
            "Party privacy: friends",
            "Party privacy: voice channel",
            "Embedded"
    };

    public static String label(int flags) {
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            if ((flags & VALUES[i]) == 0) continue;
            if (label.length() > 0) label.append(", ");
            label.append(LABELS[i]);
        }
        return label.length() == 0 ? "None" : label.toString();
    }

    private ActivityFlags() {}
}
