version = "1.0.1" // Plugin version. Increment this to trigger an update
description = "Keeps deleted messages and edit history visible in Discord chats." // Plugin description that will be shown to user

aliucord {
    changelog.set(
        """
        # 1.0.1
        * Fixed message emojis not loading when deleted-message labels are applied.
        * Fixed the deleted marker not appearing on restored messages.
        * Moved BetterMessageLogger actions below the reaction picker.

        # 1.0.0
        * Initial plugin release!
        """.trimIndent(),
    )
}
