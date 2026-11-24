version = "1.0.15"
description = "Plays direct video and audio link embeds in Discord's built-in media player."

aliucord {
    changelog.set(
        """
        # 1.0.15
        * Rotating fullscreen to landscape now works on Android 7 and older instead of showing a black screen
        * Players pause as soon as they scroll off screen instead of a second later
        * New "Copy debug log" setting to paste into bug reports

        # 1.0.14
        * The seekbar moves to its own row above the controls when extra buttons are shown, so it no longer shrinks to nothing
        * On Android 7 and older, videos no longer go black after going fullscreen or when the screen is rebuilt

        # 1.0.13
        * The screen no longer dims or locks while a video or audio is playing

        # 1.0.12
        * The quality menu and the download permission prompt no longer stop opening after you open and close another screen, like settings or an image

        # 1.0.11
        * Embeds now play in search results too; tapping anything else still jumps to the message
        * Download button in the player saves the video or audio to Downloads
        * New setting to rotate the screen to landscape in fullscreen
        * When YouTube refuses a video, show why instead of opening the YouTube app

        # 1.0.10
        * YouTube videos now start almost instantly: they stream while playing instead of downloading first
        * Choose the quality in the YouTube player, and a default quality in the plugin settings
        * New setting to play videos in place of the whole embed card
        * YouTube videos watched to the end replay without loading until the app restarts

        # 1.0.9
        * Pause players once they scroll out of view
        * Show a loading popup with progress and time left while a YouTube video loads

        # 1.0.8
        * Remove WebViews: YouTube, SoundCloud and Spotify now play in Discord's native player
        * YouTube videos play inside the embed, with a fullscreen button
        * SoundCloud tracks play over their artwork; sets play their first track
        * Spotify plays 30-second previews (full tracks only work in the Spotify app)
        * Links that can't play natively open in their app or browser
        * Stop players when scrolled away
        * Fix previously played videos showing up in other embeds

        # 1.0.6
        Refactor and cleanup

        # 1.0.5
        * Play signed, extensionless video embeds such as OGInstagram reels using Discord's proxy URL.
        * Keep inline videos playing when message rows refresh.
        * Fall back to Discord's normal click behavior when the inline player cannot be displayed.
        * Keep hosted clicks usable when a preview is unavailable.
        * Load Spotify short links in the embed player after they resolve.
        * Handle YouTube playlists and already embedded SoundCloud widget URLs.

        # 1.0.4
        * Play extensionless video URLs using the media type supplied in the embed, without adding service-specific host rules.

        # 1.0.3
        * Load YouTube's /embed/{id} endpoint directly instead of wrapping it in a synthetic iframe page.
        * Fix Spotify embeds failing to load the playable player.
        * Keep YouTube playback active when adding reactions refreshes the message.
        * Replace the entire YouTube embed card with the player again.
        * Fix touch handling so GIFs and other embeds can be tapped and opened normally.
        * Avoid Kkinstagram-specific handling and leave Discord-native embeds untouched.

        # 1.0.2
        * Keep hosted videos playing when reactions refresh their embed rows.
        * Let Discord handle Kkinstagram embeds without plugin interception.
        * Keep selected YouTube videos inside the inline player after fullscreen.

        # 1.0.1
        * Improve YouTube player loading and error reporting.
        * Play hosted media inside the original image area at its default size, preserving the embed layout.
        * Prevent panel swipes from interrupting the inline YouTube seekbar.
        * Fix search-result clicks playing embeds instead of jumping to the message.

        # 1.0.0
        * Play direct video and audio link embeds in Discord's built-in media player.
        """.trimIndent(),
    )
}
