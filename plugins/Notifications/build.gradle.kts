version = "1.0.2"
description = "Backports account notification preferences to a dedicated Notifications settings page."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Fix section headers, dividers and descriptions to fully match Discord's settings style.
        # 1.0.1
        * Redesign the settings page with native Discord headers and spacing.
        * Remove the "Settings saved" and "Saving changes" status messages; only loading and errors are shown.
        # 1.0.0
        * Initial release
        """.trimIndent(),
    )
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(rootProject.libs.kotlin.stdlib)
}
