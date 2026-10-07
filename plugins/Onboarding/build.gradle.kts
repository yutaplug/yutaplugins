version = "1.0.6"
description = "Backports community onboarding and Channels & Roles to Discord 126.21."

aliucord {
    changelog.set(
        """
        # 1.0.6
        * Use Aliucord's themed dialogs for dropdown questions, the discard prompt and save errors.
        * Fix a possible crash when searching channels.

        # 1.0.5
        * Re-add button to check onboarding
        * Fix twemojis

        # 1.0.4
        * Add a Show All Channels toggle to the server sheet, synced with Browse Channels.
        * Fix saving Channels & Roles when initial onboarding is incomplete (Discord error 350002).

        # 1.0.3
        * Fix a crash when opening Channels & Roles by using Discord's native dialog button styles.
        * Remove the Check Onboarding button. Joining-server onboarding and customization questions remain available.

        # 1.0.2
        * Use Discord 126.21's native toolbar, tabs, fonts, and controls.
        * Add channel search and expanded category groups with Follow Category controls.
        * Show channel icons, topics, and recent activity.
        * Fix category controls becoming unresponsive after a failed update.

        # 1.0.1
        * Allow disabling channels even if it's a default channel
        """.trimIndent(),
    )
}

android {
    namespace = "com.github.yutaplug.onboarding"
}
