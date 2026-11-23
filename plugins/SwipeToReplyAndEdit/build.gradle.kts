version = "1.0.4"
description = "Swipe a message from right to left to reply to it, or to edit your own messages."

aliucord {
    changelog.set(
        """
        # 1.0.4
        * The keyboard opens after swiping again after you open and close another screen, like settings or an image
        # 1.0.3
        * Added a setting for how long you have to hold the swipe to edit your own message
        # 1.0.2
        * Automatically uninstalls SwipeToReply and SwipeActions, which conflict with this plugin
        # 1.0.1
        * Hold the swipe on your own message for a moment to edit it instead of replying
        * Renamed the setting to "Instantly edit own messages", which skips the hold and edits right away
        # 1.0.0
        * Initial release
        """.trimIndent(),
    )
}
