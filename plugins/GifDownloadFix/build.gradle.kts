version = "1.0.2"
description = "Keeps Tenor, Klipy, and Giphy GIF downloads named with a .gif extension."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Fix a possible crash when saving GIFs.

        # 1.0.1
        * Add Klipy GIF download progress, completion, and failure notifications.
        * Show Klipy download errors instead of failing silently.
        * Retry the direct Klipy source if Discord's media proxy fails.
        * Avoid unnecessary legacy storage permission requests on Android 10+ in the media viewer.

        # 1.0.0
        * Keep Tenor, Klipy, and Giphy GIF downloads named with a .gif extension.
        """.trimIndent(),
    )
}
