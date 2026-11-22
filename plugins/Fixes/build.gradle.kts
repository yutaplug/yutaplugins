version = "1.0.1"
description = "Fixes essential features of Discord 126.21. Each fix can be turned on or off in the plugin settings."

android {
    namespace = "com.github.yutaplug.fixes"
}

aliucord {
    author("TsyQax", 0L, hyperlink = false)
    author("secp192k1", 0L, hyperlink = false)
    author("zt64", 0L, hyperlink = false)
    author("pilotbellyt-spec", 0L, hyperlink = false)
    author("js6pak", 0L, hyperlink = false)
    changelog.set(
        """
        # 1.0.1
        * Added Message width (from zt64's MessageWidthFix): embeds and stickers use the full chat width
        * Added Server settings (from pilotbellyt-spec's ServerSettingsFix): the bans list and audit log load again, and invite durations read properly
        * Added Mentioned users (from js6pak's ValidUser): mentions of users that show as invalid-user are fetched
        * Added GIF sticker animation: GIF stickers follow the sticker animation setting
        * Heading 3 is now normal text size by default, like current Discord
        * MessageWidthFix, ServerSettingsFix and ValidUser are uninstalled automatically, since Fixes replaces them

        # 1.0.0
        * Initial release
        """.trimIndent(),
    )
}
