version = "1.0.2"
description = "Marks unread channels as read from the DM icon menu or the /readall command."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Fix settings spacing to match Discord's.

        # 1.0.1
        * Rewrite the plugin in Kotlin and improve read-state handling.
        * Hold the DM icon to open a Read All menu.
        * Add a setting to use one /readall command instead, with an option to include DMs.
        """.trimIndent(),
    )
}
