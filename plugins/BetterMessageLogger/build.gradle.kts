version = "1.0.15"
description = "Keeps deleted messages and edit history visible in Discord chats."

aliucord {
    changelog.set(
        """
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
