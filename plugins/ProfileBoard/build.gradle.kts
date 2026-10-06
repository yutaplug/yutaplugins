version = "1.0.4"
description = "Backports Discord Profile Board and Wishlist sections to Discord 126.21 profiles."

aliucord {
    changelog.set(
        """
        # 1.0.4
        * Make games openable
        # 1.0.3
        * Hide empty Board and Wishlist tabs, and hide the tab row when neither section has content
        * Match Discord 126.21 tab styling and touch feedback
        * Improve Wishlist previews for avatar decorations, profile effects, frames, and nameplates
        * Show checkmarks on owned Wishlist items and remove empty grid tiles
        * Fix Wishlist images not loading
        """.trimIndent(),
    )
}

android {
    namespace = "com.github.yutaplug.profileboard"
}
