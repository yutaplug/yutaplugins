package com.github.yutaplug.playembeds

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SelectDialog
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.message.embed.MessageEmbed
import com.discord.app.AppActivity
import com.discord.player.AppMediaPlayer
import com.discord.player.MediaSource
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class PlayEmbeds : Plugin() {
    private val embedLinks = WeakHashMap<ViewGroup, EmbedLink>()
    private val configuredEmbeds = WeakHashMap<View, MessageEmbed>()
    private val hostedEmbeds = WeakHashMap<ViewGroup, MessageEmbed>()
    private val embedRowKeys = WeakHashMap<ViewGroup, Pair<Long, Int>>()
    private val searchEmbedClicks = WeakHashMap<ViewGroup, SearchEmbedClick>()
    private val players = WeakValueMap<ViewGroup, NativePlayerState>()
    private val loadingIndicators = WeakValueMap<ViewGroup, View>()
    private val spotifyPlayButtons = WeakValueMap<ViewGroup, ImageView>()

    /** Keyed by the player root currently moved into a fullscreen overlay. */
    private val fullscreenPlayers = WeakHashMap<View, FullscreenState>()

    /** Keyed by each player's ExoPlayer listener (`AppMediaPlayer.g`), which receives errors. */
    private val errorCallbacks = WeakHashMap<Any, () -> Unit>()

    /** Quality picked in a YouTube player, by embed URL, so replaying the video keeps it. */
    private val youtubeQualityChoices = HashMap<String, Int>()

    private var youtube: YoutubeResolver? = null
    private var soundCloud: SoundCloudResolver? = null
    private var spotify: SpotifyResolver? = null
    private var downloader: MediaDownloader? = null

    /** The video in a rotated fullscreen, and one that left it and waits for its embed row. */
    private var rotated: RotatedSession? = null
    private var returning: RotatedSession? = null
    private var application: Application? = null

    internal val replaceWholeCard get() = settings.getBool(KEY_WHOLE_CARD, false)
    internal val rotateFullscreen get() = settings.getBool(KEY_ROTATE_FULLSCREEN, false)
    internal val youtubeQuality get()= settings.getInt(KEY_YOUTUBE_QUALITY, YoutubeResolver.AUTO_QUALITY)

    init {
        settingsTab = SettingsTab(PlayEmbedsSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        EmbedUrls.fxVideoResolver = FxVideoResolver()
        youtube = YoutubeResolver(File(context.cacheDir, "playembeds-youtube"))
        soundCloud = SoundCloudResolver(File(context.cacheDir, "playembeds"))
        spotify = SpotifyResolver()
        downloader = MediaDownloader(context.applicationContext)
        NativePlayers.init()
        allowLoopbackCleartext()
        patchEmbedRows()
        patchNativeHostedLaunches()
        patchPlayerErrors()
        application = (context.applicationContext as? Application)?.also {
            it.registerActivityLifecycleCallbacks(rotationCallbacks)
        }
    }

    /**
     * Discord's network security config forbids cleartext HTTP, which would
     * stop its player from reading YouTube streams from [StreamServer]. Allow
     * it for 127.0.0.1 only; every other host keeps Discord's policy. If the
     * hook can't be placed, YouTube falls back to downloading first.
     */
    private fun allowLoopbackCleartext() {
        try {
            XposedBridge.disableHiddenApiRestrictions()
        } catch (_: Throwable) {
            // Then the classes below may be unreachable; the fallback handles that.
        }
        val hook = PreHook { frame ->
            if (frame.args.getOrNull(0) == StreamServer.LOOPBACK_HOST) frame.result = true
        }
        for (className in CLEARTEXT_POLICY_CLASSES) {
            try {
                val method = Class.forName(className).getDeclaredMethod("isCleartextTrafficPermitted", String::class.java)
                patcher.patch(method, hook)
            } catch (_: Throwable) {
                // Not on this Android version.
            }
        }
    }

    /** Discord only logs ExoPlayer errors; surface them so a dead stream falls back. */
    private fun patchPlayerErrors() {
        val listener = Class.forName("b.a.p.k")
        val onError = listener.getDeclaredMethod("s", PlaybackException::class.java)
        patcher.patch(
            onError,
            Hook { frame ->
                val callback = errorCallbacks.remove(frame.thisObject) ?: return@Hook
                Utils.mainThread.post { callback() }
            },
        )
    }

    private fun patchEmbedRows() {
        val getAdapter = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
            "access\$getAdapter\$p",
            WidgetChatListAdapterItemEmbed::class.java,
        )
        val onConfigure = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
            "onConfigure",
            Int::class.javaPrimitiveType,
            ChatListEntry::class.java,
        )

        // Restore the old row before Discord configures its next embed. Restoring
        // afterward would overwrite the new embed's image/inline-media visibility.
        patcher.patch(
            onConfigure,
            PreHook { frame ->
                val item = frame.thisObject as? WidgetChatListAdapterItemEmbed ?: return@PreHook
                val entry = frame.args.getOrNull(1) as? EmbedEntry
                embedContainers(item.itemView).forEach { container ->
                    val currentEmbed = entry?.embed
                    val state = players[container]
                    val sameRow = entry != null &&
                        embedRowKeys[container] == (entry.message.id to entry.embedIndex)
                    val samePlayer = sameRow && currentEmbed != null && state != null &&
                        isShowing(state) && state.url == playerIdentity(currentEmbed)
                    val sameLoading = sameRow && loadingIndicators[container] != null
                    if (!samePlayer && !sameLoading) resetEmbedContainer(container)
                }
            },
        )
        patcher.patch(
            onConfigure,
            Hook { frame ->
                val item = frame.thisObject as? WidgetChatListAdapterItemEmbed ?: return@Hook
                val entry = frame.args.getOrNull(1) as? EmbedEntry ?: return@Hook
                configuredEmbeds[item.itemView] = entry.embed
                embedContainers(item.itemView).forEach { container ->
                    val rowKey = entry.message.id to entry.embedIndex
                    embedRowKeys[container] = rowKey
                    // A video back from a rotated fullscreen goes into its rebuilt row once that's laid out.
                    if (returning?.rowKey == rowKey) container.post { returnInto(container) }
                    players[container]?.let { state ->
                        state.previousVisibility.forEach { (view, visibility) ->
                            if (state.forceGone) {
                                view.visibility = View.GONE
                            } else if (visibility == View.VISIBLE) {
                                view.visibility = View.INVISIBLE
                            }
                        }
                        state.root.bringToFront()
                    }
                    loadingIndicators[container]?.bringToFront()
                }
                val adapter = getAdapter.invoke(null, item) as WidgetChatListAdapter
                val handler = adapter.eventHandler
                if (handler.javaClass.name == SEARCH_EVENT_HANDLER) {
                    // Playable embeds play in the search results; taps on anything else jump to the message.
                    embedContainers(item.itemView).forEach { container ->
                        searchEmbedClicks[container] = SearchEmbedClick(handler, entry)
                    }
                }
                val link = EmbedUrls.embedLink(entry.embed)
                if (link == null) {
                    if (entry.embed.m() == null) {
                        val root = WeakReference(item.itemView)
                        val embed = entry.embed
                        val rowKey = entry.message.id to entry.embedIndex
                        EmbedUrls.fxVideoResolver?.resolve(embed.l()) { video ->
                            val view = root.get()
                            if (video != null && view != null && configuredEmbeds[view] === embed &&
                                embedContainers(view).any { embedRowKeys[it] == rowKey }
                            ) {
                                EmbedUrls.embedLink(embed)?.let { attachMediaClickHandlers(view, it) }
                            }
                        }
                    }
                    return@Hook
                }
                attachMediaClickHandlers(item.itemView, link)
            },
        )
    }

    private fun playerIdentity(embed: MessageEmbed): String? =
        EmbedUrls.genericVideoUrl(embed) ?: EmbedUrls.hostedEmbed(embed)?.url

    private fun patchNativeHostedLaunches() {
        val widgetMediaLaunch = WidgetMedia.Companion::class.java.getDeclaredMethod(
            "launch",
            Context::class.java,
            MessageEmbed::class.java,
        )
        patcher.patch(
            widgetMediaLaunch,
            PreHook { frame ->
                val embed = frame.args.getOrNull(1) as? MessageEmbed ?: return@PreHook
                val container = findHostedContainer(embed) ?: return@PreHook
                if (EmbedUrls.genericVideoUrl(embed) != null) {
                    if (playInlineVideo(container, embed)) frame.setResult(null)
                    return@PreHook
                }
                val hosted = EmbedUrls.hostedEmbed(embed) ?: return@PreHook
                if (playHosted(container, hosted)) frame.setResult(null)
            },
        )

        val playButtonListener = Class.forName(
            "com.discord.widgets.chat.list.InlineMediaView\$updateUI\$5",
        )
        val onClick = playButtonListener.getDeclaredMethod("onClick", View::class.java)
        patcher.patch(
            onClick,
            PreHook { frame ->
                val view = frame.args.getOrNull(0) as? View ?: return@PreHook
                val container = findHostedContainer(view)
                val embed = container?.let { hostedEmbeds[it] }
                if (container != null && embed != null && EmbedUrls.genericVideoUrl(embed) != null) {
                    if (playInlineVideo(container, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val url = mediaSourceUrl(frame.thisObject)
                // Discord plays direct media links itself, in chat and in search.
                if (url != null && EmbedUrls.isMediaLink(url)) return@PreHook
                val hosted = url?.let(EmbedUrls::hostedEmbed)
                if ((hosted != null && playHosted(view, hosted)) || handleSearchEmbedClick(container)) {
                    frame.setResult(null)
                }
            },
        )

        // Rich VIDEO previews (including FxTwitter) open EmbedVideo.url via
        // UriHandler directly, so intercept them before that external launch.
        patchEmbedPreviewClick(
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed\$configureEmbedImage\$\$inlined\$apply\$lambda\$1",
        )
        patchEmbedPreviewClick(
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed\$configureInlineEmbed\$\$inlined\$apply\$lambda\$1",
        )
    }

    private fun patchEmbedPreviewClick(className: String) {
        val listener = Class.forName(className)
        val onClick = listener.getDeclaredMethod("onClick", View::class.java)
        patcher.patch(
            onClick,
            PreHook { frame ->
                val embed = messageEmbedField(frame.thisObject) ?: return@PreHook
                val source = frame.args.getOrNull(0) as? View
                    ?: viewGroupField(frame.thisObject)
                    ?: return@PreHook
                val container = findHostedContainer(source)
                if (container != null && EmbedUrls.genericVideoUrl(embed) != null) {
                    if (playInlineVideo(container, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val hosted = EmbedUrls.hostedEmbed(embed)
                if ((hosted != null && playHosted(source, hosted)) || handleSearchEmbedClick(container)) {
                    frame.setResult(null)
                }
            },
        )
    }

    private fun handleSearchEmbedClick(container: ViewGroup?): Boolean {
        val click = container?.let { searchEmbedClicks[it] } ?: return false
        val handler = click.handler ?: return false
        handler.onMessageClicked(click.entry.message, click.entry.isThreadStarterMessage)
        return true
    }

    private fun mediaSourceUrl(listener: Any): String? {
        for (field in listener.javaClass.declaredFields) {
            if (!MediaSource::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                val source = field.get(listener) as? MediaSource ?: return null
                return source.j.toString()
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun messageEmbedField(listener: Any): MessageEmbed? {
        for (field in listener.javaClass.declaredFields) {
            if (!MessageEmbed::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                return field.get(listener) as? MessageEmbed
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun viewGroupField(listener: Any): ViewGroup? {
        for (field in listener.javaClass.declaredFields) {
            if (!ViewGroup::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                return field.get(listener) as? ViewGroup
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun attachMediaClickHandlers(root: View, link: EmbedLink) {
        val listener = View.OnClickListener { view ->
            val currentLink = embedLinkForView(view) ?: return@OnClickListener
            openEmbed(view, currentLink)
        }

        embedContainers(root).forEach { container ->
            embedLinks[container] = link
            if (link is EmbedLink.InlineVideo) {
                hostedEmbeds[container] = link.embed
            }
            if (link is EmbedLink.Hosted) {
                link.embed.sourceEmbed?.let { hostedEmbeds[container] = it }
            }
            if (link is EmbedLink.Hosted && link.embed.provider == HostedProvider.SPOTIFY) {
                addSpotifyPlayButton(container, listener)
            }
        }
    }

    private fun resetEmbedContainer(container: ViewGroup) {
        val replacedCard = players[container]?.replacement?.originalCard
        removeNativePlayer(container)
        hideLoading(container)
        listOfNotNull(container, replacedCard).forEach { view ->
            removeSpotifyPlayButton(view)
            embedLinks.remove(view)
            hostedEmbeds.remove(view)
            embedRowKeys.remove(view)
            searchEmbedClicks.remove(view)
        }
    }

    private fun embedLinkForView(view: View): EmbedLink? {
        var current: View? = view
        while (current != null) {
            if (current is ViewGroup) {
                embedLinks[current]?.let { return it }
            }
            current = current.parent as? View
        }
        if (view is ViewGroup) {
            embedContainers(view).forEach { container ->
                embedLinks[container]?.let { return it }
            }
        }
        return null
    }

    private fun openEmbed(view: View, link: EmbedLink) {
        when (link) {
            is EmbedLink.InlineVideo -> {
                val container = findHostedContainer(view) ?: return
                playInlineVideo(container, link.embed)
            }
            is EmbedLink.Hosted -> {
                if (!playHosted(view, link.embed)) openExternally(view.context, link.embed.url)
            }
        }
    }

    private fun showYoutubeError(url: String) {
        val reason = youtube?.refusal(url)
        Utils.showToast(
            if (reason != null) "YouTube blocked playback: $reason" else "Couldn't play this YouTube video",
            true,
        )
    }

    private fun openExternally(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Utils.showToast("Unable to open the embed", true)
        }
    }

    private fun embedContainers(root: View): List<ViewGroup> {
        val containers = ArrayList<ViewGroup>()
        val cardId = Utils.getResId("chat_list_item_embed_container_card", "id")
        val card = root.findViewById<View>(cardId) as? ViewGroup
        if (card != null) {
            containers.add(card)
        } else if (root is ViewGroup) {
            containers.add(root)
        }
        return containers
    }

    private fun findHostedContainer(view: View): ViewGroup? {
        val inlineMediaId = Utils.getResId("embed_inline_media", "id")
        val imageContainerId = Utils.getResId("embed_image_container", "id")
        val cardId = Utils.getResId("chat_list_item_embed_container_card", "id")
        var current: View? = view
        var inlineMedia: ViewGroup? = null
        var fallback: ViewGroup? = null
        while (current != null) {
            if (current is ViewGroup) {
                if (current.id == cardId) return current
                if (current.id == inlineMediaId || current.id == imageContainerId) inlineMedia = current
                fallback = current
            }
            current = current.parent as? View
        }
        if (view is ViewGroup) {
            val card = view.findViewById<View>(cardId) as? ViewGroup
            if (card != null) return card
            val inline = view.findViewById<View>(inlineMediaId)
            if (inline is ViewGroup && inline.visibility == View.VISIBLE) return inline
        }
        return inlineMedia ?: fallback
    }

    private fun findHostedContainer(embed: MessageEmbed): ViewGroup? {
        return hostedEmbeds.entries
            .firstOrNull { entry -> entry.value === embed }
            ?.key
    }

    private fun resolverFor(provider: HostedProvider): CachedResolver<ResolvedMedia>? = when (provider) {
        HostedProvider.YOUTUBE -> youtube
        HostedProvider.SOUNDCLOUD -> soundCloud
        HostedProvider.SPOTIFY -> spotify
    }

    private fun playHosted(view: View, embed: HostedEmbed): Boolean {
        val container = findHostedContainer(view)
            ?: embed.sourceEmbed?.let(::findHostedContainer)
            ?: return false
        return playHosted(container, embed)
    }

    /**
     * YouTube, SoundCloud and Spotify links are webpages, not media files.
     * Resolve a progressive stream in the background, then play it inline
     * with Discord's ExoPlayer. Anything that can't be resolved opens externally.
     */
    private fun playHosted(container: ViewGroup, embed: HostedEmbed): Boolean {
        players[container]?.let { state ->
            if (state.url == embed.url && isShowing(state)) {
                state.root.bringToFront()
                return true
            }
            // Removing a whole-card player puts the original card back in place of the wrapper.
            val replacedCard = state.replacement?.originalCard
            removeNativePlayer(container)
            if (replacedCard != null) return playHosted(replacedCard, embed)
        }
        val resolver = resolverFor(embed.provider) ?: return false
        if (!resolver.handles(embed.url)) {
            openExternally(container.context, embed.url)
            return true
        }
        if (loadingIndicators[container] != null) return true

        val rowKey = embedRowKeys[container]
        val containerRef = WeakReference(container)
        val done: (ResolvedMedia?) -> Unit = done@{ media ->
            val target = containerRef.get() ?: return@done
            hideLoading(target)
            if (!target.isAttachedToWindow || embedRowKeys[target] != rowKey) return@done
            if (media == null && embed.provider == HostedProvider.YOUTUBE) {
                // Opening the app hid why it failed (usually YouTube's bot check); say so instead.
                showYoutubeError(embed.url)
                return@done
            }
            val title = embed.title ?: embed.sourceEmbed?.j()
            if (media == null || !attachPlayer(target, embed.url, media, embed.provider, title)) {
                openExternally(target.context, embed.url)
            }
        }
        val youtubeResolver = youtube?.takeIf { embed.provider == HostedProvider.YOUTUBE }
        if (youtubeResolver == null) {
            showLoading(container)
            resolver.resolve(embed.url, done)
            return true
        }
        val quality = youtubeQualityChoices[embed.url] ?: youtubeQuality
        if (youtubeResolver.cached(embed.url, quality) == null) {
            showYoutubeLoading(container) { youtubeResolver.progress(embed.url, quality) }
        } else {
            showLoading(container)
        }
        youtubeResolver.resolve(embed.url, quality, done)
        return true
    }

    /** Replace a direct-video preview with a native player for Discord's video URL. */
    private fun playInlineVideo(container: ViewGroup, embed: MessageEmbed): Boolean {
        val videoUrl = EmbedUrls.genericVideoUrl(embed) ?: return false
        players[container]?.let { state ->
            if (state.url == videoUrl && isShowing(state)) {
                state.root.bringToFront()
                return true
            }
            val replacedCard = state.replacement?.originalCard
            removeNativePlayer(container)
            if (replacedCard != null) return playInlineVideo(replacedCard, embed)
        }
        val video = embed.m()
        val thumbnail = embed.h()
        val width = video?.d()?.takeIf { it > 0 } ?: thumbnail?.d()?.takeIf { it > 0 } ?: 16
        val height = video?.a()?.takeIf { it > 0 } ?: thumbnail?.a()?.takeIf { it > 0 } ?: 9
        val media = ResolvedMedia(videoUrl, width, height, false)
        val attached = attachPlayer(container, videoUrl, media, null, embed.j())
        if (attached) {
            // A whole-card player lives in a wrapper that took over the card's place.
            val wrapper = (container.parent as? ViewGroup)
                ?.takeIf { players[it]?.replacement?.originalCard === container }
            hostedEmbeds[wrapper ?: container] = embed
        }
        return attached
    }

    private fun attachPlayer(
        container: ViewGroup,
        identity: String,
        media: ResolvedMedia,
        provider: HostedProvider?,
        title: String?,
        /** A player that's already playing [media] (back from a rotated fullscreen), shown without reloading. */
        existing: AppMediaPlayer? = null,
    ): Boolean {
        removeSpotifyPlayButton(container)
        val player = existing ?: NativePlayers.create(container.context) ?: return false
        val reuse = existing != null
        val wholeCard = replaceWholeCard && container.id == id("chat_list_item_embed_container_card")
        val state = when {
            media.audioOnly -> attachAudioPlayer(container, player, identity, media, reuse)
            wholeCard ->
                attachWholeCardVideo(container, player, identity, media, fitVideo = true, reuse = reuse)
                    ?: attachPreviewVideo(container, player, identity, media, reuse)
            // Otherwise the whole card is replaced only when there's no large preview to play in.
            provider == HostedProvider.YOUTUBE ->
                attachPreviewVideo(container, player, identity, media, reuse)
                    ?: attachWholeCardVideo(container, player, identity, media, fitVideo = false, reuse = reuse)
            else -> attachPreviewVideo(container, player, identity, media, reuse)
        }
        if (state == null) {
            NativePlayers.release(player)
            return false
        }
        state.media = media
        state.provider = provider
        state.title = title
        val quality = if (provider == HostedProvider.YOUTUBE && !media.audioOnly) {
            addQualityButton(state, identity, media)
        } else {
            null
        }
        // After a quality switch the download saves the quality that's playing.
        addDownloadButton((state.root as? ViewGroup)?.getChildAt(0), title, media.audioOnly) {
            quality?.media?.uri ?: media.uri
        }
        val playerContainer = state.replacement?.wrapper ?: container
        // `players` holds its values weakly; the view's tag is the strong reference that
        // keeps the state alive exactly as long as the player view exists. Without it the
        // state can be collected mid-playback, and a recycled row then keeps showing the
        // old video because resetEmbedContainer can no longer find it.
        state.root.tag = state
        players[playerContainer] = state
        // Weak references only: the map value must not pin its own key (the player's listener).
        val containerRef = WeakReference(playerContainer)
        val playerRef = WeakReference(player)
        errorCallbacks[player.g] = {
            val target = containerRef.get()
            val current = playerRef.get()
            if (target != null && current != null && players[target]?.player === current) {
                removeNativePlayer(target)
                if (provider == HostedProvider.YOUTUBE) {
                    showYoutubeError(identity)
                } else {
                    Utils.showToast("Couldn't play this embed", true)
                    if (provider != null) openExternally(target.context, identity)
                }
            }
        }
        // The chat list can keep a row attached after it scrolls out of view, so
        // pause as soon as the player is (almost) off screen. It isn't resumed
        // automatically; scrolling back shows it paused where it stopped.
        val rootRef = WeakReference(state.root)
        var wasOnScreen = true
        val scrollCheck = ViewTreeObserver.OnScrollChangedListener {
            val root = rootRef.get() ?: return@OnScrollChangedListener
            if (fullscreenPlayers.containsKey(root)) return@OnScrollChangedListener
            val onScreen = isOnScreen(root)
            if (wasOnScreen && !onScreen) playerRef.get()?.let(NativePlayers::pause)
            wasOnScreen = onScreen
        }
        if (state.root.isAttachedToWindow) state.root.viewTreeObserver.addOnScrollChangedListener(scrollCheck)
        // Like Discord's own inline media, stop once the row is detached
        // (or the chat closes). The delay ignores brief detaches during relayout.
        state.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnScrollChangedListener(scrollCheck)
            }

            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.removeOnScrollChangedListener(scrollCheck)
                val viewRef = WeakReference(view)
                Utils.mainThread.postDelayed({
                    val root = viewRef.get() ?: return@postDelayed
                    val target = containerRef.get() ?: return@postDelayed
                    if (!root.isAttachedToWindow && players[target]?.root === root) removeNativePlayer(target)
                }, DETACH_RELEASE_DELAY_MS)
            }
        })
        playerContainer.requestLayout()
        return true
    }

    /**
     * Replaces the whole embed card with the player. With [fitVideo] the card is
     * hidden and the player takes the video's aspect ratio at the card's width;
     * otherwise it covers the card at its current size (YouTube cards without a
     * large preview, whose thumbnail is too small to play in).
     */
    private fun attachWholeCardVideo(
        container: ViewGroup,
        player: AppMediaPlayer,
        identity: String,
        media: ResolvedMedia,
        fitVideo: Boolean,
        reuse: Boolean = false,
    ): NativePlayerState? {
        val replacement = wrapEmbedForHostedPlayer(container) ?: return null
        val context = container.context
        val playerView = NativePlayers.videoView(context)
        val root = TouchGuardLayout(context).apply {
            addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addFullscreenButton(playerView, root, landscape = media.width >= media.height)
        val previousVisibility = ArrayList<Pair<View, Int>>()
        val params = if (fitVideo) {
            val size = fittedVideoSize(context, media, replacement.originalWidth)
            previousVisibility.add(container to container.visibility)
            container.visibility = View.GONE
            replacement.wrapper.minimumWidth = 0
            replacement.wrapper.minimumHeight = 0
            FrameLayout.LayoutParams(size.first, size.second)
        } else {
            FrameLayout.LayoutParams(MATCH, MATCH)
        }
        replacement.wrapper.addView(root, params)
        root.bringToFront()
        startPlayback(player, media, playerView, null, reuse)
        return NativePlayerState(
            player,
            root,
            replacement.wrapper,
            previousVisibility,
            forceGone = fitVideo,
            url = identity,
            extraViews = emptyList(),
            previousMinimumWidth = replacement.wrapper.minimumWidth,
            replacement = replacement,
        )
    }

    /** Start [media] in [player], or with [reuse] just show the player's current playback in the views. */
    private fun startPlayback(
        player: AppMediaPlayer,
        media: ResolvedMedia,
        view: PlayerView,
        controls: PlayerControlView?,
        reuse: Boolean,
    ) {
        if (reuse) NativePlayers.bind(player, view, controls) else NativePlayers.play(player, media.uri, view, controls)
    }

    /** The video's aspect ratio at up to [cardWidth] wide, capped at Discord's embed image height. */
    private fun fittedVideoSize(context: Context, media: ResolvedMedia, cardWidth: Int): Pair<Int, Int> {
        val embedResources = EmbedResourceUtils.INSTANCE
        val videoWidth = media.width.coerceAtLeast(1)
        val videoHeight = media.height.coerceAtLeast(1)
        val maxWidth = minOf(cardWidth, embedResources.computeMaximumImageWidthPx(context)).coerceAtLeast(1)
        val maxHeight = embedResources.getMAX_IMAGE_VIEW_HEIGHT_PX()
        var width = maxWidth
        var height = (width.toLong() * videoHeight / videoWidth).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height.toLong() * videoWidth / videoHeight).toInt()
        }
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    }

    /** Swap the image/inline-media preview for a video surface of the same size. */
    private fun attachPreviewVideo(
        container: ViewGroup,
        player: AppMediaPlayer,
        identity: String,
        media: ResolvedMedia,
        reuse: Boolean = false,
    ): NativePlayerState? {
        val imageContainer = container.findViewById<View>(id("embed_image_container")) as? ViewGroup
        val inlineMedia = container.findViewById<View>(id("embed_inline_media")) as? ViewGroup
        val content = container.findViewById<View>(id("chat_list_item_embed_content"))
        val parent = when {
            content?.visibility == View.VISIBLE && imageContainer?.visibility == View.VISIBLE -> imageContainer
            inlineMedia?.visibility == View.VISIBLE -> inlineMedia
            else -> return null
        }
        val previousVisibility = ArrayList<Pair<View, Int>>()
        if (parent === imageContainer) {
            listOfNotNull(
                parent.findViewById<View>(id("chat_list_item_embed_image")),
                parent.findViewById<View>(id("chat_list_item_embed_image_icons")),
            ).forEach { previousVisibility.add(it to it.visibility) }
        } else {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                previousVisibility.add(child to child.visibility)
            }
        }

        // The image card wraps its children. A MATCH_PARENT player cannot
        // provide its own size once the native preview is removed.
        val preview = parent.findViewById<View>(
            id(if (parent === imageContainer) "chat_list_item_embed_image" else "inline_media_image_preview"),
        )
        val embedResources = EmbedResourceUtils.INSTANCE
        val fallbackSize = embedResources.calculateScaledSize(
            media.width.coerceAtLeast(1),
            media.height.coerceAtLeast(1),
            embedResources.computeMaximumImageWidthPx(parent.context),
            embedResources.getMAX_IMAGE_VIEW_HEIGHT_PX(),
            parent.resources,
            0,
        )
        val width = (parent.width - parent.paddingLeft - parent.paddingRight).takeIf { it > 0 }
            ?: preview?.layoutParams?.width?.takeIf { it > 0 }
            ?: fallbackSize.first
        val height = (parent.height - parent.paddingTop - parent.paddingBottom).takeIf { it > 0 }
            ?: preview?.layoutParams?.height?.takeIf { it > 0 }
            ?: fallbackSize.second

        val playerView = NativePlayers.videoView(parent.context)
        val root = TouchGuardLayout(parent.context).apply {
            addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addFullscreenButton(playerView, root, landscape = media.width >= media.height)
        previousVisibility.forEach { (child, _) -> child.visibility = View.GONE }
        parent.addView(root, FrameLayout.LayoutParams(width, height))
        root.bringToFront()
        startPlayback(player, media, playerView, null, reuse)
        return NativePlayerState(
            player,
            root,
            parent,
            previousVisibility,
            forceGone = true,
            url = identity,
            extraViews = emptyList(),
            previousMinimumWidth = parent.minimumWidth,
        )
    }

    /**
     * Audio keeps the embed's artwork visible. The control bar sits over the
     * bottom of a large preview, or below the text for compact thumbnail cards.
     */
    private fun attachAudioPlayer(
        container: ViewGroup,
        player: AppMediaPlayer,
        identity: String,
        media: ResolvedMedia,
        reuse: Boolean = false,
    ): NativePlayerState? {
        val context = container.context
        val preview = previewView(container)
        val previewParent = preview?.parent as? ViewGroup
        val controlBar = NativePlayers.controlBar(context)
        val root = TouchGuardLayout(context).apply {
            addView(controlBar, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        // AppMediaPlayer needs a PlayerView even for audio; it never shows.
        val hiddenView = NativePlayers.videoView(context)

        if (preview != null && previewParent is FrameLayout) {
            val previousVisibility = ArrayList<Pair<View, Int>>()
            for (index in 0 until previewParent.childCount) {
                val child = previewParent.getChildAt(index)
                if (child !== preview && child.visibility == View.VISIBLE) {
                    previousVisibility.add(child to child.visibility)
                    child.visibility = View.INVISIBLE
                }
            }
            previewParent.addView(root, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            root.bringToFront()
            startPlayback(player, media, hiddenView, controlBar, reuse)
            return NativePlayerState(
                player,
                root,
                previewParent,
                previousVisibility,
                forceGone = false,
                url = identity,
                extraViews = emptyList(),
                previousMinimumWidth = previewParent.minimumWidth,
            )
        }

        // Compact card: small thumbnail (or none) beside the text.
        val content = container.findViewById<View>(id("chat_list_item_embed_content"))
            ?.takeIf { it.visibility == View.VISIBLE }
            ?: return null
        val cardLayout = content.parent as? ConstraintLayout ?: return null
        val previousMinimumWidth = cardLayout.minimumWidth
        val maxWidth = EmbedResourceUtils.INSTANCE.computeMaximumImageWidthPx(context)
        cardLayout.minimumWidth = maxOf(previousMinimumWidth, maxWidth)
        val dividerId = id("chat_list_item_embed_divider")
        val referenced = listOfNotNull(preview?.takeIf { it.parent === cardLayout }?.id, content.id)
            .filter { it != View.NO_ID }
        val barrier = Barrier(context).apply {
            id = View.generateViewId()
            type = Barrier.BOTTOM
            referencedIds = referenced.toIntArray()
        }
        cardLayout.addView(barrier, ConstraintLayout.LayoutParams(0, 0))
        val barWidth = maxOf(dp(context, 1), maxWidth - (container.findViewById<View>(dividerId)?.width ?: 0))
        val params = ConstraintLayout.LayoutParams(barWidth, WRAP).apply {
            startToEnd = dividerId
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            horizontalBias = 0f
            topToBottom = barrier.id
            topMargin = dp(context, 4)
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            bottomMargin = dp(context, 8)
        }
        cardLayout.addView(root, params)
        root.bringToFront()
        startPlayback(player, media, hiddenView, controlBar, reuse)
        return NativePlayerState(
            player,
            root,
            cardLayout,
            emptyList(),
            forceGone = false,
            url = identity,
            extraViews = listOf(barrier),
            previousMinimumWidth = previousMinimumWidth,
        )
    }

    /** The embed's visible preview image: large image, inline media, or small thumbnail. */
    private fun previewView(container: ViewGroup): View? {
        val imageContainer = container.findViewById<View>(id("embed_image_container"))
        val inlineMedia = container.findViewById<View>(id("embed_inline_media"))
        val content = container.findViewById<View>(id("chat_list_item_embed_content"))
        return when {
            content?.visibility == View.VISIBLE && imageContainer?.visibility == View.VISIBLE ->
                imageContainer.findViewById(id("chat_list_item_embed_image"))
            inlineMedia?.visibility == View.VISIBLE ->
                inlineMedia.findViewById(id("inline_media_image_preview"))
            else -> container.findViewById<View>(id("chat_list_item_embed_image_thumbnail"))
                ?.takeIf { it.visibility == View.VISIBLE }
        }
    }

    private fun wrapEmbedForHostedPlayer(container: ViewGroup): HostedEmbedReplacement? {
        val originalParent = container.parent as? ViewGroup ?: return null
        val originalIndex = originalParent.indexOfChild(container)
        val originalLayoutParams = container.layoutParams ?: return null
        val originalId = container.id
        val width = container.width
        val height = container.height
        if (originalIndex < 0 || originalId == View.NO_ID || width <= 0 || height <= 0) return null

        val wrapper = FrameLayout(container.context).apply {
            id = originalId
            minimumWidth = maxOf(container.minimumWidth, width)
            minimumHeight = maxOf(container.minimumHeight, height)
            elevation = container.elevation
            clipChildren = container.clipChildren
            clipToPadding = container.clipToPadding
            clipToOutline = container.clipToOutline
            container.background?.constantState
                ?.newDrawable(container.resources)
                ?.mutate()
                ?.let { background = it }
        }

        originalParent.removeViewAt(originalIndex)
        container.id = View.generateViewId()
        originalParent.addView(wrapper, originalIndex, originalLayoutParams)
        wrapper.addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        moveEmbedViewState(container, wrapper)
        return HostedEmbedReplacement(
            wrapper,
            originalParent,
            originalIndex,
            originalLayoutParams,
            container,
            originalId,
            width,
            height,
        )
    }

    private fun moveEmbedViewState(from: ViewGroup, to: ViewGroup) {
        embedLinks.remove(from)?.let { embedLinks[to] = it }
        hostedEmbeds.remove(from)?.let { hostedEmbeds[to] = it }
        embedRowKeys.remove(from)?.let { embedRowKeys[to] = it }
        searchEmbedClicks.remove(from)?.let { searchEmbedClicks[to] = it }
        spotifyPlayButtons.remove(from)?.let { spotifyPlayButtons[to] = it }
        loadingIndicators.remove(from)?.let { loadingIndicators[to] = it }
    }

    private fun removeNativePlayer(container: ViewGroup) {
        val state = detachNativePlayer(container) ?: return
        errorCallbacks.remove(state.player.g)
        NativePlayers.release(state.player)
    }

    /** Takes the player out of [container] and restores the embed, without releasing the player. */
    private fun detachNativePlayer(container: ViewGroup): NativePlayerState? {
        val state = players.remove(container) ?: return null
        state.root.tag = null
        exitFullscreen(state.root)
        (state.root.parent as? ViewGroup)?.removeView(state.root)
        state.extraViews.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        state.parent.minimumWidth = state.previousMinimumWidth
        state.previousVisibility.forEach { (view, visibility) ->
            view.visibility = visibility
        }
        val replacement = state.replacement
        if (replacement != null) {
            (replacement.originalCard.parent as? ViewGroup)?.removeView(replacement.originalCard)
            replacement.wrapper.id = View.generateViewId()
            replacement.originalCard.id = replacement.originalId
            (replacement.wrapper.parent as? ViewGroup)?.removeView(replacement.wrapper)
            val insertionIndex = replacement.originalIndex.coerceIn(0, replacement.originalParent.childCount)
            replacement.originalParent.addView(
                replacement.originalCard,
                insertionIndex,
                replacement.originalLayoutParams,
            )
            moveEmbedViewState(replacement.wrapper, replacement.originalCard)
            replacement.originalParent.requestLayout()
        } else {
            container.requestLayout()
        }
        return state
    }

    /** A spinner styled like Discord's inline_media_loading_indicator, over the preview. */
    private fun showLoading(container: ViewGroup) {
        val context = container.context
        val anchor = previewView(container)
        val parent = anchor?.parent as? ViewGroup ?: container
        val sizeId = Utils.getResId("embed_play_button_width", "dimen")
        val size = if (sizeId > 0) context.resources.getDimensionPixelSize(sizeId) else dp(context, 48)
        val indicator = ProgressBar(context).apply {
            isIndeterminate = true
            val backgroundId = Utils.getResId("drawable_circle_primary_900", "drawable")
            if (backgroundId > 0) setBackgroundResource(backgroundId)
            val padding = dp(context, 8)
            setPadding(padding, padding, padding, padding)
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0.6f
            elevation = dp(context, 2).toFloat()
        }
        parent.addView(indicator, centeredParams(parent, anchor, size, size))
        indicator.bringToFront()
        loadingIndicators[container] = indicator
    }

    /** Layout params that center a view of [width]x[height] over [anchor] (or [parent] without one). */
    private fun centeredParams(parent: ViewGroup, anchor: View?, width: Int, height: Int): ViewGroup.LayoutParams =
        when {
            parent is ConstraintLayout -> ConstraintLayout.LayoutParams(width, height).apply {
                val target = anchor?.id?.takeIf { it != View.NO_ID } ?: ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = target
                endToEnd = target
                topToTop = target
                bottomToBottom = target
            }
            parent is FrameLayout -> FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            else -> ViewGroup.LayoutParams(width, height)
        }

    /**
     * A popup over the YouTube preview while the video is fetched, built from
     * Discord's upload progress row (view_upload_progress): the current step,
     * a progress bar and, once it can be estimated, the time left.
     */
    private fun showYoutubeLoading(container: ViewGroup, progress: () -> LoadProgress?) {
        val context = container.context
        val card = youtubeLoadingCard(context)
        if (card == null) {
            showLoading(container)
            return
        }

        // Center over a large preview; a small thumbnail can't hold it, so use the whole card then.
        val preview = previewView(container)
        val maxWidth = dp(context, 260)
        val margin = dp(context, 16)
        val largePreview = preview != null && preview.width - margin >= dp(context, 160)
        val anchor = if (largePreview) preview else null
        val parent = anchor?.parent as? ViewGroup ?: container
        val width = if (anchor != null) {
            minOf(maxWidth, anchor.width - margin)
        } else {
            minOf(maxWidth, (container.width - margin).coerceAtLeast(dp(context, 160)))
        }
        parent.addView(card, centeredParams(parent, anchor, width, WRAP))
        card.bringToFront()
        loadingIndicators[container] = card
        startLoadingTicker(card, progress)
    }

    /** The popup's content: Discord's upload progress row on a floating card, or null without it. */
    private fun youtubeLoadingCard(context: Context): FrameLayout? {
        val layoutId = Utils.getResId("view_upload_progress", "layout")
        if (layoutId <= 0) return null
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat()
                setColor(themedColor(context, "colorBackgroundFloating", Color.DKGRAY))
            }
            elevation = dp(context, 4).toFloat()
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), 0)
            // Taps on the popup shouldn't reach the preview underneath.
            isClickable = true
        }
        val content = try {
            LayoutInflater.from(context).inflate(layoutId, card, false)
        } catch (_: Throwable) {
            return null
        }
        content.findViewById<View>(id("progress_file_image"))?.visibility = View.GONE
        content.findViewById<TextView>(id("progress_text"))
            ?.setTextColor(themedColor(context, "colorHeaderPrimary", Color.WHITE))
        content.findViewById<TextView>(id("progress_subtext"))
            ?.setTextColor(themedColor(context, "colorTextMuted", Color.LTGRAY))
        card.addView(content)
        return card
    }

    /** Refreshes [card] from [progress] until it's removed from its parent. */
    private fun startLoadingTicker(card: View, progress: () -> LoadProgress?) {
        val resolver = youtube ?: return
        val cardRef = WeakReference(card)
        val tick = object : Runnable {
            override fun run() {
                val view = cardRef.get() ?: return
                if (view.parent == null) return
                updateYoutubeLoading(view, progress(), resolver)
                Utils.mainThread.postDelayed(this, LOADING_TICK_MS)
            }
        }
        tick.run()
    }

    /**
     * A quality label at the end of the YouTube player's control bar, styled
     * like its duration. Tapping it lists the video's qualities; picking one
     * loads it and continues from the same position.
     */
    private fun addQualityButton(state: NativePlayerState, url: String, media: ResolvedMedia): QualityControl? {
        val playerView = (state.root as? ViewGroup)?.getChildAt(0) as? PlayerView ?: return null
        val duration = playerView.findViewById<TextView>(id("exo_duration")) ?: return null
        val bar = duration.parent as? LinearLayout ?: return null
        val context = playerView.context
        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, duration.textSize)
            typeface = duration.typeface
            setTextColor(duration.textColors)
            includeFontPadding = duration.includeFontPadding
            gravity = Gravity.CENTER
            val padding = dp(context, 4)
            setPadding(padding, padding, padding, padding)
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
        }
        val control = QualityControl(state, playerView, label, url, media)
        control.updateLabel()
        label.setOnClickListener { showQualityPicker(control) }
        bar.addView(
            label,
            bar.indexOfChild(duration) + 1,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(context, 12) },
        )
        return control
    }

    /**
     * A download button in the player's control bar, just before the fullscreen
     * button, styled like it. It saves the stream that's playing to Downloads.
     */
    private fun addDownloadButton(controls: View?, title: String?, audioOnly: Boolean, uri: () -> String) {
        if (controls == null || !MediaDownloader.canDownload(uri())) return
        addBarButton(
            controls,
            "ic_file_download_white_24dp",
            if (audioOnly) "Download audio" else "Download video",
        ) {
            downloader?.download(uri(), title, audioOnly, activityFrom(controls.context) as? AppActivity)
        }
    }

    /**
     * An icon button in [controls]' control bar (the row holding the duration),
     * styled like ExoPlayer's own: before the fullscreen button, or last.
     */
    private fun addBarButton(controls: View, iconName: String, description: String, onClick: () -> Unit): ImageButton? {
        val duration = controls.findViewById<View>(id("exo_duration")) ?: return null
        val bar = duration.parent as? LinearLayout ?: return null
        val context = controls.context
        val styleId = Utils.getResId("ExoMediaButton", "style")
        val button = if (styleId > 0) ImageButton(context, null, 0, styleId) else ImageButton(context)
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        button.setPadding(0, 0, 0, 0)
        val iconId = Utils.getResId(iconName, "drawable")
        if (iconId > 0) button.setImageResource(iconId)
        button.contentDescription = description
        button.setOnClickListener { onClick() }
        val size = dp(context, 24)
        val fullscreen = bar.findViewWithTag<View>(FULLSCREEN_BUTTON_TAG)
        val index = if (fullscreen != null) bar.indexOfChild(fullscreen) else bar.childCount
        bar.addView(button, index, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(context, 16) })
        return button
    }

    private class QualityControl(
        val state: NativePlayerState,
        val playerView: PlayerView,
        val label: TextView,
        val url: String,
        var media: ResolvedMedia,
    ) {
        /** The quality being loaded, so only the last pick is applied. */
        var pending: Int? = null
        var loadingCard: View? = null

        fun updateLabel() {
            label.text = if (media.quality > 0) "${media.quality}p" else "Quality"
            label.contentDescription = "Video quality: ${label.text}"
        }

        fun isActive(): Boolean = state.root.tag === state
    }

    private fun showQualityPicker(control: QualityControl) {
        val qualities = (control.media.qualities + control.media.quality)
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        if (qualities.size < 2) {
            Utils.showToast("No other qualities for this video")
            return
        }
        val current = control.pending ?: control.media.quality
        SelectDialog().apply {
            title = "Video quality"
            items = qualities.map { if (it == current) "✓ ${it}p" else "${it}p" }.toTypedArray()
            onResultListener = { which -> qualities.getOrNull(which)?.let { switchQuality(control, it) } }
        }.show(fragmentManagerFor(control.label.context), "PlayEmbedsQuality")
    }

    private fun switchQuality(control: QualityControl, quality: Int) {
        val resolver = youtube ?: return
        if (!control.isActive() || quality == control.pending) return
        if (quality == control.media.quality) {
            // Back to what's playing: drop the pending switch.
            control.pending = null
            hideQualityLoading(control)
            return
        }
        control.pending = quality
        hideQualityLoading(control)

        val cached = resolver.cached(control.url, quality)
        if (cached != null) {
            applyQuality(control, cached)
            return
        }
        val root = control.state.root as? FrameLayout
        val card = youtubeLoadingCard(root?.context ?: control.label.context)
        if (root != null && card != null) {
            val context = root.context
            val width = minOf(dp(context, 260), (root.width - dp(context, 16)).coerceAtLeast(dp(context, 160)))
            root.addView(card, FrameLayout.LayoutParams(width, WRAP, Gravity.CENTER))
            card.bringToFront()
            control.loadingCard = card
            startLoadingTicker(card) { resolver.progress(control.url, quality) }
        } else {
            Utils.showToast("Loading ${quality}p…")
        }
        resolver.resolve(control.url, quality) { media ->
            if (control.pending != quality) return@resolve
            hideQualityLoading(control)
            control.pending = null
            if (!control.isActive()) return@resolve
            if (media == null) {
                Utils.showToast("Couldn't load ${quality}p", true)
                return@resolve
            }
            applyQuality(control, media)
        }
    }

    private fun applyQuality(control: QualityControl, media: ResolvedMedia) {
        control.pending = null
        val player = control.state.player
        val position = NativePlayers.position(player)
        val playing = NativePlayers.isPlaying(player)
        NativePlayers.play(player, media.uri, control.playerView, null, position, playing)
        control.media = media
        control.state.media = media
        control.updateLabel()
        youtubeQualityChoices[control.url] = media.quality
    }

    private fun hideQualityLoading(control: QualityControl) {
        val card = control.loadingCard ?: return
        control.loadingCard = null
        (card.parent as? ViewGroup)?.removeView(card)
    }

    private fun updateYoutubeLoading(card: View, tracker: LoadProgress?, resolver: YoutubeResolver) {
        val stage = tracker?.stage
        val total = tracker?.totalBytes ?: 0L
        val percent = if (tracker != null && stage == LoadStage.DOWNLOADING && total > 0) {
            (tracker.downloadedBytes.get() * 100L / total).toInt().coerceIn(0, 100)
        } else {
            null
        }
        card.findViewById<TextView>(id("progress_text"))?.text = when (stage) {
            null -> "Loading video"
            LoadStage.RESOLVING -> "Finding video"
            LoadStage.DOWNLOADING -> if (percent != null) "Downloading video · $percent%" else "Downloading video"
            LoadStage.MUXING -> "Preparing video"
        }
        val remaining = tracker?.let(resolver::estimateMs)
        card.findViewById<TextView>(id("progress_subtext"))?.text =
            if (remaining != null) formatRemaining(remaining) else ""
        card.findViewById<ProgressBar>(id("progress_bar"))?.let { bar ->
            if (percent != null) {
                if (bar.isIndeterminate) bar.isIndeterminate = false
                bar.progress = percent
            } else if (!bar.isIndeterminate) {
                bar.isIndeterminate = true
            }
        }
    }

    private fun formatRemaining(ms: Long): String {
        val seconds = ((ms + 999L) / 1_000L).coerceAtLeast(1L)
        return if (seconds < 60L) "~${seconds}s left" else "~${(seconds + 30L) / 60L} min left"
    }

    private fun themedColor(context: Context, attrName: String, fallback: Int): Int {
        val attrId = Utils.getResId(attrName, "attr")
        if (attrId <= 0) return fallback
        return try {
            ColorCompat.getThemedColor(context, attrId)
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun hideLoading(container: ViewGroup) {
        val indicator = loadingIndicators.remove(container) ?: return
        (indicator.parent as? ViewGroup)?.removeView(indicator)
    }

    /** At least [MIN_VISIBLE_FRACTION] of [view]'s height is inside the chat list's visible area. */
    private fun isOnScreen(view: View): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || view.height <= 0) return false
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return false
        return visible.height() >= view.height * MIN_VISIBLE_FRACTION
    }

    /** The player is in its embed, or temporarily moved into the fullscreen overlay. */
    private fun isShowing(state: NativePlayerState): Boolean =
        state.root.parent === state.parent || fullscreenPlayers.containsKey(state.root)

    /** Adds a fullscreen toggle to the end of Discord's control bar (after the duration). */
    private fun addFullscreenButton(playerView: View, root: View, landscape: Boolean) {
        val duration = playerView.findViewById<View>(id("exo_duration")) ?: return
        val bar = duration.parent as? LinearLayout ?: return
        val context = playerView.context
        val styleId = Utils.getResId("ExoMediaButton", "style")
        val button = if (styleId > 0) ImageButton(context, null, 0, styleId) else ImageButton(context)
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        button.setPadding(0, 0, 0, 0)
        button.tag = FULLSCREEN_BUTTON_TAG
        setFullscreenIcon(button, fullscreen = false)
        button.setOnClickListener {
            if (fullscreenPlayers.containsKey(root)) exitFullscreen(root) else enterFullscreen(root, button, landscape)
        }
        val size = dp(context, 24)
        bar.addView(button, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(context, 16) })
    }

    private fun setFullscreenIcon(button: ImageButton, fullscreen: Boolean) {
        val iconId = Utils.getResId(
            if (fullscreen) "exo_icon_fullscreen_exit" else "exo_icon_fullscreen_enter",
            "drawable",
        )
        if (iconId > 0) button.setImageResource(iconId)
        button.contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen"
    }

    /**
     * Moves the playing view (not a new player) into a black overlay over the
     * whole window, so playback continues without a restart. Discord's activity
     * doesn't handle rotation itself and would be recreated, destroying the
     * player, so the current orientation is locked while fullscreen. With the
     * rotate setting, a [landscape] video on a portrait screen goes into a
     * [RotatedSession] instead, which lets the screen really rotate.
     */
    @Suppress("DEPRECATION")
    private fun enterFullscreen(root: View, button: ImageButton, landscape: Boolean) {
        if (fullscreenPlayers.containsKey(root)) return
        val activity = activityFrom(root.context) ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val portraitScreen = decor.width > 0 && decor.width < decor.height
        if (landscape && rotateFullscreen && portraitScreen && enterRotatedFullscreen(root, activity)) return
        val parent = root.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(root)
        val params = root.layoutParams ?: return

        val overlay = FullscreenOverlay(activity) { exitFullscreen(root) }
        val state = FullscreenState(
            activity,
            overlay,
            button,
            parent,
            index,
            params,
            decor.systemUiVisibility,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0,
            activity.requestedOrientation,
        )
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(decor.windowToken, 0)
        parent.removeView(root)
        overlay.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        decor.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        fullscreenPlayers[root] = state

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        hideSystemBars(activity, decor, state.previousSystemUiVisibility)
        overlay.requestFocus()
        setFullscreenIcon(button, fullscreen = true)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(activity: Activity, decor: View, previousSystemUiVisibility: Int) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        decor.systemUiVisibility = previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Suppress("DEPRECATION")
    private fun restoreSystemBars(activity: Activity, previousSystemUiVisibility: Int, wasFullscreen: Boolean) {
        activity.window.decorView.systemUiVisibility = previousSystemUiVisibility
        if (!wasFullscreen) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    /**
     * Fullscreen with the screen really turned to landscape. The player leaves its
     * embed (which is restored) and keeps running on its own in [rotated]; it's
     * shown in an overlay now and again in every activity Discord rebuilds while
     * the screen rotates (see [rotationCallbacks]). False if it can't be done.
     */
    private fun enterRotatedFullscreen(root: View, activity: Activity): Boolean {
        val container = players.keys.firstOrNull { players[it]?.root === root } ?: return false
        val state = players[container] ?: return false
        val media = state.media ?: return false
        val oldView = (root as? ViewGroup)?.getChildAt(0) as? PlayerView ?: return false
        endRotated()
        val session = RotatedSession(
            state.player,
            state.url,
            state.provider,
            state.title,
            media,
            embedRowKeys[container],
            activity.requestedOrientation,
        )
        // Detaching (not removing) keeps the player playing; the embed shows its preview again.
        detachNativePlayer(container)
        val sessionRef = WeakReference(session)
        errorCallbacks[state.player.g] = {
            val current = sessionRef.get()
            if (current != null && rotated === current) {
                endRotated()
                if (current.provider == HostedProvider.YOUTUBE) {
                    showYoutubeError(current.identity)
                } else {
                    Utils.showToast("Couldn't play this embed", true)
                }
            }
        }
        rotated = session
        session.playerView = oldView
        showRotated(session, activity)
        activity.requestedOrientation = RotatedSession.LANDSCAPE
        return true
    }

    /** Shows [session]'s player in a fullscreen overlay in [activity]. */
    @Suppress("DEPRECATION")
    private fun showRotated(session: RotatedSession, activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val overlay = FullscreenOverlay(activity) { exitRotatedFullscreen() }
        val view = NativePlayers.videoView(activity)
        overlay.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        session.playerView?.setPlayer(null)
        NativePlayers.bind(session.player, view, null)
        session.playerView = view
        session.activity = activity
        session.overlay = overlay
        session.previousSystemUiVisibility = decor.systemUiVisibility
        session.wasFullscreen = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
        addDownloadButton(view, session.title, audioOnly = false) { session.media.uri }
        addBarButton(view, "exo_icon_fullscreen_exit", "Exit fullscreen") { exitRotatedFullscreen() }
        decor.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(decor.windowToken, 0)
        hideSystemBars(activity, decor, session.previousSystemUiVisibility)
        overlay.requestFocus()
    }

    /**
     * Leaves a rotated fullscreen: the screen goes back to how it was, and the
     * paused player waits to be put back in its embed once the chat is rebuilt.
     */
    private fun exitRotatedFullscreen() {
        val session = rotated ?: return
        rotated = null
        session.wasPlaying = NativePlayers.isPlaying(session.player)
        NativePlayers.pause(session.player)
        hideRotated(session, restoreOrientation = true)
        errorCallbacks.remove(session.player.g)
        val rowKey = session.rowKey
        if (rowKey == null) {
            NativePlayers.release(session.player)
            return
        }
        returning = session
        // Without a rebuild (the phone is still sideways) the embed rows already exist;
        // after one, onConfigure finds the new row first and these are detached.
        Utils.mainThread.postDelayed({
            if (returning !== session) return@postDelayed
            embedRowKeys.entries
                .firstOrNull { (container, key) -> key == rowKey && container.isAttachedToWindow }
                ?.key
                ?.let(::returnInto)
        }, RETURN_RETRY_DELAY_MS)
        Utils.mainThread.postDelayed({
            if (returning !== session) return@postDelayed
            returning = null
            NativePlayers.release(session.player)
        }, RETURN_TIMEOUT_MS)
    }

    /** Puts a player back from a rotated fullscreen into [container], its embed, if it's waiting for it. */
    private fun returnInto(container: ViewGroup) {
        val session = returning ?: return
        if (!container.isAttachedToWindow || embedRowKeys[container] != session.rowKey) return
        if (players[container] != null) return
        returning = null
        val attached = attachPlayer(
            container,
            session.identity,
            session.media,
            session.provider,
            session.title,
            existing = session.player,
        )
        if (attached && session.wasPlaying) NativePlayers.resume(session.player)
    }

    private fun hideRotated(session: RotatedSession, restoreOrientation: Boolean) {
        val overlay = session.overlay
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        session.overlay = null
        session.playerView?.setPlayer(null)
        session.playerView = null
        val activity = session.activity ?: return
        restoreSystemBars(activity, session.previousSystemUiVisibility, session.wasFullscreen)
        if (restoreOrientation) activity.requestedOrientation = session.previousOrientation
    }

    /** Closes any rotated fullscreen and drops a player waiting to return, releasing them. */
    private fun endRotated() {
        rotated?.let { session ->
            rotated = null
            hideRotated(session, restoreOrientation = true)
            errorCallbacks.remove(session.player.g)
            NativePlayers.release(session.player)
        }
        returning?.let { session ->
            returning = null
            NativePlayers.release(session.player)
        }
    }

    /**
     * Follows a [RotatedSession] across the activities Discord rebuilds while the
     * screen rotates: the old one's overlay goes with it, the new one is kept in
     * landscape from its creation and gets a new overlay once it's resumed.
     */
    private val rotationCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            val session = rotated ?: return
            if (session.activity == null && activity is AppActivity) activity.requestedOrientation = RotatedSession.LANDSCAPE
        }

        override fun onActivityResumed(activity: Activity) {
            val session = rotated ?: return
            if (session.activity == null && activity is AppActivity) showRotated(session, activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            val session = rotated ?: return
            if (session.activity !== activity) return
            session.activity = null
            session.overlay = null
            // Closed for good rather than rebuilt for the rotation.
            if (!activity.isChangingConfigurations) endRotated()
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    @Suppress("DEPRECATION")
    private fun exitFullscreen(root: View) {
        val state = fullscreenPlayers.remove(root) ?: return
        val activity = state.activity
        state.overlay.removeView(root)
        (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
        restoreSystemBars(activity, state.previousSystemUiVisibility, state.wasFullscreen)
        activity.requestedOrientation = state.previousOrientation
        val index = state.index.coerceIn(0, state.parent.childCount)
        state.parent.addView(root, index, state.layoutParams)
        root.bringToFront()
        setFullscreenIcon(state.button, fullscreen = false)
    }

    private fun activityFrom(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * The fragment manager of the screen showing [context]. Aliucord's appActivity only changes when a
     * screen is created, so after closing settings or the image viewer it points at a closed screen,
     * and Discord's dialogs silently refuse to show there.
     */
    private fun fragmentManagerFor(context: Context): FragmentManager {
        val activity = activityFrom(context) as? FragmentActivity
        return activity?.supportFragmentManager ?: Utils.appActivity.supportFragmentManager
    }

    private fun id(name: String): Int = Utils.getResId(name, "id")

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun addSpotifyPlayButton(container: ViewGroup, listener: View.OnClickListener) {
        val existing = spotifyPlayButtons[container]
        if (existing != null && existing.parent != null) {
            existing.setOnClickListener(listener)
            return
        }
        removeSpotifyPlayButton(container)

        val size = dp(container.context, 32)
        val button = ImageView(container.context)
        val iconId = Utils.getResId("ic_play_arrow_24dp", "drawable")
        if (iconId > 0) button.setImageResource(iconId)
        val backgroundId = Utils.getResId("drawable_circle_primary_900", "drawable")
        if (backgroundId > 0) button.setBackgroundResource(backgroundId)
        button.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        val padding = dp(container.context, 4)
        button.setPadding(padding, padding, padding, padding)
        button.contentDescription = "Play Spotify embed"
        button.isClickable = true
        button.setOnClickListener(listener)

        val thumbnailId = id("chat_list_item_embed_image_thumbnail")
        val imageContainerId = id("embed_image_container")
        val image = listOfNotNull(
            container.findViewById<View>(thumbnailId),
            container.findViewById<View>(imageContainerId),
        ).firstOrNull { it.visibility == View.VISIBLE }
        val imageParent = image?.parent as? ConstraintLayout
        val imageId = image?.id ?: View.NO_ID
        val layoutParams = if (imageParent != null && imageId != View.NO_ID) {
            ConstraintLayout.LayoutParams(size, size).apply {
                startToStart = imageId
                endToEnd = imageId
                topToTop = imageId
                bottomToBottom = imageId
            }
        } else if (container is FrameLayout) {
            FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(container.context, 4)
            }
        } else {
            ViewGroup.LayoutParams(size, size)
        }
        (imageParent ?: container).addView(button, layoutParams)
        button.elevation = dp(container.context, 2).toFloat()
        button.bringToFront()
        spotifyPlayButtons[container] = button
    }

    private fun removeSpotifyPlayButton(container: ViewGroup) {
        val button = spotifyPlayButtons.remove(container) ?: return
        (button.parent as? ViewGroup)?.removeView(button)
    }

    override fun stop(context: Context) {
        application?.unregisterActivityLifecycleCallbacks(rotationCallbacks)
        application = null
        endRotated()
        ArrayList(fullscreenPlayers.keys).forEach(::exitFullscreen)
        ArrayList(players.keys).forEach(::removeNativePlayer)
        ArrayList(loadingIndicators.keys).forEach(::hideLoading)
        ArrayList(spotifyPlayButtons.keys).forEach(::removeSpotifyPlayButton)
        EmbedUrls.fxVideoResolver?.close()
        EmbedUrls.fxVideoResolver = null
        youtube?.close()
        soundCloud?.close()
        spotify?.close()
        downloader?.close()
        downloader = null
        youtube = null
        soundCloud = null
        spotify = null
        configuredEmbeds.clear()
        embedLinks.clear()
        hostedEmbeds.clear()
        players.clear()
        loadingIndicators.clear()
        spotifyPlayButtons.clear()
        errorCallbacks.clear()
        embedRowKeys.clear()
        searchEmbedClicks.clear()
        youtubeQualityChoices.clear()
        patcher.unpatchAll()
    }

    internal companion object {
        const val KEY_WHOLE_CARD = "replaceWholeCard"
        const val KEY_ROTATE_FULLSCREEN = "rotateFullscreen"
        const val KEY_YOUTUBE_QUALITY = "youtubeQuality"

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val DETACH_RELEASE_DELAY_MS = 1_000L
        private const val MIN_VISIBLE_FRACTION = 0.25f
        private const val LOADING_TICK_MS = 500L
        private const val FULLSCREEN_BUTTON_TAG = "PlayEmbedsFullscreen"
        private const val RETURN_RETRY_DELAY_MS = 1_000L
        private const val RETURN_TIMEOUT_MS = 15_000L
        private const val SEARCH_EVENT_HANDLER =
            "com.discord.widgets.search.results.WidgetSearchResults\$SearchResultAdapterEventHandler"

        /** Where Android checks a host's cleartext permission (the policy, and the config it asks). */
        private val CLEARTEXT_POLICY_CLASSES = listOf(
            "android.security.net.config.ConfigNetworkSecurityPolicy",
            "android.security.net.config.ApplicationConfig",
        )
    }
}
