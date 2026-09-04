version = "1.0.1"
description = "Backports Discord's newer Markdown formatting to chat messages."

aliucord {
    changelog.set(
        """
        # 1.0.1
        * Fixed nested list bullets not rendering correctly.
        * Fixed headers and subtext inside list items.
        * Increased and differentiated the sizes of #, ##, and ### headers.

        # 1.0.0
        * Backport headers, subtext, bullet lists, nested lists, and masked links.
        """.trimIndent(),
    )
}
