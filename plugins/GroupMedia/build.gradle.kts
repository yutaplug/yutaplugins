version = "1.0.2"
description = "Groups several images and videos sent in one message into a grid, and lets you swipe between them in fullscreen."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Renamed from GroupImages to GroupMedia; the old GroupImages plugin is uninstalled automatically
        * Tapping a video's play button in the grid now opens it fullscreen

        # 1.0.1
        * New setting to hide the arrows in the fullscreen viewer (swiping still works)

        # 1.0.0
        * Show images and videos sent together in one message as a grid, like newer Discord
        * Swipe or tap the arrows in fullscreen to switch between them, with an "x/x" counter
        """.trimIndent(),
    )
}
