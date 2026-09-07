version = "1.0.5"
description = "Backports viewing Discord Nitro profile effects and profile frames."
aliucord {
    changelog.set(
        """
        # 1.0.5
        * Replace the effect WebView with native APNG rendering
        * Hide one-shot APNG layers after playback completes
        * Add an optional WebView renderer for older Android devices
        * Prevent oversized WebView layers from crashing fullscreen profiles
        # 1.0.4
        * Fix side rails being stretched and rendered above the banner
        # 1.0.3
        * Fix animation looping when reopening the profile
        # 1.0.2
        * Fix profile effects and frames being cut off in fullscreen profile sheets
        # 1.0.1
        * Fixes
        * Don't show them in settings
        """.trimIndent(),
    )
}
