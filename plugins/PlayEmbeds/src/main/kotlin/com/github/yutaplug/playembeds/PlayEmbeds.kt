package com.github.yutaplug.playembeds

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.message.embed.MessageEmbed
import com.discord.player.AppMediaPlayer
import com.discord.player.MediaSource
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import com.google.android.exoplayer2.PlaybackException
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

    private var youtube: YoutubeResolver? = null
    private var soundCloud: SoundCloudResolver? = null
    private var spotify: SpotifyResolver? = null

    override fun start(context: Context) {
        EmbedUrls.fxVideoResolver = FxVideoResolver()
        youtube = YoutubeResolver(File(context.cacheDir, "playembeds-youtube"))
        soundCloud = SoundCloudResolver(File(context.cacheDir, "playembeds"))
        spotify = SpotifyResolver()
        NativePlayers.init()
        patchEmbedRows()
        patchNativeHostedLaunches()
        patchPlayerErrors()
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
                    embedRowKeys[container] = entry.message.id to entry.embedIndex
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
                if (handler.javaClass.name == "com.discord.widgets.search.results.WidgetSearchResults\$SearchResultAdapterEventHandler") {
                    embedContainers(item.itemView).forEach { container ->
                        searchEmbedClicks[container] = SearchEmbedClick(handler, entry)
                    }
                    return@Hook
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
                if (handleSearchEmbedClick(container)) {
                    frame.setResult(null)
                    return@PreHook
                }
                val embed = container?.let { hostedEmbeds[it] }
                if (container != null && embed != null && EmbedUrls.genericVideoUrl(embed) != null) {
                    if (playInlineVideo(container, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val url = mediaSourceUrl(frame.thisObject) ?: return@PreHook
                if (EmbedUrls.isMediaLink(url)) return@PreHook
                val hosted = EmbedUrls.hostedEmbed(url) ?: return@PreHook
                if (!playHosted(view, hosted)) return@PreHook
                frame.setResult(null)
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
                if (handleSearchEmbedClick(container)) {
                    frame.setResult(null)
                    return@PreHook
                }
                if (EmbedUrls.genericVideoUrl(embed) != null) {
                    val target = container ?: return@PreHook
                    if (playInlineVideo(target, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val hosted = EmbedUrls.hostedEmbed(embed) ?: return@PreHook
                if (!playHosted(source, hosted)) return@PreHook
                frame.setResult(null)
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
        showLoading(container)
        resolver.resolve(embed.url) { media ->
            val target = containerRef.get() ?: return@resolve
            hideLoading(target)
            if (!target.isAttachedToWindow || embedRowKeys[target] != rowKey) return@resolve
            if (media == null || !attachPlayer(target, embed.url, media, embed.provider)) {
                openExternally(target.context, embed.url)
            }
        }
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
        val attached = attachPlayer(container, videoUrl, ResolvedMedia(videoUrl, width, height, false), null)
        if (attached) hostedEmbeds[container] = embed
        return attached
    }

    private fun attachPlayer(
        container: ViewGroup,
        identity: String,
        media: ResolvedMedia,
        provider: HostedProvider?,
    ): Boolean {
        removeSpotifyPlayButton(container)
        val player = NativePlayers.create(container.context) ?: return false
        val state = when {
            media.audioOnly -> attachAudioPlayer(container, player, identity, media)
            // The whole card is replaced only when there's no large preview to play in.
            provider == HostedProvider.YOUTUBE ->
                attachPreviewVideo(container, player, identity, media)
                    ?: attachWholeCardVideo(container, player, identity, media)
            else -> attachPreviewVideo(container, player, identity, media)
        }
        if (state == null) {
            NativePlayers.release(player)
            return false
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
                Utils.showToast("Couldn't play this embed", true)
                if (provider != null) openExternally(target.context, identity)
            }
        }
        // Like Discord's own inline media, stop once the row leaves the screen
        // (or the chat closes). The delay ignores brief detaches during relayout.
        state.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}

            override fun onViewDetachedFromWindow(view: View) {
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

    /** YouTube replaces the whole embed card, as its preview is only a thumbnail. */
    private fun attachWholeCardVideo(
        container: ViewGroup,
        player: AppMediaPlayer,
        identity: String,
        media: ResolvedMedia,
    ): NativePlayerState? {
        val replacement = wrapEmbedForHostedPlayer(container) ?: return null
        val playerView = NativePlayers.videoView(container.context)
        val root = TouchGuardLayout(container.context).apply {
            addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addFullscreenButton(playerView, root)
        replacement.wrapper.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        root.bringToFront()
        NativePlayers.play(player, media.uri, playerView, null)
        return NativePlayerState(
            player,
            root,
            replacement.wrapper,
            emptyList(),
            forceGone = false,
            url = identity,
            extraViews = emptyList(),
            previousMinimumWidth = replacement.wrapper.minimumWidth,
            replacement = replacement,
        )
    }

    /** Swap the image/inline-media preview for a video surface of the same size. */
    private fun attachPreviewVideo(
        container: ViewGroup,
        player: AppMediaPlayer,
        identity: String,
        media: ResolvedMedia,
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
        addFullscreenButton(playerView, root)
        previousVisibility.forEach { (child, _) -> child.visibility = View.GONE }
        parent.addView(root, FrameLayout.LayoutParams(width, height))
        root.bringToFront()
        NativePlayers.play(player, media.uri, playerView, null)
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
            NativePlayers.play(player, media.uri, hiddenView, controlBar)
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
        NativePlayers.play(player, media.uri, hiddenView, controlBar)
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
        val state = players.remove(container) ?: return
        state.root.tag = null
        exitFullscreen(state.root)
        errorCallbacks.remove(state.player.g)
        NativePlayers.release(state.player)
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
        val params = when {
            parent is ConstraintLayout && anchor != null && anchor.id != View.NO_ID ->
                ConstraintLayout.LayoutParams(size, size).apply {
                    startToStart = anchor.id
                    endToEnd = anchor.id
                    topToTop = anchor.id
                    bottomToBottom = anchor.id
                }
            parent is FrameLayout -> FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            else -> ViewGroup.LayoutParams(size, size)
        }
        parent.addView(indicator, params)
        indicator.bringToFront()
        loadingIndicators[container] = indicator
    }

    private fun hideLoading(container: ViewGroup) {
        val indicator = loadingIndicators.remove(container) ?: return
        (indicator.parent as? ViewGroup)?.removeView(indicator)
    }

    /** The player is in its embed, or temporarily moved into the fullscreen overlay. */
    private fun isShowing(state: NativePlayerState): Boolean =
        state.root.parent === state.parent || fullscreenPlayers.containsKey(state.root)

    /** Adds a fullscreen toggle to the end of Discord's control bar (after the duration). */
    private fun addFullscreenButton(playerView: View, root: View) {
        val duration = playerView.findViewById<View>(id("exo_duration")) ?: return
        val bar = duration.parent as? LinearLayout ?: return
        val context = playerView.context
        val styleId = Utils.getResId("ExoMediaButton", "style")
        val button = if (styleId > 0) ImageButton(context, null, 0, styleId) else ImageButton(context)
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        button.setPadding(0, 0, 0, 0)
        setFullscreenIcon(button, fullscreen = false)
        button.setOnClickListener {
            if (fullscreenPlayers.containsKey(root)) exitFullscreen(root) else enterFullscreen(root, button)
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
     * player, so the current orientation is locked while fullscreen.
     */
    @Suppress("DEPRECATION")
    private fun enterFullscreen(root: View, button: ImageButton) {
        if (fullscreenPlayers.containsKey(root)) return
        val activity = activityFrom(root.context) ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
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
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        decor.systemUiVisibility = state.previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        overlay.requestFocus()
        setFullscreenIcon(button, fullscreen = true)
    }

    @Suppress("DEPRECATION")
    private fun exitFullscreen(root: View) {
        val state = fullscreenPlayers.remove(root) ?: return
        val activity = state.activity
        state.overlay.removeView(root)
        (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
        activity.window.decorView.systemUiVisibility = state.previousSystemUiVisibility
        if (!state.wasFullscreen) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
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
        ArrayList(fullscreenPlayers.keys).forEach(::exitFullscreen)
        ArrayList(players.keys).forEach(::removeNativePlayer)
        ArrayList(loadingIndicators.keys).forEach(::hideLoading)
        ArrayList(spotifyPlayButtons.keys).forEach(::removeSpotifyPlayButton)
        EmbedUrls.fxVideoResolver?.close()
        EmbedUrls.fxVideoResolver = null
        youtube?.close()
        soundCloud?.close()
        spotify?.close()
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
        patcher.unpatchAll()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DETACH_RELEASE_DELAY_MS = 1_000L
    }
}
