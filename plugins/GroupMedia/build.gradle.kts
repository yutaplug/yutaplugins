version = "1.0.5"
description = "Groups several images and videos sent in one message into a grid, and lets you swipe between them in fullscreen."

aliucord {
    changelog.set(
        """
        # 1.0.5
        * The fullscreen viewer now loads the message's other images in the background, so swiping to them shows them right away

        # 1.0.4
        * The fullscreen viewer now shows a row of thumbnails of the message's images and videos; tap one to switch to it
        * The "x/x" counter now also shows on videos

        # 1.0.3
        * The play button on videos in the grid now always opens them fullscreen instead of playing in the small tile

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
