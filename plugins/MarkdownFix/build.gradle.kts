version = "1.0.7"
description = "Backports Discord's newer Markdown formatting to chat messages."
aliucord {
    changelog.set(
        """
        # 1.0.7
        * Fix consecutive bullets rendering
        # 1.0.6
        * Fixed compact quotes
        # 1.0.5
        * Fixed empty lines before headers and subtext
        # 1.0.4
        * List parsing now only applies at the start of a line
        # 1.0.3
        * Fixed spacing between quotes and bold text hyperlinks
        # 1.0.2
        * Added settings and fixed markdown inside embeds
        # 1.0.1
        * Fix forum posts
        """.trimIndent(),
    )
}
