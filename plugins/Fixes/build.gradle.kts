version = "1.0.4"
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
        # 1.0.4
        * Markdown: rewritten from scratch
        * Markdown: code blocks no longer lose their background, also after an edit, and fit inside quotes and lists. One-line ```code``` shows as a box too
        * Markdown: numbered lists now render after a line of text
        * Markdown: each heading can have its own color, set in Markdown appearance
        * Markdown: reply previews show a heading's text instead of a big heading
        * Markdown: the settings explain what each part of the fix does

        # 1.0.3
        * Added Plugin menus: menus and popups from plugins no longer stop opening after you open and close another screen, like settings or an image
        * Added Message menu in search: holding a message in search opens its menu instead of jumping to it
        * Added Server long press: the menu from holding a server or folder no longer closes by itself when you let go
        * Markdown: the reset dialog in the settings no longer stops opening after you open and close another screen, like settings or an image

        # 1.0.2
        * Markdown: numbered lists (1. 2. 3.) now render, and dashes after a numbered item continue its numbering like on current Discord

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
