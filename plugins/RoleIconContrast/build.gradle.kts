version = "1.0.0"
description = "Adds a light backdrop so dark Discord role icons are easier to see."

android {
    namespace = "com.github.yutaplug.roleiconcontrast"
}

aliucord {
    changelog.set(
        """
        # 1.0.0
        * Added a light backdrop behind role icons to improve visibility for dark icons.
        """.trimIndent(),
    )
}
