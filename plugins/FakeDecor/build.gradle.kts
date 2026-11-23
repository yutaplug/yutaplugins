version = "1.0.5"
description = "Allow users to set a custom avatar frame. Shares API with Vencord."
aliucord {
    changelog.set(
        """
        # 1.0.5
        * The settings dialogs no longer stop opening after you open and close another screen, like settings or an image

        # 1.0.4
        * Fix uploading custom decorations doing nothing after picking an image
        * Popups now use Discord's themed dialogs
        # 1.0.3
        * Fixes
        # 1.0.2
        * Rewrite in Kotlin
        * Redesign settings page and add images for decor presets
        # 1.0.1
        * Fix decos refreshing and add compat with ViewProfileImages
        * Add setting to preserve original discord decor if available
        """.trimIndent(),
    )
}
