version = "1.0.5"
description = "Backport super reactions."
aliucord {
    changelog.set(
        """
        # 1.0.5
        * Reduce reaction metadata requests to avoid Discord rate limits on messages with many reactions
        * Add rate-limit backoff and stop retrying alternate send endpoints after HTTP 429
        * Fix existing super-reaction counts resetting after sending a new super reaction
        # 1.0.4
        * Fix shine not applying sometimes
        * Fix not being able to remove a super-reaction sent from official Discord
        * Move the context menu button to the bottom
        # 1.0.3
        * Fix member list
        # 1.0.2
        * Fixes
        # 1.0.1
        * Match shine color to emoji
        """.trimIndent(),
    )
}