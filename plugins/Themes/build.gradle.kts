version = "1.0.9"
description = "Aliucord theme engine compatible with Themer themes, that keeps working on modern Android."
aliucord {
    changelog.set(
        """
        # 1.0.9
        * Discord links now work for backgrounds, fonts, sounds and icons. Expiring attachment links are renewed automatically.
        * Themes with a Discord background are only turned off when the image was deleted from Discord.
        * Fix Discord crashing when the screen was rotated or the app was in the background while picking a file in the theme editor.

        # 1.0.8
        * Fix Discord crashing when opening the Icons page of the theme editor once a theme has icons set.

        # 1.0.7
        * Backgrounds, fonts, sounds and icons can now be picked from your gallery or files app in the theme editor, not only added as links.

        # 1.0.6
        * Themes can now replace any of Discord's icons, with an image or with another Discord icon. Set them in the new Icons page of the theme editor.
        * Added a Custom icons switch to turn off icons from themes.
        * Fix some features unsupported on your device not being listed in the Status section.

        # 1.0.5
        * Fix some elements not being themed on some newer Android versions.

        # 1.0.4
        * Fix settings pages crashing with an animated background in Chat & Settings transparency mode.

        # 1.0.3
        * Fix the Themes settings and theme editor closing when a change was applied.

        # 1.0.2
        * Changes made in the Themes settings and theme editor now apply right away, without going back first.

        # 1.0.1
        * Fix the status bar staying hidden after restarting Discord with a theme enabled.
        * Fix icons such as the log out button becoming invisible with themes that use transparent backgrounds.
        * Fix muted channels and forum thread lines becoming invisible. Colors now change exactly where they did with Themer.

        # 1.0.0
        * Initial release.
        """.trimIndent(),
    )
}
