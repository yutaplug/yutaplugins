version = "1.1.0"
description = "Backport Discord's 20 MB free upload limit"

android {
    namespace = "com.github.yutaplug.uploadsize20mb"
}

aliucord {
    changelog.set(
        """
        # 1.1.0
        * Backported Discord's 20 MB free upload limit.
        * Updated legacy RN upload request metadata for current Discord treatment.
        """.trimIndent(),
    )
    deploy.set(false)
}
