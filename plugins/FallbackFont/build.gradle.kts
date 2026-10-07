version = "1.0.1"
description = "Use custom fonts for text, emoji, and characters your device fonts cannot display."

aliucord {
    changelog.set("""
        # 1.0.1
        * Added a text font that replaces Discord's font.
        * Added an emoji font that replaces your device's emoji, and a fallback emoji font for emoji it's missing.
        * Added an option to show emoji as text instead of Twemoji images, keeping emoji-only messages large.
        * Added a second fallback font for characters the first one is missing.

        # 1.0.0
        * Initial release.
    """.trimIndent())
}
