version = "1.0.13"
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
        # 1.0.13
        * GitHub updater: new "Update from the popup" option. Turn it off and tapping the updates popup opens the Updater, so you can read the changelogs and choose what to install

        # 1.0.12
        * Attachment previews: new fix for the preview of a file you add to a message (like a .zip) sometimes showing an image you added before

        # 1.0.11
        * New badges: turned off for now, since it could log you out of Discord. It will come back once it's safe
        * GitHub updater: update messages are now a popup at the top of the app instead of a bar at the bottom, so they don't cover the chat or the navigation bar. Tap it to update, or swipe it away
        * GitHub updater: new options to turn the popups off, or to also get a phone notification
        * GitHub updater: ignore plugins you don't want updates for, from their update or under Ignored
        * GitHub updater: checks again if GitHub couldn't be reached right after starting

        # 1.0.10
        * GitHub updater: no longer replaces a plugin you installed by hand before restarting with an older version from GitHub. It now shows that a restart is needed instead
        * GitHub updater: plugin updates show when the new version was released
        * GitHub updater: tap a plugin under Up to date to see its changelog

        # 1.0.9
        * Screen refresh: now on by default. It also stops the second reload Discord started right after the fix put your language back, which is why it didn't work before

        # 1.0.8
        * GitHub updater: replaces the Raw GitHub updater, and Aliucord's own updater for both plugins and Aliucord itself. Updates come straight from GitHub at the latest commit, so new versions show up right away, and each download is checked before it replaces the old version, which fixes plugins asking to restart over and over
        * GitHub updater: a new Updater screen, a message with an Update button when updates are found at startup, a restart prompt after updating, and a message saying what was updated after the restart, with its changelog
        * GitHub updater: Aliucord's changelog now shows what changed, from the Aliucord documentation site

        # 1.0.7
        * New badges: new fix that shows newer Discord badges on profiles, like Account Age, which Discord didn't send to Aliucord
        * Paste images: new fix that lets you paste images from the clipboard into the chat box as attachments
        * Screen refresh: off by default for now, since it doesn't work for everyone yet. If you were asked to, you can turn it on and save a log from the plugin settings

        # 1.0.6
        * Screen refresh: new fix for the app reloading and jumping back to the start of the chat or settings after you close an image, a video or a settings page, on some phones
        * Message links: links to a channel, thread or forum post now open in Aliucord instead of the browser
        * Message links: jumping to a message in a forum post or thread that isn't loaded yet, from search, a link or a reminder, now opens it instead of the server's default channel

        # 1.0.5
        * Slow uploads and downloads: new fix for uploads and media loading very slowly on networks without IPv6, where the app first waited for IPv6 connections to time out

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
