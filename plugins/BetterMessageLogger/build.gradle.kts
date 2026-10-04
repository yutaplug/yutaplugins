version = "1.0.24"
description = "Keeps deleted messages and edit history visible in Discord chats."

aliucord {
    changelog.set(
        """
        # 1.0.24
        * Small redesign and add toggle to keep media after restart
        # 1.0.23
        * Pop-up redesign
        # 1.0.22
        * Logged images and videos are saved and stay visible after restarting the app.
        * Media is pre-downloaded as messages arrive, so deleted attachments show even if you weren't in the channel (can be turned off).
        * Deleted media also shows for the current session when the database is off.
        * Added a Message Logger entry to server, channel and DM long-press menus to block or allow them quickly.
        * Compact settings, filter lists with names, and a cleaner edit history popup.
        # 1.0.21
        * Redesign
        # 1.0.20
        * Database migration
        # 1.0.19
        * Modernize pop-ups in settings
        # 1.0.18
        * Rewrite the plugin entirely in Kotlin.
        * Modernize settings and the edit-history popup with selectable versions and individual copy actions.
        * Improve database reliability and edit-history storage, with bounded caches and loading limited to the current message range.
        * Store logs in Aliucord/BetterMessageLogger.db without WAL, SHM, or disk journal files.
        # 1.0.17
        * Fix NitroSpoof
        # 1.0.16
        * Remove inline edit history immediately when deleting a logged message.
        # 1.0.15
        * Add visual color picker
        # 1.0.14
        * Add option to show edit logs in chat history (like other loggers)
        * Add option to change text color (eleted tag doesnt need to be hidden now)
        # 1.0.13
        * Fix HideMessages
        # 1.0.12
        * Add option to disable deleted tag
        * Add option to disable edit logs
        # 1.0.11
        * Fix PluginDownloader in link context menu
        # 1.0.10
        * Added a customizable color for the deleted-message tag and fixed crashes caused by excessive message-cache memory usage.
        # 1.0.9
        * Fixed context menus failing after deleting messages with images.
        # 1.0.8
        * Fixed deleted bot messages not being logged or restored.
        # 1.0.7
        * Fixed deleted messages being repositioned as older or newer live batches load.
        # 1.0.6
        * Fixed logged messages interfering with older/newer loading, channel jumps, and re-entry.
        # 1.0.5
        * Fixed database messages interfering with loading older live messages.
        # 1.0.4
        * Fixed channel and server whitelist/blacklist filtering for your own messages.
        # 1.0.3
        * Fixed link long-press context menus so PluginDownloader actions remain available.
        # 1.0.2
        * Modernized the settings screen with grouped sections, switches, action buttons, and improved spacing.
        # 1.0.1
        * Fixed message emojis not loading when deleted-message labels are applied.
        * Fixed the deleted marker not appearing on restored messages.
        * Moved BetterMessageLogger actions below the reaction picker.
        # 1.0.0
        * Initial plugin release!
        """.trimIndent(),
    )
}
