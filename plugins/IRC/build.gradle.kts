version = "1.0.3"
description = "Recreates Discord desktop's IRC-style compact chat layout."

aliucord {
    changelog.set(
        """
        # 1.0.3
        * Fix settings spacing to match Discord's.
        * Fix extra space above and below replies, and show the replied message on one line like desktop.
        * Align reactions, embeds, videos, stickers and bot components with the message text.
        * Lay out system messages like desktop compact mode, with the time and icon on the same line.
        * Show search results in Discord's regular layout, like desktop.
        * Keep timestamps clear of the highlight bar on mentions and replies to you.
        * Support MessageLatency's indicator before the author name.
        # 1.0.2
        * Make it more like desktop-style and fix spacing issues.
        # 1.0.1
        * Rewrite the plugin entirely in Kotlin.
        * Hide avatars by default and add a Show avatars setting.
        * Fix author and avatar alignment for grouped messages and replies.
        * Preserve themed author fonts and remove forced bold styling.
        * Align authors and message text on the same baseline.
        * Wrap long names and let message continuation lines flow beneath them.
        * Match reply and message spine thickness and stop the spine at the end of the author name.
        # 1.0.0
        * Add an IRC-style compact chat layout with inline authors, timestamps, avatars, and message spines.
        """.trimIndent(),
    )
}
