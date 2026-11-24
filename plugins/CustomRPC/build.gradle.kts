version = "1.0.9"
description = "Set a custom Rich Presence from Aliucord settings."
aliucord {
    changelog.set(
        """
        # 1.0.9
        * Rewritten from scratch
        * Settings are now a live copy of your rich presence card; tap any part of it to change it, and changes apply right away
        * Show or hide the activity with the eye button in the top bar
        * Images now need an application ID, with a built-in guide to getting one
        * Discord image links work, and expiring attachment links are renewed automatically
        * Art asset names from the Developer Portal work for other people too
        * Set a custom elapsed time (like "2h played"), a countdown, or hide the time
        * Add up to two link buttons, a party size like "(2 of 4)", and a stream link for Streaming
        * The activity no longer disappears after a reconnect
        * Spotify and other activities of the same type no longer replace your activity while it's shown
        * Removed activity flags, which had no visible effect

        # 1.0.8
        * The activity type and flags dialogs no longer stop opening after you open and close another screen, like settings or an image

        # 1.0.7
        * Use Aliucord's themed dialogs for activity type and activity flags
        * Rebuild settings with Discord's native rows, headers, dividers and text inputs
        # 1.0.6
        * Redesign
        # 1.0.5
        * Fix crash
        # 1.0.3
        * Rewrite CustomRPC entirely in Kotlin while preserving existing settings
        * Redesign settings and selection dialogs with themed cards and a live activity text preview
        * Add application ID and image URL validation and separate controls to save settings or save and enable the activity
        * Prevent queued presence updates from restoring disabled activities and keep activity timestamps stable
        * Restore replaced activities when disabling CustomRPC or changing activity type
        * Prevent outdated image requests from repopulating cleared caches
        * Use Discord's presence snapshots to avoid redundant gateway updates
        # 1.0.2
        * Re-add option for application id and clarify https description
        # 1.0.1
        * Change setting images to use public https and remove application id field
        """.trimIndent(),
    )
}
