version = "1.1.4"
description = "Plays direct video and audio link embeds in Discord's built-in media player."

aliucord {
    changelog.set(
        """
        # 1.1.4
        * GIFs from X (Twitter) and FxTwitter links open in Discord's fullscreen viewer again instead of opening the tweet

        # 1.1.3
        * X (Twitter) videos play inline instead of failing with a playback error; tweets without a playable video open in the app or browser

        # 1.1.2
        * Embeds pause when you leave the chat for another screen (like user settings), open a link, or minimize the app

        # 1.1.1
        * Videos and quality changes show just a loading spinner instead of a progress box
        * The captions button turns captions on or off right away when there's only one language, and opens a bottom sheet to pick one when there are more

        # 1.1.0
        * Cleaner, more compact video controls: play/pause in the middle, buttons in the top right, and a slim time and seekbar row at the bottom
        * Lighter, more see-through background behind the controls
        * Tapping outside a player hides its controls
        * Captions have a lighter background that fits each line instead of one box as wide as the longest line

        # 1.0.21
        * When the controls don't fit, the seekbar keeps a full-width row with the times beside it, and the buttons stay at the bottom
        * The captions and speed menus open in rotated fullscreen

        # 1.0.20
        * Captions button in YouTube players, including auto-generated captions; the language you pick turns on in later videos too
        * Playback speed button in YouTube players, from 0.25x to 2x
        * Fix a crash on vertical videos such as YouTube Shorts
        * The debug log also goes to logcat (tag PlayEmbeds)

        # 1.0.19
        * Swiping on a player to open the channel list or member list works again; only drags on the seekbar stay with the player

        # 1.0.18
        * On narrow (portrait) videos, the fullscreen, download and quality buttons move up beside the seekbar instead of being cut off

        # 1.0.17
        * On Android 7 and older, rotating fullscreen turns the video sideways instantly instead of rotating the screen

        # 1.0.16
        * Rotating fullscreen to landscape no longer makes Discord blink on the way in or out

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
