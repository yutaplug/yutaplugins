version = "1.1.1"
description = "Backports Discord profile effects, profile frames, profile themes, role gradients, and display name fonts and colors."
aliucord {
    changelog.set(
        """
        # 1.1.1
        * Show display name styles in reply previews
        * Role colors now win over display name style colors, as on Discord; the font still shows
        * Draw display name fonts bold and at Discord's size
        * Use the default font for the fonts Discord retired
        * Add a setting to hide display name colors in the friend and DM lists
        # 1.1.0
        * Rewrite the plugin
        * Show profile theme gradients on profiles
        * Show role gradients on names in chat and the member list
        * Show display name fonts, colors and effects in chat, the member list, profiles, the DM list and the friend list
        * Add settings to turn each feature on or off, including profile effects and frames
        * Uninstall ModernUserStyles and ModernProfiles, which this plugin replaces
        # 1.0.11
        * Replace the effect WebView with a native APNG renderer that decodes one frame at a time
        * Lower memory use and improve compatibility with older Android versions
        * Cache effect assets on disk
        # 1.0.9
        * Rewrite the plugin in Kotlin
        * Fix crashes when closing profile sheets and improve overlay and WebView cleanup
        * Improve profile loading, collectible caching, and rate-limit handling
        * Fix effect scaling, animation restarts, and loop timing
        * Respect reduced motion settings
        # 1.0.8
        * Fix crash
        # 1.0.7
        * Return to WebView for compatibility
        # 1.0.6
        * Fix WebView layer removing
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
