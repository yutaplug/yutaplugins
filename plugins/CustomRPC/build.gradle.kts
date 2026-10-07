version = "1.0.7"
description = "Set a custom Rich Presence from Aliucord settings."
aliucord {
    changelog.set(
        """
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
