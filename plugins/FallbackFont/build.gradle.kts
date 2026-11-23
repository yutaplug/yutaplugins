version = "1.0.2"
description = "Use custom fonts for text, emoji, and characters your device fonts cannot display."

aliucord {
    changelog.set("""
        # 1.0.2
        * The font menu and picker no longer stop opening after you open and close another screen, like settings or an image.

        # 1.0.1
        * Added a text font that replaces Discord's font.
        * Added an emoji font that replaces your device's emoji, and a fallback emoji font for emoji it's missing.
        * Added an option to show emoji as text instead of Twemoji images, keeping emoji-only messages large.
        * Added a second fallback font for characters the first one is missing.

        # 1.0.0
        * Initial release.
    """.trimIndent())
}
