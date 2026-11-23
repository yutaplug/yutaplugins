version = "1.0.1"
description = "Backports profile connection details, including Steam games, Reddit karma, and member dates."

android {
    namespace = "com.github.yutaplug.connectioninfo"
}

aliucord {
    changelog.set(
        """
        # 1.0.1
        * Tapping a connection opens its menu again after you open and close another screen, like settings or an image

        # 1.0.0
        * Initial release
        """.trimIndent(),
    )
}
