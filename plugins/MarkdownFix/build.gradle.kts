version = "1.0.16"
description = "Backports Discord's newer Markdown formatting to chat messages."

android {
    namespace = "com.github.yutaplug.markdownfix"
}

aliucord {
    changelog.set(
        """
        # 1.0.16
        * Pop-up redesign
        # 1.0.15
        * Another redesign
        # 1.0.14
        * Improve game mentions and make them clickable
        * Support viewing slash command mentions
        # 1.0.13
        * Redesign
        # 1.0.12
        * Add support for hex colors
        # 1.0.11
        * Modernize settings pop-ups with rounded layouts and styled buttons
        * Add inline validation, keyboard Done support, and a live bullet-color preview
        * Add a bullet-color picker with hue, saturation, brightness, and opacity sliders
        # 1.0.10
        * Rewrite the plugin in Kotlin and unify Markdown parsing across messages, forum posts, and embeds
        * Fix quote/list alignment, nested formatting, and trailing quote spacing
        * Make bullets compact by default and remove the compact bullet option
        * Improve settings with a live preview, immediate updates, and reset controls
        * Remove restart prompts
        * Fix spoilers in Markdown links, game mention updates, and ANSI style resets
        # 1.0.9
        * Add support for game mentions and ansi colors
        # 1.0.8
        * Fix NewEmojis and TextEmoji
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
