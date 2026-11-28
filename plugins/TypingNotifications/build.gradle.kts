version = "1.0.2"
description = "Notifies you in-app and with push notifications whenever someone starts typing."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * The in-app banner shows right away instead of sometimes waiting behind Discord's own notices or showing late
        * The banner no longer disappears when Discord reopens its screen shortly after startup
        * The banner's top-right button is a close button instead of a settings gear
        """.trimIndent(),
    )
}
