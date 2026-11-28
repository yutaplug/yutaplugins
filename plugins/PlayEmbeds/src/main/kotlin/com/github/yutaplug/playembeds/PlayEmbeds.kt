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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
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
import android.widget.RelativeLayout
import android.widget.Space
import android.widget.TextView
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.android.billingclient.api.ProxyBillingActivity
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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
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
    private val watchedBars: MutableSet<LinearLayout> = Collections.newSetFromMap(WeakHashMap())

    /** Keyed by each player's ExoPlayer listener (`AppMediaPlayer.g`), which receives errors. */
    private val errorCallbacks = WeakHashMap<Any, () -> Unit>()

    /** Quality picked in a YouTube player, by embed URL, so replaying the video keeps it. */
    private val youtubeQualityChoices = HashMap<String, Int>()

    /** Speed and captions by ExoPlayer (`AppMediaPlayer.f`). */
    private val playbackExtras = WeakHashMap<Any, PlaybackExtras>()

    /** The caption language last picked in a YouTube player, turned on in the next ones too. */
    private var captionLanguage: String? = null

    private var youtube: YoutubeResolver? = null
    private var soundCloud: SoundCloudResolver? = null
    private var spotify: SpotifyResolver? = null
    private var twitch: TwitchClipResolver? = null
    private var downloader: MediaDownloader? = null

    /** The video in a rotated fullscreen, and one that left it and waits for its embed row. */
    private var rotated: RotatedSession? = null
    private var returning: RotatedSession? = null
    private var application: Application? = null

    /** The video in a fullscreen screen of its own (see [enterHostedFullscreen]). */
    private var hosted: RotatedSession? = null
    private var hostPatched = false

    /** Waits for the phone to be upright to give Discord's screen its orientation back. */
    private var orientationUnlock: Pair<OrientationEventListener, Runnable>? = null

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
        twitch = TwitchClipResolver()
        EmbedUrls.tikTokResolver = TikTokResolver()
        downloader = MediaDownloader(context.applicationContext)
        NativePlayers.init()
        allowLoopbackCleartext()
        patchEmbedRows()
        patchNativeHostedLaunches()
        patchPlayerErrors()
        patchOutsideTaps()
        hostPatched = try {
            patchFullscreenHost()
            true
        } catch (e: Throwable) {
            // Then fullscreen turns the player sideways instead.
            DebugLog.log("host: couldn't patch its screen: $e")
            false
        }
        application =(context.applicationContext as? Application)?.also {
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
                DebugLog.log("player error: ${frame.args.firstOrNull()}")
                Utils.mainThread.post { callback() }
            },
        )
    }

    /**
     * A tap anywhere outside an inline player hides its controls, instead of
     * them staying up until their timeout. Fullscreen players cover the whole
     * screen, so they're left alone.
     */
    private fun patchOutsideTaps() {
        val dispatch = Activity::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        patcher.patch(
            dispatch,
            PreHook { frame ->
                val event = frame.args.getOrNull(0) as? MotionEvent ?: return@PreHook
                if (event.actionMasked != MotionEvent.ACTION_DOWN) return@PreHook
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                for (state in players.values) {
                    val root = state.root
                    if (fullscreenPlayers.containsKey(root) || !root.isShown) continue
                    val playerView = (root as? ViewGroup)?.getChildAt(0) as? PlayerView ?: continue
                    if (playerView.t?.e() != true) continue
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    val inside = x >= location[0] && x < location[0] + root.width &&
                        y >= location[1] && y < location[1] + root.height
                    if (!inside) playerView.d()
                }
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
                    val fxUrl = EmbedUrls.fxLookupUrl(entry.embed)
                    if (fxUrl != null) {
                        val root = WeakReference(item.itemView)
                        val embed = entry.embed
                        val rowKey = entry.message.id to entry.embedIndex
                        EmbedUrls.fxVideoResolver?.resolve(fxUrl) { video ->
                            val view = root.get()
                            if (FxVideoResolver.isVideo(video) && view != null && configuredEmbeds[view] === embed &&
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
                if (playPendingFxVideo(container, embed) { runOriginal(frame) }) {
                    frame.setResult(null)
                    return@PreHook
                }
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
                if (container != null && playPendingFxVideo(container, embed) { runOriginal(frame) }) {
                    frame.setResult(null)
                    return@PreHook
                }
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
        HostedProvider.TWITCH -> twitch
        HostedProvider.TIKTOK -> EmbedUrls.tikTokResolver
    }

    private fun playHosted(view: View, embed: HostedEmbed): Boolean {
        val container = findHostedContainer(view)
            ?: embed.sourceEmbed?.let(::findHostedContainer)
            ?: return false
        return playHosted(container, embed)
    }

    /**
     * YouTube, SoundCloud, Spotify, Twitch clip and TikTok links are webpages, not media files.
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
        showLoading(container)
        youtubeResolver.resolve(embed.url, quality, done)
        return true
    }

    /**
     * An X video whose mp4 FxTwitter hasn't returned yet: wait for it with a spinner
     * and play it then, instead of letting Discord load X's HTML player page.
     * When it's a GIF, [discord] does what Discord would have (its fullscreen viewer);
     * when there's no mp4, open the tweet. False when the embed doesn't need this.
     */
    private fun playPendingFxVideo(container: ViewGroup, embed: MessageEmbed, discord: () -> Unit): Boolean {
        val fxUrl = EmbedUrls.fxLookupUrl(embed) ?: return false
        val resolver = EmbedUrls.fxVideoResolver ?: return false
        if (resolver.cached(fxUrl) != null) return false
        if (loadingIndicators[container] != null) return true
        val rowKey = embedRowKeys[container]
        val containerRef = WeakReference(container)
        showLoading(container)
        resolver.resolve(fxUrl) { video ->
            val target = containerRef.get() ?: return@resolve
            hideLoading(target)
            if (!target.isAttachedToWindow || embedRowKeys[target] != rowKey) return@resolve
            if (video == FxVideoResolver.GIF) {
                DebugLog.log("x video: $fxUrl is a gif, opening it in Discord")
                discord()
            } else if (video == null || !playInlineVideo(target, embed)) {
                DebugLog.log("x video: no mp4 for $fxUrl, opening the tweet")
                openExternally(target.context, embed.l() ?: fxUrl)
            }
        }
        return true
    }

    /** Runs the hooked Discord method a PreHook held back, as if it hadn't been hooked. */
    private fun runOriginal(frame: XC_MethodHook.MethodHookParam) {
        try {
            XposedBridge.invokeOriginalMethod(frame.method, frame.thisObject, frame.args)
        } catch (e: Throwable) {
            DebugLog.log("couldn't run Discord's own action: ${DebugLog.causes(e)}")
        }
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
            provider == HostedProvider.YOUTUBE || provider == HostedProvider.TWITCH ||
                provider == HostedProvider.TIKTOK ->
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
        if (provider == HostedProvider.YOUTUBE && !media.audioOnly) {
            ((state.root as? ViewGroup)?.getChildAt(0) as? PlayerView)?.let { addPlaybackControls(it, player, identity) }
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
            if (wasOnScreen && !onScreen) {
                DebugLog.log("embed scrolled out of view: paused")
                playerRef.get()?.let(NativePlayers::pause)
            }
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
                // A scroll recycles the row as soon as it's off screen, often before the scroll
                // check sees it leave, so pause right away. Posting skips moves that re-attach
                // it in the same pass (fullscreen, relayout); release still waits for the delay.
                Utils.mainThread.post {
                    val root = viewRef.get() ?: return@post
                    val target = containerRef.get() ?: return@post
                    val player = playerRef.get() ?: return@post
                    if (!root.isAttachedToWindow && players[target]?.player === player) {
                        DebugLog.log("embed row left the screen: paused")
                        NativePlayers.pause(player)
                    }
                }
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
        DebugLog.log("embed player released")
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
        val size = spinnerSize(context)
        val indicator = loadingSpinner(context)
        parent.addView(indicator, centeredParams(parent, anchor, size, size))
        indicator.bringToFront()
        loadingIndicators[container] = indicator
    }

    /** The size of Discord's embed play button, which the spinner replaces while loading. */
    private fun spinnerSize(context: Context): Int {
        val sizeId = Utils.getResId("embed_play_button_width", "dimen")
        return if (sizeId > 0) context.resources.getDimensionPixelSize(sizeId) else dp(context, 48)
    }

    private fun loadingSpinner(context: Context): ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        val backgroundId = Utils.getResId("drawable_circle_primary_900", "drawable")
        if (backgroundId > 0) setBackgroundResource(backgroundId)
        val padding = dp(context, 8)
        setPadding(padding, padding, padding, padding)
        indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        alpha = 0.6f
        elevation = dp(context, 2).toFloat()
        // Taps on the spinner shouldn't reach the player underneath.
        isClickable = true
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
        val row = buttonRow(bar)
        row.addView(
            label,
            if (row === bar) bar.indexOfChild(duration) + 1 else 0,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(context, 12) },
        )
        fitSeekbar(bar)
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
        val row = buttonRow(bar)
        val fullscreen = row.findViewWithTag<View>(FULLSCREEN_BUTTON_TAG)
        val index = row.indexOfChild(fullscreen).takeIf { it >= 0 } ?: row.childCount
        row.addView(button, index, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(context, 16) })
        fitSeekbar(bar)
        return button
    }

    /** Where extra buttons go: the compact video layout's top strip, or the control bar itself. */
    private fun buttonRow(bar: LinearLayout): LinearLayout {
        if (bar.tag != COMPACT_BAR_TAG) return bar
        return (bar.parent as? ViewGroup)?.findViewWithTag(TOP_BUTTONS_TAG) ?: bar
    }

    /**
     * Rearranges a video player's controls into a compact overlay: play/pause
     * as a round button in the middle, the extra buttons (quality, download,
     * captions, speed, fullscreen) in a strip at the top right, and only the
     * position, seekbar and duration left in a slim bottom row. Both rows use
     * a lighter, more see-through background than Discord's bar.
     */
    private fun compactControls(bar: LinearLayout) {
        if (bar.tag == COMPACT_BAR_TAG) return
        val host = bar.parent as? RelativeLayout ?: return
        val context = bar.context
        bar.tag = COMPACT_BAR_TAG
        bar.setBackgroundColor(CONTROLS_BACKGROUND)
        val horizontal = dp(context, 12)
        val vertical = dp(context, 6)
        bar.setPadding(horizontal, vertical, horizontal, vertical)
        (bar.findViewById<View>(id("exo_position"))?.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.marginEnd = dp(context, 10)
        (bar.findViewById<View>(id("exo_duration"))?.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.marginStart = dp(context, 10)

        val center = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CONTROLS_BACKGROUND)
            }
        }
        val icon = dp(context, 28)
        val playButtons = ArrayList<View>()
        for (name in arrayOf("exo_play", "exo_pause")) {
            val button = bar.findViewById<View>(id(name)) ?: continue
            bar.removeView(button)
            center.addView(button, FrameLayout.LayoutParams(icon, icon, Gravity.CENTER))
            playButtons.add(button)
        }
        // The ring around the icon toggles playback too, instead of hiding the controls.
        center.setOnClickListener { playButtons.firstOrNull { it.visibility == View.VISIBLE }?.performClick() }
        val size = dp(context, 52)
        host.addView(center, RelativeLayout.LayoutParams(size, size).apply { addRule(RelativeLayout.CENTER_IN_PARENT) })

        val strip = topButtonStrip(host, bar)
        strip.setPadding(0, vertical, horizontal, vertical)
        host.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) view.post { fitButtonStrip(strip, right - left) }
        }
        strip.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = host.width
            if (width > 0) view.post { fitButtonStrip(strip, width) }
        }
    }

    /** Narrows the gaps between the top strip's buttons when the player is too thin for them. */
    private fun fitButtonStrip(strip: LinearLayout, available: Int) {
        if (available <= 0 || strip.childCount == 0) return
        val context = strip.context
        var content = strip.paddingLeft + strip.paddingRight
        for (index in 0 until strip.childCount) {
            val child = strip.getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val params = child.layoutParams
            content += if (params != null && params.width > 0) params.width else child.measuredWidth
        }
        val gap = ((available - content) / strip.childCount)
            .coerceIn(dp(context, MIN_STRIP_GAP_DP), dp(context, STRIP_GAP_DP))
        for (index in 0 until strip.childCount) {
            val params = strip.getChildAt(index).layoutParams as? LinearLayout.LayoutParams ?: continue
            if (params.marginStart != gap) {
                params.marginStart = gap
                strip.getChildAt(index).layoutParams = params
            }
        }
    }

    /**
     * Discord's control bar is a single row (play, position, seekbar, duration)
     * where the seekbar only gets the width that's left. Once two or more of our
     * controls are added it would shrink to nothing in an embed, so it moves to
     * its own row just above the bar, as wide as the player.
     */
    private fun fitSeekbar(bar: LinearLayout, force: Boolean = false) {
        if (bar.tag == COMPACT_BAR_TAG) return
        watchBarWidth(bar)
        val timeBar = bar.findViewById<View>(id("exo_progress")) ?: return
        if (timeBar.parent !== bar || (!force && bar.childCount - STOCK_BAR_CHILDREN < 2)) return
        val host = bar.parent as? RelativeLayout ?: return
        val context = bar.context
        if (bar.id == View.NO_ID) bar.id = View.generateViewId()
        val index = bar.indexOfChild(timeBar)
        bar.removeView(timeBar)
        // Keeps the duration and the buttons at the end of the row, where they were.
        bar.addView(Space(context), index, LinearLayout.LayoutParams(0, 0, 1f))
        val gap = dp(context, 4)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = SEEKBAR_ROW_TAG
            background = bar.background?.constantState?.newDrawable()
            setPadding(bar.paddingLeft, gap, bar.paddingRight, 0)
            addView(timeBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        host.addView(row, RelativeLayout.LayoutParams(MATCH, WRAP).apply { addRule(RelativeLayout.ABOVE, bar.id) })
        bar.setPadding(bar.paddingLeft, gap, bar.paddingRight, bar.paddingBottom)
    }

    /**
     * A narrow (portrait) video can be too thin for the control bar, pushing the
     * buttons at its end (fullscreen, download, quality) out of the player where
     * they can't be tapped. Once the bar overflows, the seekbar moves to its own
     * row and, if that's still not enough, the buttons move up beside it.
     */
    private fun watchBarWidth(bar: LinearLayout) {
        if (!watchedBars.add(bar)) return
        bar.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val target = view as LinearLayout
            if (barOverflows(target)) target.post { wrapBarButtons(target) }
        }
    }

    private fun barOverflows(bar: LinearLayout): Boolean {
        if (bar.width <= 0) return false
        val timeBarId = id("exo_progress")
        var needed = bar.paddingLeft + bar.paddingRight
        for (index in 0 until bar.childCount) {
            val child = bar.getChildAt(index)
            if (child.visibility == View.GONE || child is Space) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            needed += (params?.marginStart ?: 0) + (params?.marginEnd ?: 0)
            // The seekbar gets whatever is left, so count a usable minimum for it.
            needed += if (child.id == timeBarId) dp(bar.context, MIN_SEEKBAR_DP) else child.measuredWidth
        }
        return needed > bar.width
    }

    /**
     * Makes room in an overflowing control bar, keeping the seekbar long:
     * 1. the seekbar gets its own full-width row above the bar;
     * 2. the position and duration move into that row, on either side of the
     *    seekbar, leaving the bar with play and the buttons;
     * 3. only if that still doesn't fit (a very narrow player), the buttons move
     *    to a strip in the player's top-right corner, the fullscreen button last.
     */
    private fun wrapBarButtons(bar: LinearLayout) {
        if (!barOverflows(bar)) return
        fitSeekbar(bar, force = true)
        if (!barOverflows(bar)) return
        val host = bar.parent as? RelativeLayout ?: return
        val row = host.findViewWithTag<LinearLayout>(SEEKBAR_ROW_TAG)
        val timeBar = row?.findViewById<View>(id("exo_progress"))
        if (row != null && timeBar != null) {
            bar.findViewById<View>(id("exo_position"))?.takeIf { it.parent === bar }?.let { position ->
                val params = position.layoutParams
                bar.removeView(position)
                row.addView(position, row.indexOfChild(timeBar), params)
            }
            bar.findViewById<View>(id("exo_duration"))?.takeIf { it.parent === bar }?.let { duration ->
                val params = duration.layoutParams
                bar.removeView(duration)
                row.addView(duration, row.indexOfChild(timeBar) + 1, params)
            }
            if (!barOverflows(bar)) return
        }
        // The buttons are everything after the spacer that took the seekbar's place.
        var start = -1
        for (index in 0 until bar.childCount) {
            if (bar.getChildAt(index) is Space) start = index + 1
        }
        if (start <= 0) return
        // A plain loop: IntRange.map iterates Discord's stripped IntRange and crashes (ClassCastException).
        val buttons = ArrayList<View>()
        val durationId = id("exo_duration")
        for (index in start until bar.childCount) {
            val child = bar.getChildAt(index)
            if (child.id != durationId) buttons.add(child)
        }
        val fullscreen = buttons.firstOrNull { it.tag == FULLSCREEN_BUTTON_TAG }
        val strip = topButtonStrip(host, bar)
        buttons.forEach { button ->
            if (button === fullscreen) return@forEach
            val params = button.layoutParams
            bar.removeView(button)
            strip.addView(button, params)
        }
        if (fullscreen != null) {
            bar.post {
                if (!barOverflows(bar) || fullscreen.parent !== bar) return@post
                val params = fullscreen.layoutParams
                bar.removeView(fullscreen)
                strip.addView(fullscreen, params)
            }
        }
    }

    /** The top-right strip [wrapBarButtons] moves buttons to, with the control bar's background. */
    private fun topButtonStrip(host: RelativeLayout, bar: LinearLayout): LinearLayout {
        host.findViewWithTag<LinearLayout>(TOP_BUTTONS_TAG)?.let { return it }
        val context = host.context
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TOP_BUTTONS_TAG
            background = bar.background?.constantState?.newDrawable()
            // The buttons' own start margins space them from the strip's start.
            val vertical = dp(context, 8)
            setPadding(0, vertical, bar.paddingRight, vertical)
        }
        host.addView(
            strip,
            RelativeLayout.LayoutParams(WRAP, WRAP).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_END)
            },
        )
        return strip
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
        showPicker(
            control.label,
            "PlayEmbedsQuality",
            "Video quality",
            qualities.map { if (it == current) "✓ ${it}p" else "${it}p" }.toTypedArray(),
        ) { which -> qualities.getOrNull(which)?.let { switchQuality(control, it) } }
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
        if (root != null) {
            val spinner = loadingSpinner(root.context)
            val size = spinnerSize(root.context)
            root.addView(spinner, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            spinner.bringToFront()
            control.loadingCard = spinner
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
        // Loading the new stream can reset the speed.
        playbackExtras[player.f as Any]?.speed?.takeIf { it != 1f }?.let { NativePlayers.setSpeed(player, it) }
    }

    private fun hideQualityLoading(control: QualityControl) {
        val card = control.loadingCard ?: return
        control.loadingCard = null
        (card.parent as? ViewGroup)?.removeView(card)
    }

    /** Speed and captions of a player, shared by every view that shows it (embed and fullscreen). */
    private class PlaybackExtras {
        var speed = 1f
        var tracks: List<CaptionTrack>? = null
        var loadingTracks = false
        val trackWaiters = ArrayList<(List<CaptionTrack>?) -> Unit>()
        var track: CaptionTrack? = null
        var cues: List<CaptionCue>? = null

        /** The track being loaded, so only the last pick is applied. */
        var pendingTrack: CaptionTrack? = null
    }

    private fun extrasFor(player: AppMediaPlayer): PlaybackExtras {
        val exo: Any = player.f
        return playbackExtras[exo] ?: PlaybackExtras().also { playbackExtras[exo] = it }
    }

    /**
     * Speed and captions buttons in a YouTube player's control bar (ExoPlayer's
     * own icons), and the caption text over the video. [url] is the embed URL.
     */
    private fun addPlaybackControls(playerView: PlayerView, player: AppMediaPlayer, url: String) {
        val extras = extrasFor(player)
        var captionsButton: ImageButton? = null
        captionsButton = addBarButton(playerView, "exo_ic_subtitle_off", "Captions") {
            captionsButton?.let { showCaptionPicker(it, player, url) }
        }
        addBarButton(playerView, "exo_ic_speed", "Playback speed") { showSpeedPicker(playerView, player) }
        addCaptionOverlay(playerView, player, captionsButton)
        restoreCaptions(extras, url)
    }

    private fun showSpeedPicker(view: View, player: AppMediaPlayer) {
        val extras = extrasFor(player)
        val labels = PLAYBACK_SPEEDS.map { speed ->
            val label = if (speed == 1f) "Normal" else speedLabel(speed)
            if (speed == extras.speed) "✓ $label" else label
        }.toTypedArray()
        showPicker(view, "PlayEmbedsSpeed", "Playback speed", labels) { which ->
            PLAYBACK_SPEEDS.getOrNull(which)?.let { speed ->
                if (NativePlayers.setSpeed(player, speed)) {
                    extras.speed = speed
                } else {
                    Utils.showToast("Couldn't change the speed", true)
                }
            }
        }
    }

    /** 0.25 → "0.25x", 2 → "2x". */
    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

    /**
     * The captions button: with one track it just turns captions on or off; with
     * more it lists them in a bottom sheet (or a sheet drawn over the borrowed
     * fullscreen screen, which has no fragments).
     */
    private fun showCaptionPicker(view: View, player: AppMediaPlayer, url: String) {
        val extras = extrasFor(player)
        val tracks = extras.tracks
        if (tracks == null) {
            // A second tap while the list loads would open it twice.
            if (extras.loadingTracks) return
            loadCaptionTracks(extras, url) { loaded ->
                when {
                    loaded == null -> Utils.showToast("Couldn't load captions", true)
                    loaded.isEmpty() -> Utils.showToast("This video has no captions")
                    view.isAttachedToWindow -> showCaptionPicker(view, player, url)
                }
            }
            return
        }
        if (tracks.isEmpty()) {
            Utils.showToast("This video has no captions")
            return
        }
        val current = extras.pendingTrack ?: extras.track
        if (tracks.size == 1) {
            selectCaptions(extras, if (current == null) tracks[0] else null)
            return
        }
        val names = listOf("Off") + tracks.map { it.name }
        val selected = if (current == null) 0 else tracks.indexOfFirst { it === current } + 1
        val onPick: (Int) -> Unit = { which ->
            if (which == 0) selectCaptions(extras, null) else tracks.getOrNull(which - 1)?.let { selectCaptions(extras, it) }
        }
        if (activityFrom(view.context) !is FragmentActivity) {
            val labels = names.mapIndexed { index, name -> if (index == selected) "✓ $name" else name }.toTypedArray()
            if (showOverlayPicker(view, "Captions", labels, onPick, sheet = true)) return
        }
        CaptionsSheet(names, selected, onPick).show(fragmentManagerFor(view.context), "PlayEmbedsCaptions")
    }

    /** Looks up [url]'s caption tracks once per player; [done] gets them (null when the lookup failed). */
    private fun loadCaptionTracks(extras: PlaybackExtras, url: String, done: (List<CaptionTrack>?) -> Unit) {
        extras.tracks?.let {
            done(it)
            return
        }
        val resolver = youtube ?: return done(null)
        extras.trackWaiters.add(done)
        if (extras.loadingTracks) return
        extras.loadingTracks = true
        Utils.threadPool.execute {
            val tracks = try {
                resolver.captionTracks(url)
            } catch (_: Throwable) {
                null
            }
            DebugLog.log("captions: ${tracks?.size ?: "no"} tracks")
            Utils.mainThread.post {
                extras.loadingTracks = false
                // None found is asked again on the next tap, in case YouTube refused only for a while.
                if (tracks != null && tracks.isNotEmpty()) extras.tracks = tracks
                val waiters = ArrayList(extras.trackWaiters)
                extras.trackWaiters.clear()
                waiters.forEach { it(tracks) }
            }
        }
    }

    /** Shows [track] (or no captions); with [remember], later videos turn on the same language. */
    private fun selectCaptions(extras: PlaybackExtras, track: CaptionTrack?, remember: Boolean = true) {
        extras.pendingTrack = track
        if (remember) captionLanguage = track?.languageCode
        if (track == null) {
            extras.track = null
            extras.cues = null
            return
        }
        Utils.threadPool.execute {
            val cues = Captions.load(track)
            Utils.mainThread.post {
                if (extras.pendingTrack !== track) return@post
                extras.pendingTrack = null
                if (cues == null) {
                    Utils.showToast("Couldn't load captions", true)
                    return@post
                }
                extras.track = track
                extras.cues = cues
            }
        }
    }

    /** Turns captions on in the language last picked, like YouTube remembers it. */
    private fun restoreCaptions(extras: PlaybackExtras, url: String) {
        val language = captionLanguage ?: return
        if (extras.track != null || extras.pendingTrack != null) return
        loadCaptionTracks(extras, url) { tracks ->
            if (tracks == null || extras.track != null || extras.pendingTrack != null) return@loadCaptionTracks
            val match = tracks.firstOrNull { it.languageCode == language && !it.auto }
                ?: tracks.firstOrNull { it.languageCode == language }
                ?: return@loadCaptionTracks
            selectCaptions(extras, match, remember = false)
        }
    }

    /**
     * The current caption over the video, in ExoPlayer's overlay frame (under
     * the controls), white on translucent black like YouTube's. It moves above
     * the control bar while that's showing.
     */
    private fun addCaptionOverlay(playerView: PlayerView, player: AppMediaPlayer, button: ImageButton?) {
        val context = playerView.context
        val host = playerView.findViewById<FrameLayout>(id("exo_overlay")) ?: playerView
        val duration = playerView.findViewById<TextView>(id("exo_duration"))
        val caption = CaptionView(context, CAPTION_BACKGROUND).apply {
            duration?.typeface?.let { typeface = it }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val horizontal = dp(context, 6)
            val vertical = dp(context, 2)
            setPadding(horizontal, vertical, horizontal, vertical)
            visibility = View.GONE
        }
        host.addView(
            caption,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                val side = dp(context, 16)
                leftMargin = side
                rightMargin = side
            },
        )
        val exo: Any = player.f
        val tick = object : Runnable {
            override fun run() {
                updateCaption(playerView, player, exo, caption, button)
                caption.postDelayed(this, CAPTION_TICK_MS)
            }
        }
        caption.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeCallbacks(tick)
                view.post(tick)
            }

            override fun onViewDetachedFromWindow(view: View) {
                view.removeCallbacks(tick)
            }
        })
        if (caption.isAttachedToWindow) caption.post(tick)
    }

    private fun updateCaption(playerView: PlayerView, player: AppMediaPlayer, exo: Any, caption: TextView, button: ImageButton?) {
        val extras = playbackExtras[exo]
        if (button != null) {
            val on = extras?.track != null
            if (button.tag != on) {
                button.tag = on
                val iconId = Utils.getResId(if (on) "exo_ic_subtitle_on" else "exo_ic_subtitle_off", "drawable")
                if (iconId > 0) button.setImageResource(iconId)
                button.contentDescription = if (on) "Captions: ${extras?.track?.name}" else "Captions: off"
            }
        }
        val cues = extras?.cues
        // A view that handed the player to fullscreen (or back) shows nothing.
        val text = if (cues == null || playerView.player !== exo) "" else Captions.textAt(cues, NativePlayers.position(player))
        if (text.isEmpty()) {
            if (caption.visibility != View.GONE) caption.visibility = View.GONE
            return
        }
        if (caption.text.toString() != text) caption.text = text
        val context = playerView.context
        val size = (playerView.height * CAPTION_SIZE_FRACTION)
            .coerceIn(dp(context, CAPTION_MIN_DP).toFloat(), dp(context, CAPTION_MAX_DP).toFloat())
        if (caption.textSize != size) caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        val controlsTop = controlsTop(playerView)
        val bottom = if (controlsTop != null) playerView.height - controlsTop + dp(context, 4) else dp(context, 12)
        val params = caption.layoutParams as? FrameLayout.LayoutParams
        if (params != null && params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            caption.layoutParams = params
        }
        if (caption.visibility != View.VISIBLE) caption.visibility = View.VISIBLE
    }

    /** The top of the control bar (and the seekbar row above it) in [playerView], or null while it's hidden. */
    private fun controlsTop(playerView: PlayerView): Int? {
        val duration = playerView.findViewById<View>(id("exo_duration")) ?: return null
        if (!duration.isShown) return null
        val bar = duration.parent as? View ?: return null
        val row = (bar.parent as? ViewGroup)?.findViewWithTag<View>(SEEKBAR_ROW_TAG)?.takeIf { it.isShown }
        val barTop = offsetIn(bar, playerView) ?: return null
        val rowTop = row?.let { offsetIn(it, playerView) }
        return if (rowTop != null) minOf(barTop, rowTop) else barTop
    }

    /** [view]'s top within [ancestor]; parent offsets only, so it holds while the player is turned sideways. */
    private fun offsetIn(view: View, ancestor: View): Int? {
        var top = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            top += current.top + current.translationY.toInt()
            current = current.parent as? View
        }
        return if (current === ancestor) top else null
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
        compactControls(bar)
        buttonRow(bar).addView(button, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(context, 16) })
        fitSeekbar(bar)
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
     * rotate setting, a [landscape] video on a portrait screen rotates in a
     * screen of its own instead ([enterHostedFullscreen]), or if that can't open,
     * Discord's own screen rotates ([RotatedSession]). Before Android 8 (see
     * [NativePlayers.turnsPlayerSideways]) the player is turned sideways instead,
     * which is instant where a real rotation takes seconds.
     */
    @Suppress("DEPRECATION")
    private fun enterFullscreen(root: View, button: ImageButton, landscape: Boolean) {
        if (fullscreenPlayers.containsKey(root)) return
        val activity = activityFrom(root.context) ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val portraitScreen = decor.width > 0 && decor.width < decor.height
        val rotate = landscape && rotateFullscreen && portraitScreen
        // Before Android 8 the player is turned sideways: really rotating takes a few seconds there.
        if (rotate && !NativePlayers.turnsPlayerSideways) {
            if (enterHostedFullscreen(root, activity)) return
            if (enterRotatedFullscreen(root, activity)) return
        }
        val sideways = rotate && NativePlayers.turnsPlayerSideways &&
            ((root as? ViewGroup)?.getChildAt(0) as? PlayerView)?.videoSurfaceView is TextureView
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
        if (sideways) {
            // Laid out as wide as the screen is tall, then turned a quarter toward the side
            // the phone is held; upright for a phone turned left until the sensor says otherwise.
            overlay.addView(root, FrameLayout.LayoutParams(decor.height, decor.width, Gravity.CENTER))
            root.rotation = SIDEWAYS_DEGREES
            state.orientationListener = followPhoneSideways(activity, root)
        } else {
            overlay.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        DebugLog.log("fullscreen: enter${if (sideways) " sideways" else ""}, screen ${decor.width}x${decor.height}")
        decor.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        fullscreenPlayers[root] = state

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        hideSystemBars(activity, decor, state.previousSystemUiVisibility)
        overlay.requestFocus()
        setFullscreenIcon(button, fullscreen = true)
    }

    /**
     * Turns a sideways [root] upright for whichever side the phone is turned to.
     * The sensor reports 270° with the phone turned left (its right side up) and
     * 90° turned right; near upright or upside down the player stays as it is.
     */
    private fun followPhoneSideways(activity: Activity, root: View): OrientationEventListener? {
        val listener = object : OrientationEventListener(activity) {
            private var turnedTo = root.rotation

            override fun onOrientationChanged(orientation: Int) {
                val target = when {
                    orientation >= 240 && orientation <= 300 -> SIDEWAYS_DEGREES
                    orientation >= 60 && orientation <= 120 -> -SIDEWAYS_DEGREES
                    else -> return
                }
                if (turnedTo == target) return
                turnedTo = target
                DebugLog.log("fullscreen: phone at $orientation°, player turned to $target°")
                root.animate().cancel()
                root.animate().rotation(target).setDuration(SIDEWAYS_TURN_MS).start()
            }
        }
        if (!listener.canDetectOrientation()) return null
        listener.enable()
        return listener
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
        DebugLog.log(
            "rotate: enter from ${DebugLog.name(activity)}, orientation ${activity.requestedOrientation}, " +
                "video ${media.width}x${media.height} ${media.quality}p, playing ${NativePlayers.isPlaying(state.player)}",
        )
        showRotated(session, activity)
        activity.requestedOrientation = RotatedSession.LANDSCAPE
        return true
    }

    /** Logs [view]'s surface events, and how the rotated player looks a moment after it's shown. */
    private fun traceRotatedView(session: RotatedSession, view: PlayerView, overlay: View) {
        (view.videoSurfaceView as? SurfaceView)?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = DebugLog.log("rotate: surface created")
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                DebugLog.log("rotate: surface changed ${width}x$height")
            override fun surfaceDestroyed(holder: SurfaceHolder) = DebugLog.log("rotate: surface destroyed")
        })
        val viewRef = WeakReference(view)
        val overlayRef = WeakReference(overlay)
        Utils.mainThread.postDelayed({
            val v = viewRef.get() ?: return@postDelayed
            val o = overlayRef.get() ?: return@postDelayed
            val shutter = v.findViewById<View>(id("exo_shutter"))
            val surface = v.videoSurfaceView as? SurfaceView
            val decor = o.parent as? ViewGroup
            DebugLog.log(
                "rotate: after 2s, overlay attached ${o.isAttachedToWindow} shown ${o.isShown} " +
                    "${o.width}x${o.height} focus ${o.hasFocus()} window focus ${o.hasWindowFocus()}, " +
                    "on top ${decor != null && decor.getChildAt(decor.childCount - 1) === o}, " +
                    "view ${v.width}x${v.height}, surface ${surface?.width}x${surface?.height} " +
                    "valid ${surface?.holder?.surface?.isValid}, shutter visible ${shutter?.visibility == View.VISIBLE}, " +
                    "bound ${v.player === session.player.f}, playing ${NativePlayers.isPlaying(session.player)}, " +
                    "state ${NativePlayers.playbackState(session.player)}, position ${NativePlayers.position(session.player)}",
            )
        }, TRACE_DELAY_MS)
    }

    /** Shows [session]'s player in a fullscreen overlay in [activity]. */
    @Suppress("DEPRECATION")
    private fun showRotated(session: RotatedSession, activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        DebugLog.log(
            "rotate: show in ${DebugLog.name(activity)}, decor ${decor.width}x${decor.height}, " +
                "${decor.childCount} decor children, resumed window focus ${activity.hasWindowFocus()}",
        )
        val overlay = FullscreenOverlay(activity) {
            DebugLog.log("rotate: back pressed in overlay")
            exitRotatedFullscreen()
        }
        val view = NativePlayers.videoView(activity)
        overlay.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        traceRotatedView(session, view, overlay)
        session.playerView?.setPlayer(null)
        NativePlayers.bind(session.player, view, null)
        session.playerView = view
        session.activity = activity
        session.overlay = overlay
        session.previousSystemUiVisibility = decor.systemUiVisibility
        session.wasFullscreen = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
        addDownloadButton(view, session.title, audioOnly = false) { session.media.uri }
        if (session.provider == HostedProvider.YOUTUBE) addPlaybackControls(view, session.player, session.identity)
        addBarButton(view, "exo_icon_fullscreen_exit", "Exit fullscreen") {
            DebugLog.log("rotate: exit button tapped")
            exitRotatedFullscreen()
        }
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
        DebugLog.log("rotate: exit, was playing ${session.wasPlaying}, row ${session.rowKey}")
        NativePlayers.pause(session.player)
        hideRotated(session, restoreOrientation = true)
        errorCallbacks.remove(session.player.g)
        sendBack(session)
    }

    /**
     * Has the paused [session] player wait to go back into its embed: once Discord's
     * activity resumes, again a moment later, and otherwise it's released. If the
     * chat is rebuilt meanwhile, onConfigure finds the new row first.
     */
    private fun sendBack(session: RotatedSession) {
        if (session.rowKey == null) {
            NativePlayers.release(session.player)
            return
        }
        returning = session
        // Without a rebuild (the phone is still sideways) the embed rows already exist.
        Utils.mainThread.postDelayed({ if (returning === session) tryReturn() }, RETURN_RETRY_DELAY_MS)
        Utils.mainThread.postDelayed({
            if (returning !== session) return@postDelayed
            returning = null
            DebugLog.log("rotate: embed row never came back, released")
            NativePlayers.release(session.player)
        }, RETURN_TIMEOUT_MS)
    }

    /** Puts the [returning] player back into its embed row, if that row is on screen. */
    private fun tryReturn() {
        val rowKey = returning?.rowKey ?: return
        embedRowKeys.entries
            .firstOrNull { (container, key) -> key == rowKey && container.isAttachedToWindow }
            ?.key
            ?.let(::returnInto)
    }

    /**
     * Fullscreen with the screen really rotated, without rebuilding Discord's
     * activity (which blinks, and before Android 8 loops, see
     * [NativePlayers.turnsPlayerSideways]): the player opens in a screen of its
     * own on top. Plugins can't declare activities, so it borrows the only one in
     * Discord's manifest that handles rotation itself, Play Billing's
     * ProxyBillingActivity (see [patchFullscreenHost]); it rotates without being
     * rebuilt, and Discord underneath stays as it was. False if it can't open.
     */
    private fun enterHostedFullscreen(root: View, activity: Activity): Boolean {
        // Unpatched, the billing screen would crash looking for a purchase to start.
        if (!hostPatched) return false
        val container = players.keys.firstOrNull { players[it]?.root === root } ?: return false
        val state = players[container] ?: return false
        val media = state.media ?: return false
        val oldView = (root as? ViewGroup)?.getChildAt(0) as? PlayerView ?: return false
        endRotated()
        endHosted()
        try {
            activity.startActivity(Intent(activity, ProxyBillingActivity::class.java).putExtra(HOST_EXTRA, true))
            activity.overridePendingTransition(0, 0)
        } catch (e: Throwable) {
            DebugLog.log("host: couldn't open its screen: $e")
            return false
        }
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
            if (current != null && hosted === current) {
                endHosted()
                if (current.provider == HostedProvider.YOUTUBE) {
                    showYoutubeError(current.identity)
                } else {
                    Utils.showToast("Couldn't play this embed", true)
                }
            }
        }
        hosted = session
        session.playerView = oldView
        session.theme = activity.theme
        // Discord underneath stays in portrait, so it isn't rebuilt while the player's
        // screen rotates, nor when it closes with the phone still sideways.
        cancelOrientationUnlock()
        session.source = WeakReference(activity)
        // Not LOCKED: that would take whatever rotation the player's screen left behind.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        DebugLog.log(
            "host: enter from ${DebugLog.name(activity)}, video ${media.width}x${media.height} ${media.quality}p, " +
                "playing ${NativePlayers.isPlaying(state.player)}",
        )
        return true
    }

    private fun isHost(activity: Activity): Boolean =
        activity is ProxyBillingActivity && activity.intent?.getBooleanExtra(HOST_EXTRA, false) == true

    /**
     * Turns ProxyBillingActivity into the fullscreen player's screen when it's
     * opened by [enterHostedFullscreen]. Without a saved state its onCreate would
     * start a purchase flow from extras that aren't there, so it gets an empty one
     * and only sets itself up; the player is shown after it.
     */
    private fun patchFullscreenHost() {
        val onCreate = ProxyBillingActivity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        patcher.patch(onCreate, PreHook { frame ->
            if (isHost(frame.thisObject as Activity) && frame.args[0] == null) frame.args[0] = Bundle()
        })
        patcher.patch(onCreate, Hook { frame ->
            val activity = frame.thisObject as Activity
            if (!isHost(activity)) return@Hook
            val session = hosted
            if (session == null) {
                DebugLog.log("host: opened without a player, closing")
                activity.finish()
                return@Hook
            }
            try {
                showHosted(session, activity)
            } catch (e: Throwable) {
                DebugLog.log("host: couldn't show the player: ${DebugLog.causes(e)}")
                // Closing sends the player back to its embed; fullscreen turns it sideways from now on.
                hostPatched = false
                session.activity = activity
                closeHost(activity)
            }
        })
    }

    /** Shows [session]'s player in the host screen [activity], held in landscape. */
    @Suppress("DEPRECATION")
    private fun showHosted(session: RotatedSession, activity: Activity) {
        // The billing screen is see-through, which would keep Discord underneath visible,
        // and so rotated (rebuilt) along with it. As an opaque screen Discord just stops.
        // (Android 8.0 also refuses an orientation from a see-through screen.)
        val opaque = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.setTranslucent(false)
            } else {
                Activity::class.java.getDeclaredMethod("convertFromTranslucent")
                    .apply { isAccessible = true }
                    .invoke(activity)
                true
            }
        } catch (e: Throwable) {
            false
        }
        activity.window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        activity.requestedOrientation = RotatedSession.LANDSCAPE
        // Its theme isn't Discord's, and the player's controls need Discord's styles and
        // attributes, so its views get the exact theme of the screen fullscreen came from.
        val themeId = Utils.getResId("AppTheme_Dark", "style")
        val context = ContextThemeWrapper(activity, themeId)
        session.theme?.let { context.theme.setTo(it) }
        val overlay = FullscreenOverlay(context) {
            DebugLog.log("host: back pressed")
            closeHost(activity)
        }
        val view = NativePlayers.videoView(context)
        overlay.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        traceRotatedView(session, view, overlay)
        session.playerView?.setPlayer(null)
        NativePlayers.bind(session.player, view, null)
        session.playerView = view
        session.activity = activity
        session.overlay = overlay
        addDownloadButton(view, session.title, audioOnly = false) { session.media.uri }
        if (session.provider == HostedProvider.YOUTUBE) addPlaybackControls(view, session.player, session.identity)
        addBarButton(view, "exo_icon_fullscreen_exit", "Exit fullscreen") {
            DebugLog.log("host: exit button tapped")
            closeHost(activity)
        }
        activity.setContentView(overlay)
        hideSystemBars(activity, activity.window.decorView, 0)
        overlay.requestFocus()
        DebugLog.log("host: shown in ${DebugLog.name(activity)}, opaque $opaque")
    }

    private fun closeHost(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(0, 0)
    }

    /** The host screen is closing: the paused player goes back to its embed. */
    private fun hostClosed(activity: Activity) {
        val session = hosted ?: return
        if (session.activity !== activity) return
        hosted = null
        session.wasPlaying = NativePlayers.isPlaying(session.player)
        DebugLog.log("host: closed, was playing ${session.wasPlaying}, row ${session.rowKey}")
        NativePlayers.pause(session.player)
        session.playerView?.setPlayer(null)
        session.playerView = null
        session.overlay = null
        session.activity = null
        errorCallbacks.remove(session.player.g)
        unlockOrientationWhenUpright(session)
        sendBack(session)
    }

    /**
     * Gives Discord's screen its orientation back once the phone is held upright,
     * so it doesn't rotate (and rebuild) the moment fullscreen closes sideways.
     * At once if the phone can't tell how it's held.
     */
    private fun unlockOrientationWhenUpright(session: RotatedSession) {
        val source = session.source?.get() ?: return
        session.source = null
        val restore = Runnable {
            if (!source.isDestroyed) source.requestedOrientation = session.previousOrientation
        }
        val listener = object : OrientationEventListener(source) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (orientation > UPRIGHT_DEGREES && orientation < 360 - UPRIGHT_DEGREES) return
                DebugLog.log("host: phone upright, Discord can rotate again")
                cancelOrientationUnlock()
                restore.run()
            }
        }
        cancelOrientationUnlock()
        if (!listener.canDetectOrientation()) {
            restore.run()
            return
        }
        orientationUnlock = listener to restore
        listener.enable()
    }

    /** Stops waiting for the phone to be upright; with [restore], gives the orientation back now. */
    private fun cancelOrientationUnlock(restore: Boolean = false) {
        val (listener, restoreOrientation) = orientationUnlock ?: return
        orientationUnlock = null
        listener.disable()
        if (restore) restoreOrientation.run()
    }

    /** Closes any host screen and releases its player. */
    private fun endHosted() {
        val session = hosted ?: return
        hosted = null
        DebugLog.log("host: ended, released")
        session.source?.get()?.let { if (!it.isDestroyed) it.requestedOrientation = session.previousOrientation }
        session.source = null
        errorCallbacks.remove(session.player.g)
        session.playerView?.setPlayer(null)
        session.playerView = null
        session.activity?.let(::closeHost)
        session.activity = null
        NativePlayers.release(session.player)
    }

    /** Puts a player back from a rotated fullscreen into [container], its embed, if it's waiting for it. */
    private fun returnInto(container: ViewGroup) {
        val session = returning ?: return
        if (!container.isAttachedToWindow || embedRowKeys[container] != session.rowKey) return
        if (players[container] != null) {
            DebugLog.log("rotate: return skipped, embed already has a player")
            return
        }
        returning = null
        val attached = attachPlayer(
            container,
            session.identity,
            session.media,
            session.provider,
            session.title,
            existing = session.player,
        )
        DebugLog.log("rotate: back in embed $attached, resume ${session.wasPlaying}")
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
            DebugLog.log("rotate: ended, released")
            hideRotated(session, restoreOrientation = true)
            errorCallbacks.remove(session.player.g)
            NativePlayers.release(session.player)
        }
        returning?.let { session ->
            returning = null
            DebugLog.log("rotate: returning player released")
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
            if (rotated != null || returning != null) DebugLog.log("activity created ${DebugLog.name(activity)}")
            val session = rotated ?: return
            if (session.activity == null && activity is AppActivity) activity.requestedOrientation = RotatedSession.LANDSCAPE
        }

        override fun onActivityResumed(activity: Activity) {
            if (rotated != null || returning != null) DebugLog.log("activity resumed ${DebugLog.name(activity)}")
            // Back from fullscreen without a rebuild, the embed rows are already there.
            if (returning != null && activity is AppActivity) Utils.mainThread.post(::tryReturn)
            val session = rotated ?: return
            if (session.activity == null && activity is AppActivity) showRotated(session, activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            val host = hosted
            if (host != null && host.activity === activity) {
                if (activity.isChangingConfigurations) {
                    // Rebuilt for some other change; the new one shows the player again.
                    DebugLog.log("host: rebuilt")
                    host.activity = null
                    host.overlay = null
                } else {
                    hostClosed(activity)
                }
            }
            if (rotated != null || returning != null) {
                DebugLog.log(
                    "activity destroyed ${DebugLog.name(activity)}, changing config " +
                        "${activity.isChangingConfigurations}, session in ${DebugLog.name(rotated?.activity)}",
                )
            }
            val session = rotated ?: return
            if (session.activity !== activity) return
            session.activity = null
            session.overlay = null
            // Closed for good rather than rebuilt for the rotation.
            if (!activity.isChangingConfigurations) endRotated()
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {
            if (rotated != null) DebugLog.log("activity paused ${DebugLog.name(activity)}")
            // Closing: return the player now rather than once it's destroyed, after Discord resumes.
            if (activity.isFinishing && hosted?.activity === activity) hostClosed(activity)
        }
        override fun onActivityStopped(activity: Activity) {
            // Rebuilt (rotation): the player carries on in the new one.
            if (!activity.isChangingConfigurations) pausePlayersIn(activity)
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    /**
     * Pauses every player shown in [activity] once it's no longer visible: another
     * screen opened over it (settings, a profile, a link in the browser) or the app
     * went to the background. The chat rows stay attached then, so the scroll and
     * detach checks never see them leave. Like scrolling away, it isn't resumed.
     */
    private fun pausePlayersIn(activity: Activity) {
        val decor = activity.window?.peekDecorView() ?: return
        for (state in players.values.toList()) {
            val root = state.root
            if (root.isAttachedToWindow && root.rootView === decor && NativePlayers.isPlaying(state.player)) {
                DebugLog.log("embed screen stopped: paused")
                NativePlayers.pause(state.player)
            }
        }
        for (session in listOfNotNull(rotated, hosted)) {
            if (session.activity === activity && NativePlayers.isPlaying(session.player)) {
                DebugLog.log("fullscreen screen stopped: paused")
                NativePlayers.pause(session.player)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun exitFullscreen(root: View) {
        val state = fullscreenPlayers.remove(root) ?: return
        val activity = state.activity
        DebugLog.log("fullscreen: exit")
        state.orientationListener?.disable()
        state.overlay.removeView(root)
        root.animate().cancel()
        root.rotation = 0f
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
    /**
     * A single-choice list. Uses Aliucord's SelectDialog, except in the borrowed
     * fullscreen screen (ProxyBillingActivity), a plain Activity without fragments,
     * where a dialog would open on Discord's screen behind it instead: there it's
     * Discord's own selector dialog layout drawn over the player.
     */
    private fun showPicker(view: View, tag: String, title: String, items: Array<String>, onPick: (Int) -> Unit) {
        if (activityFrom(view.context) !is FragmentActivity && showOverlayPicker(view, title, items, onPick)) return
        SelectDialog().apply {
            this.title = title
            this.items = items
            onResultListener = onPick
        }.show(fragmentManagerFor(view.context), tag)
    }

    /**
     * Discord's dialog_simple_selector in the [FullscreenOverlay] around [view]; false when there's none.
     * With [sheet] it sits at the bottom edge like a bottom sheet instead of in the middle.
     */
    private fun showOverlayPicker(
        view: View,
        title: String,
        items: Array<String>,
        onPick: (Int) -> Unit,
        sheet: Boolean = false,
    ): Boolean {
        var parent = view.parent
        while (parent != null && parent !is FullscreenOverlay) parent = parent.parent
        val overlay = parent as? FullscreenOverlay ?: return false
        overlay.findViewWithTag<View>(OVERLAY_PICKER_TAG)?.let { overlay.removeView(it) }
        val layoutId = Utils.getResId("dialog_simple_selector", "layout")
        val itemId = Utils.getResId("dialog_simple_selector_item", "layout")
        if (layoutId <= 0 || itemId <= 0) return false
        val context = view.context
        val inflater = LayoutInflater.from(context)
        val dialog = try {
            inflater.inflate(layoutId, overlay, false) as? LinearLayout
        } catch (e: Throwable) {
            DebugLog.log("picker: couldn't inflate: $e")
            null
        } ?: return false
        val list = dialog.findViewById<View>(id("dialog_selections")) ?: return false
        val scrim = FrameLayout(context).apply {
            tag = OVERLAY_PICKER_TAG
            setBackgroundColor(if (sheet) SHEET_SCRIM else PICKER_SCRIM)
            isClickable = true
        }
        val close = { overlay.removeView(scrim) }
        scrim.setOnClickListener { close() }
        dialog.findViewById<TextView>(id("dialog_title"))?.text = title
        dialog.findViewById<View>(id("dialog_cancel"))?.setOnClickListener { close() }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val ripple = TypedValue()
        val hasRipple = context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        items.forEachIndexed { index, label ->
            val row = try {
                inflater.inflate(itemId, rows, false) as? TextView
            } catch (_: Throwable) {
                null
            } ?: return@forEachIndexed
            row.text = label
            if (hasRipple) row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener {
                close()
                onPick(index)
            }
            rows.addView(row)
        }
        val scroll = MaxHeightScrollView(context, (overlay.height * PICKER_MAX_HEIGHT).toInt().coerceAtLeast(dp(context, 120)))
        scroll.addView(rows)
        val index = dialog.indexOfChild(list)
        dialog.removeView(list)
        dialog.addView(scroll, index, LinearLayout.LayoutParams(MATCH, WRAP))
        dialog.background = GradientDrawable().apply {
            val radius = dp(context, 8).toFloat()
            if (sheet) {
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            } else {
                cornerRadius = radius
            }
            setColor(themedColor(context, "colorBackgroundPrimary", Color.DKGRAY))
        }
        dialog.clipToOutline = true
        // Taps inside the dialog don't reach the scrim, which closes it.
        dialog.isClickable = true
        if (sheet) {
            // A sheet has no cancel button; tapping above it closes it.
            dialog.findViewById<View>(id("dialog_cancel"))?.visibility = View.GONE
            val width = minOf(dp(context, SHEET_MAX_WIDTH_DP), overlay.width)
            scrim.addView(dialog, FrameLayout.LayoutParams(width, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        } else {
            val width = minOf(dp(context, PICKER_MAX_WIDTH_DP), overlay.width - dp(context, 32))
            scrim.addView(dialog, FrameLayout.LayoutParams(width, WRAP, Gravity.CENTER))
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        return true
    }

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
        endHosted()
        cancelOrientationUnlock(restore = true)
        hostPatched = false
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
        twitch?.close()
        EmbedUrls.tikTokResolver?.close()
        downloader?.close()
        downloader = null
        youtube = null
        soundCloud = null
        spotify = null
        twitch = null
        EmbedUrls.tikTokResolver = null
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
        playbackExtras.clear()
        captionLanguage = null
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
        private const val FULLSCREEN_BUTTON_TAG = "PlayEmbedsFullscreen"
        private const val SEEKBAR_ROW_TAG = "PlayEmbedsSeekbarRow"
        private const val TOP_BUTTONS_TAG = "PlayEmbedsTopButtons"
        private const val COMPACT_BAR_TAG = "PlayEmbedsCompactBar"

        /** The compact video controls' background: 40% black, lighter than Discord's 80% bar. */
        private const val CONTROLS_BACKGROUND = 0x66000000

        /** Gap between the top strip's buttons, narrowed down to the minimum on thin players. */
        private const val STRIP_GAP_DP = 16
        private const val MIN_STRIP_GAP_DP = 4
        private const val PICKER_SCRIM = 0x99000000.toInt()
        private const val PICKER_MAX_HEIGHT = 0.5f
        private const val SHEET_SCRIM = 0x40000000
        private const val SHEET_MAX_WIDTH_DP = 480
        private const val PICKER_MAX_WIDTH_DP = 400
        private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private const val CAPTION_TICK_MS = 100L
        private const val CAPTION_BACKGROUND = 0x8C000000.toInt()

        /** Caption text height as a share of the player height, kept between these sizes. */
        private const val CAPTION_SIZE_FRACTION = 0.045f
        private const val CAPTION_MIN_DP = 11
        private const val CAPTION_MAX_DP = 24

        /** The narrowest the seekbar may get in the control bar before it moves to its own row. */
        private const val MIN_SEEKBAR_DP = 64

        /** Children of Discord's exo_player_control_view bar: play, pause, position, seekbar, duration. */
        private const val STOCK_BAR_CHILDREN = 5
        private const val TRACE_DELAY_MS = 2_000L
        private const val SIDEWAYS_DEGREES = 90f
        private const val SIDEWAYS_TURN_MS = 250L

        /** How far (degrees) from upright the phone still counts as held upright. */
        private const val UPRIGHT_DEGREES = 30

        /** Marks the ProxyBillingActivity launch that hosts a fullscreen player. */
        private const val HOST_EXTRA = "com.github.yutaplug.playembeds.FULLSCREEN_HOST"
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
