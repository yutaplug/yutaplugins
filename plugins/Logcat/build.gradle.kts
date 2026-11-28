version = "1.0.2"
description = "Captures Discord's own logcat (Aliucord, Discord, network, media and crash output), plus how long " +
    "Discord's connections, uploads and downloads take, so you can copy or save it with private data redacted."

aliucord {
    changelog.set("""
        # 1.0.2
        * Network timings now also log video loading: each video download (status, size, speed), downloads that stall with no data, the video player's buffering and ready states, and playback errors with their reason. Useful when videos load forever.
        * The Share section is now at the top of the settings.

        # 1.0.1
        * New Debug log page: Discord's debug log with search, a level filter, tap to expand stack traces, and Share, Save and Copy that work with big logs. Aliucord's Open Debug Log entry opens it (switchable in the settings).
        * Replaces and uninstalls the DebugLogEnhancer plugin.

        # 1.0.0
        * Initial release.
    """.trimIndent())
}
