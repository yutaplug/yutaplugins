version = "1.1.4"
description = "Backports Discord profile effects, profile frames, profile themes, role gradients, and display name fonts and colors."
aliucord {
    changelog.set(
        """
        # 1.1.4
        * Show profile frames whose data Discord nests under variants or bundles, or whose layers use other asset IDs
        * Log profile frames the plugin can't draw, with Discord's data, to the debug log
        * Use FallbackFont's fonts for characters a display name font can't draw, including in Neon, Toon and Pop names
        * Draw the Vampyre display name font at Discord's weight
        # 1.1.3
        * Fix the app crashing at launch on Android 7
        * Match Discord's look for every display name effect, including Prism and Gummy
        * Match Discord's display name font sizes, and use 8Bit's medium weight
        * Use the right font weights for Monkey Bars and Modern on Android 7 and older
        * List Prism and Gummy in the settings
        # 1.1.2
        * Fix display name styles in reply previews only showing some of the time
        * Fix display name styles missing for users Discord hadn't cached
        * Show display name styles in the DM header
        * The list color setting now also covers messages in DMs
        * Match Discord's font weights, letter spacing and gradients for display names
        * Show random profile effect variants and match Discord's effect timing and sizing
        * Place border-type profile frame layers correctly
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
