version = "1.0.2"
description = "Backport Discord's 20 MB free upload limit."

android {
    namespace = "com.github.yutaplug.uploadsize20mb"
}

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Make RN header setup resilient to app startup timing.
        * Make the size override deterministic across app restarts.

        # 1.0.1
        * Fix the 20 MB limit being lost after an app restart.

        # 1.0.0
        * Backported Discord's 20 MB free upload limit.
        * Updated the legacy RN upload metadata used by the core upload transport.
        """.trimIndent(),
    )
    deploy.set(false)
}
