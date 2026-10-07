version = "1.0.3"
description = "Adds a Server Discovery button to the server list."

aliucord {
    changelog.set(
        """
        # 1.0.3
        * Make tap feedback visible on the light theme.
        * Use the theme's brand color while server banners load.
        # 1.0.2
        * Fix search
        # 1.0.1
        * Redesign discovery with category tabs, an illustrated search header, and server banner cards using Discord 126.21's native styles.
        * Fix the discovery header width and a Kotlin runtime crash when opening discovery.
        * Open Discord's native server preview with its Join Server bar directly when tapping a server.
        """.trimIndent(),
    )
}

android {
    namespace = "com.github.yutaplug.serverdiscovery"
}
