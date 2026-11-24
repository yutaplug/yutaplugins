version = "1.0.3"
description = "Marks every unread channel as read by holding the DM icon or with the /readall command."

aliucord {
    changelog.set(
        """
        # 1.0.3
        * Rewritten from scratch. Holding the DM icon and the /readall command now both work, and the settings are gone.
        * Choose servers, DMs or everything, from the menu or the command's scope option.
        * Channels are marked read in batches instead of one request each, so large servers no longer hit rate limits.
        * Muted channels are marked read too, so their mention badges clear.
        * The menu opens on the screen you're on and keeps working after the app recreates it.

        # 1.0.2
        * Fix settings spacing to match Discord's.

        # 1.0.1
        * Rewrite the plugin in Kotlin and improve read-state handling.
        * Hold the DM icon to open a Read All menu.
        * Add a setting to use one /readall command instead, with an option to include DMs.
        """.trimIndent(),
    )
}
