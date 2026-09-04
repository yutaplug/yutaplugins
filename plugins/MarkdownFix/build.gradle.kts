version = "1.0.1"
description = "Backports Discord's newer Markdown formatting to chat messages."

aliucord {
    changelog.set(
        """
        # 1.0.1
        * Updated the parser hook for compatibility with current Aliucord builds.
        * Enabled the backported Markdown rules consistently in replies and forum posts.
        * Improved nested-list indentation handling for nested bullet markers.
        * Improved parsing of indented -# subtext.
        * Increased and differentiated the sizes of #, ##, and ### headers.

        # 1.0.0
        * Backport headers, subtext, bullet lists, nested lists, and masked links.
        """.trimIndent(),
    )
}
