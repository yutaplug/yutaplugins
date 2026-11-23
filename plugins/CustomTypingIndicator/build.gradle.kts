version = "1.0.3"
description = "Backports Discord's custom typing indicators: see other people's emojis and phrases in the typing bar, and pick your own."
aliucord {
    changelog.set(
        """
        # 1.0.3
        * Tapping a name in the typing bar opens their profile again after you open and close another screen, like settings or an image
        * The editor's dialogs and emoji picker no longer stop opening after you open and close another screen, like settings or an image

        # 1.0.2
        * Show your saved typing indicator when reopening the editor
        * Show Discord's captcha when saving asks for one, then save again
        # 1.0.1
        * Add plugin settings, separate from the typing indicator editor in User Settings
        * Add an option to show avatars next to names in the typing bar
        * Add an option to open profiles by tapping names in the typing bar
        """.trimIndent(),
    )
}
