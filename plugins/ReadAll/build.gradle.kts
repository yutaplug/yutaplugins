version = "1.0.0"
description = "Clears unread server and direct-message notifications with slash commands."

android {
    namespace = "com.github.yutaplug.readall"
}

aliucord {
    changelog.set(
        """
        # 1.0.0
        * Ported Read All from Apex Plugins.
        * Added `/read`, `/read all`, `/read server`, and `/read dm`.
        * Added server and DM exception lists.
        """.trimIndent(),
    )
}
