package com.github.yutaplug.fakedecor

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ChannelUtils
import com.aliucord.utils.GsonUtils
import com.aliucord.wrappers.users.avatarDecorationData
import com.discord.api.sticker.BaseSticker
import com.discord.api.user.AvatarDecoration
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.utilities.apng.ApngUtils
import com.discord.utilities.file.DownloadUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.stickers.StickerUtils
import com.discord.views.sticker.StickerView
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import kotlinx.coroutines.Job
import rx.Emitter
import rx.Observable
import rx.Subscription
import rx.functions.Action1
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin
class FakeDecor : Plugin() {
    private val userDecorations = ConcurrentHashMap<Long, CachedDecoration>()
    private val decorationRequests = ConcurrentHashMap.newKeySet<Long>()
    private val boundViews = Collections.synchronizedMap(WeakHashMap<View, BoundView>())
    private val pendingRenders = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val renderedDecorations = Collections.synchronizedMap(WeakHashMap<View, RenderedDecoration>())
    private val touchOverrides = Collections.synchronizedMap(WeakHashMap<View, TouchOverride>())
    private val stickerPlaceholders = Collections.synchronizedMap(WeakHashMap<ImageView, Drawable?>())
    private val editableHeaders = Collections.synchronizedMap(WeakHashMap<UserProfileHeaderView, Boolean>())
    private val customStickerSkuIds = ConcurrentHashMap<String, Long>()
    private val stickerDownloadLocks = ConcurrentHashMap<Long, Any>()
    private val profileAttachments = WeakHashMap<UserProfileHeaderView, View.OnAttachStateChangeListener>()
    private var accountSubscription: Subscription? = null
    private val localProfileDecorations = WeakHashMap<UserProfileHeaderView, LocalProfileDecoration>()

    // The core sticker is Kotlin-internal; access its public JVM bridge without depending on Kotlin visibility.
    private val avatarStickerClass by lazy {
        Class.forName("com.aliucord.coreplugins.decorations.avatar.AvatarSticker")
    }
    private val stickerDataMethod by lazy { avatarStickerClass.getMethod("getData") }
    private val stickerConstructor by lazy { avatarStickerClass.getConstructor(AvatarDecoration::class.java) }
    private var decorationViewId = 0

    @Volatile private var running = false

    init {
        instance = this
        settingsTab = SettingsTab(FakeDecorSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings, this)
    }

    override fun start(context: Context) {
        running = true
        decorationViewId = try {
            Class
                .forName("com.aliucord.coreplugins.decorations.avatar.AvatarDecoratorKt")
                .getDeclaredMethod("access\$getDecoId\$p")
                .apply { isAccessible = true }
                .invoke(null) as Int
        } catch (_: Throwable) {
            View.generateViewId()
        }
        patcher.patch(
            StickerView::class.java,
            "d",
            arrayOf(BaseSticker::class.java, Int::class.javaObjectType),
            PreHook { param ->
                val view = param.thisObject as StickerView
                if (view.id != decorationViewId) return@PreHook
                // An avatar decoration must leave the underlying avatar visible while loading.
                val placeholder = view.j.d
                if (!stickerPlaceholders.containsKey(placeholder)) {
                    stickerPlaceholders[placeholder] =
                        placeholder.drawable
                }
                placeholder.setImageDrawable(null)
                val next = param.args[0] as? BaseSticker
                if (view.k != null && next != null && view.k.d() != next.d()) {
                    view.m?.b(null)
                    view.m = null
                }
            },
        )
        patcher.patch(
            StickerUtils::class.java,
            "fetchSticker",
            arrayOf(Context::class.java, BaseSticker::class.java),
            PreHook { param ->
                val sticker = param.args[1]
                if (!avatarStickerClass.isInstance(sticker)) return@PreHook
                val data = stickerDataMethod.invoke(sticker) as? AvatarDecoration ?: return@PreHook
                if (data.skuId >= 0) return@PreHook
                val stickerContext = param.args[0] as Context
                // Supply the Decor URL directly; an optimized native URL getter can bypass a late plugin hook.
                param.result = downloadDecorSticker(stickerContext, data)
            },
        )
        patcher.patch(
            StickerUtils::class.java,
            "getCDNAssetUrl",
            arrayOf(BaseSticker::class.java, Int::class.javaObjectType, Boolean::class.javaPrimitiveType!!),
            Hook { param ->
                val sticker = param.args[0]
                val data = if (avatarStickerClass.isInstance(sticker)) {
                    stickerDataMethod.invoke(sticker) as? AvatarDecoration
                } else {
                    null
                }
                if (data != null && data.skuId < 0) param.result = assetUrl(data.asset, true)
            },
        )
        try {
            val decoratorClass = Class.forName("com.aliucord.coreplugins.decorations.avatar.AvatarDecorator")
            patcher.patch(
                decoratorClass.getDeclaredMethod(
                    "onProfileHeaderConfigure",
                    UserProfileHeaderView::class.java,
                    UserProfileHeaderViewModel.ViewState.Loaded::class.java,
                ),
                Hook { param ->
                    val view = param.args[0] as UserProfileHeaderView
                    val state = param.args[1] as UserProfileHeaderViewModel.ViewState.Loaded
                    editableHeaders[view] = state.editable
                    val userId = state.guildMember?.userId?.takeIf { it != 0L } ?: state.user.id
                    val bound = BoundView(userId, original(state.guildMember) ?: original(state.user), state.isMe)
                    synchronized(boundViews) { boundViews.put(view, bound) }
                    renderedDecorations.remove(view)
                    // Run after the core has configured its overlay, so it cannot hide our result afterwards.
                    render(view, bound)
                },
            )
            val method = decoratorClass.getDeclaredMethod(
                "findAndConfigure",
                View::class.java,
                AvatarDecoration::class.java,
            )
            patcher.patch(
                method,
                PreHook { param ->
                    val parent = param.args[0] as? View ?: return@PreHook
                    // Keep our static image in place until the row bind supplies its current user.
                    if (ownsStaticDecoration(parent)) param.result = null
                },
            )
            patcher.patch(
                method,
                Hook { param ->
                    val parent = param.args[0] as? View ?: return@Hook
                    if (ownsStaticDecoration(parent)) return@Hook
                    // Discord may have cleared or replaced the image even when our selected asset is unchanged.
                    renderedDecorations.remove(parent)
                    synchronized(boundViews) {
                        val bound = boundViews[parent]
                        if (bound !=
                            null
                        ) {
                            boundViews[parent] = bound.copy(original = param.args[1] as? AvatarDecoration)
                        }
                        true
                    }
                    postRender(parent)
                },
            )
        } catch (error: Throwable) {
            logger.error("Failed to patch core avatar decoration renderer", error)
        }
        patcher.patch(
            WidgetChannelsListAdapter.ItemChannelPrivate::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChannelListItem::class.java),
            Hook { param ->
                val holder = param.thisObject as WidgetChannelsListAdapter.ItemChannelPrivate
                val channel = (param.args[1] as? ChannelListItemPrivate)?.channel
                val user = channel?.let { ChannelUtils.getDMRecipient(it) }
                scheduleRender(holder.itemView, user?.id ?: 0, original(user))
            },
        )
        patcher.patch(
            ChannelMembersListViewHolderMember::class.java,
            "bind",
            arrayOf(ChannelMembersListAdapter.Item.Member::class.java, Function0::class.java),
            Hook { param ->
                val holder = param.thisObject as ChannelMembersListViewHolderMember
                val member = param.args[0] as? ChannelMembersListAdapter.Item.Member
                val native = member?.let {
                    val guild = it.guildId
                    (if (guild == null) null else original(StoreStream.getGuilds().getMember(guild, it.userId)))
                        ?: original(StoreStream.getUsers().users[it.userId])
                }
                scheduleRender(holder.itemView, member?.userId ?: 0, native)
            },
        )
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { param ->
                val holder = param.thisObject as WidgetChatListAdapterItemMessage
                val entry = param.args[1] as? MessageEntry
                val userId = entry?.author?.userId?.takeIf { it != 0L } ?: entry?.message?.author?.id ?: 0L
                scheduleRender(holder.itemView, userId, original(entry?.author) ?: original(entry?.message?.author))
            },
        )
        patcher.patch(
            UserProfileHeaderView::class.java,
            "updateViewState",
            arrayOf(UserProfileHeaderViewModel.ViewState.Loaded::class.java),
            Hook { param ->
                val view = param.thisObject as UserProfileHeaderView
                val state = param.args[0] as? UserProfileHeaderViewModel.ViewState.Loaded
                editableHeaders[view] = state?.editable == true
                val userId = state?.guildMember?.userId?.takeIf { it != 0L } ?: state?.user?.id ?: 0L
                scheduleRender(view, userId, original(state?.guildMember) ?: original(state?.user), state?.isMe == true)
            },
        )
        accountSubscription = StoreStream.getUsers().observeMe().W(
            object : Action1<MeUser> {
                override fun call(user: MeUser) {
                    if (running) {
                        Utils.mainThread.post {
                            if (running) {
                                // Retry views bound before the current account and its saved selection were ready.
                                synchronized(boundViews) { boundViews.keys.toList() }.forEach(::postRender)
                            }
                        }
                    }
                }
            },
            object : Action1<Throwable> {
                override fun call(error: Throwable) {
                    logger.error("Could not observe the current Decor account", error)
                }
            },
        )
    }

    private fun scheduleRender(parent: View, userId: Long, original: AvatarDecoration?, isMe: Boolean = false) {
        synchronized(boundViews) { boundViews.put(parent, BoundView(userId, original, isMe)) }
        if (parent is UserProfileHeaderView) {
            renderedDecorations.remove(parent)
            postRender(parent)
        } else {
            // Row binds run on the UI thread; finish the update before RecyclerView can draw the row.
            render(parent, BoundView(userId, original))
        }
    }

    private fun ownsStaticDecoration(parent: View): Boolean {
        if (parent is UserProfileHeaderView) return false
        val decoration = parent.findViewById<View>(decorationViewId) as? ImageView ?: return false
        return renderedDecorations[parent]?.view?.get() === decoration
    }

    private fun postRender(parent: View) {
        synchronized(boundViews) {
            if (!running || !boundViews.containsKey(parent) || !pendingRenders.add(parent)) return
            true
        }
        if (!parent.post {
                val bound = synchronized(boundViews) {
                    pendingRenders.remove(parent)
                    boundViews[parent]
                }
                if (running && bound != null) render(parent, bound)
            }
        ) {
            synchronized(boundViews) { pendingRenders.remove(parent) }
        }
    }

    private fun render(parent: View, bound: BoundView) {
        if (bound.userId == 0L) return configureDecoration(parent, null, false)
        if (isPreserveOriginalDecor() && bound.original != null) {
            return configureDecoration(parent, bound.original, false)
        }
        val asset = if (bound.isMe || bound.userId == currentUserId()) {
            accountValue("selectedAssets", "selectedAsset", bound.userId).ifEmpty { null }
        } else {
            val cached = userDecorations[bound.userId]
            if (cached == null || System.currentTimeMillis() - cached.fetchedAt >= FETCH_COOLDOWN) {
                fetchUserDecoration(bound.userId)
            }
            cached?.asset
        }
        val data = asset?.let {
            val normalized = normalizeAsset(it)
            AvatarDecoration(
                normalized,
                customStickerSkuIds.getOrPut(normalized) {
                    customStickerId(normalized)
                },
                null,
            )
        }
        configureDecoration(parent, data, true)
    }

    private fun fetchUserDecoration(userId: Long) {
        if (!decorationRequests.add(userId)) return
        Utils.threadPool.execute {
            try {
                val ids = URLEncoder.encode("[\"$userId\"]", "UTF-8")
                val response = GsonUtils.fromJson(Http.simpleGet("$API_URL/users?ids=$ids"), Map::class.java)
                val asset = normalizeAsset(response?.get(userId.toString())?.toString()).ifEmpty { null }
                if (running) {
                    userDecorations[userId] = CachedDecoration(asset)
                    postRenderForUser(userId)
                }
            } catch (error: Throwable) {
                logger.error("Failed to fetch Decor decoration for $userId", error)
            } finally {
                decorationRequests.remove(userId)
            }
        }
    }

    private fun postRenderForUser(userId: Long) {
        val parents = synchronized(boundViews) {
            boundViews.entries.filter { it.value.userId == userId }.map { it.key }
        }
        parents.forEach(::postRender)
    }

    private fun configureDecoration(parent: View, data: AvatarDecoration?, custom: Boolean) {
        if (running && parent is UserProfileHeaderView) {
            observeProfileAttachment(parent)
            if (!parent.isAttachedToWindow) return
        }
        val decoration = parent.findViewById<View>(decorationViewId) ?: return
        val avatar = if (parent is UserProfileHeaderView) {
            val resource = if (editableHeaders[parent] == true) "large_avatar" else "avatar"
            parent.findViewById<View>(Utils.getResId(resource, "id"))
        } else {
            null
        }
        if (running && parent is UserProfileHeaderView) {
            makeTouchTransparent(decoration, avatar)
            if (custom && data != null) {
                if (decoration is StickerView) releaseSticker(decoration)
                decoration.visibility = View.INVISIBLE
                renderLocalProfileDecoration(parent, decoration, avatar, data)
                return
            }
            removeLocalProfileDecoration(parent)
        }
        if (data == null || normalizeAsset(data.asset).isEmpty()) {
            if (decoration is StickerView) releaseSticker(decoration)
            decoration.visibility = View.INVISIBLE
            renderedDecorations[parent] = RenderedDecoration(null, false, WeakReference(decoration))
            return
        }
        decoration.visibility = View.VISIBLE
        val rendered = renderedDecorations[parent]
        if (rendered != null &&
            rendered.data == data &&
            rendered.custom == custom &&
            rendered.view.get() === decoration
        ) {
            return
        }
        when (decoration) {
            is StickerView -> decoration.d(stickerConstructor.newInstance(data) as BaseSticker, null)

            is ImageView -> IconUtils.setIcon(
                decoration,
                if (custom) {
                    assetUrl(data.asset, false)
                } else {
                    "https://cdn.discordapp.com/avatar-decoration-presets/${data.asset}.png?size=256&passthrough=true"
                },
            )
        }
        if (running && parent is UserProfileHeaderView) makeTouchTransparent(decoration, avatar)
        renderedDecorations[parent] = RenderedDecoration(data, custom, WeakReference(decoration))
    }

    private fun observeProfileAttachment(profile: UserProfileHeaderView) {
        if (profileAttachments.containsKey(profile)) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                // The native image children must finish attaching before the APNG drawable is installed.
                view.post {
                    if (running && view.isAttachedToWindow) {
                        renderedDecorations.remove(view)
                        (view.findViewById<View>(decorationViewId) as? StickerView)?.let(::releaseSticker)
                        postRender(view)
                    }
                }
            }

            override fun onViewDetachedFromWindow(view: View) {
                renderedDecorations.remove(view)
                removeLocalProfileDecoration(view as UserProfileHeaderView)
                (view.findViewById<View>(decorationViewId) as? StickerView)?.let(::releaseSticker)
            }
        }
        profileAttachments[profile] = listener
        profile.addOnAttachStateChangeListener(listener)
    }

    private fun renderLocalProfileDecoration(
        profile: UserProfileHeaderView,
        nativeDecoration: View,
        avatar: View?,
        data: AvatarDecoration,
    ) {
        val previous = localProfileDecorations[profile]
        if (previous?.data == data && !previous.failed && previous.image.get()?.parent != null) return
        removeLocalProfileDecoration(profile)
        val container = nativeDecoration.parent as? ViewGroup ?: return
        val image = ImageView(profile.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        container.addView(image, nativeDecoration.layoutParams)
        makeTouchTransparent(image, avatar)
        val state = LocalProfileDecoration(data, WeakReference(image))
        localProfileDecorations[profile] = state
        state.subscription = downloadDecorSticker(profile.context.applicationContext, data).W(
            object : Action1<DownloadUtils.DownloadState> {
                override fun call(result: DownloadUtils.DownloadState) {
                    if (result is DownloadUtils.DownloadState.Failure) {
                        state.failed = true
                        return
                    }
                    if (result !is DownloadUtils.DownloadState.Completed) return
                    val target = state.image.get() ?: return
                    // Display a decoded first frame even if the client's APNG animation cannot start.
                    val firstFrame = BitmapFactory.decodeFile(result.file.absolutePath)
                    target.post {
                        if (running && state.active && target.isAttachedToWindow) {
                            if (firstFrame != null) target.setImageBitmap(firstFrame)
                            val animate = !AccessibilityUtils.INSTANCE.isReducedMotionEnabled &&
                                StoreStream.getUserSettings().stickerAnimationSettings == 0
                            if (animate) {
                                state.job = ApngUtils.INSTANCE.renderApngFromFile(result.file, target, null, null, true)
                            }
                        }
                    }
                }
            },
            object : Action1<Throwable> {
                override fun call(error: Throwable) {
                    logger.error("Could not render local profile decoration", error)
                }
            },
        )
    }

    private fun removeLocalProfileDecoration(profile: UserProfileHeaderView) {
        val state = localProfileDecorations.remove(profile) ?: return
        state.active = false
        state.subscription?.unsubscribe()
        state.job?.b(null)
        state.image.get()?.let { image ->
            (image.drawable as? Animatable)?.stop()
            image.setImageDrawable(null)
            (image.parent as? ViewGroup)?.removeView(image)
            touchOverrides.remove(image)
        }
    }

    private fun makeTouchTransparent(view: View, avatar: View?) {
        if (avatar != null) {
            try {
                val current = touchListener(view)
                val previous = touchOverrides[view]
                val original = if (previous != null && current === previous.installed) previous.original else current
                val target = WeakReference(avatar)
                val installed = View.OnTouchListener { _, event ->
                    val clickableAvatar = target.get()
                    if (clickableAvatar == null) {
                        false
                    } else {
                        if (event.actionMasked == MotionEvent.ACTION_UP) clickableAvatar.performClick()
                        true
                    }
                }
                touchOverrides[view] = TouchOverride(original, installed)
                view.setOnTouchListener(installed)
            } catch (error: ReflectiveOperationException) {
                logger.error("Could not preserve the decoration touch listener", error)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) makeTouchTransparent(view.getChildAt(i), avatar)
        }
    }

    private fun touchListener(view: View): View.OnTouchListener? {
        val info = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(view)
            ?: return null
        return info.javaClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }.get(info)
            as? View.OnTouchListener
    }

    private fun releaseSticker(view: StickerView) {
        view.l?.unsubscribe()
        view.l = null
        view.m?.b(null)
        view.m = null
        view.k = null
        view.j.b.controller = null
        view.j.c.setImageDrawable(null)
    }

    private fun downloadDecorSticker(
        context: Context,
        data: AvatarDecoration,
    ): Observable<DownloadUtils.DownloadState> = Observable.o({ emitter ->
        Utils.threadPool.execute {
            try {
                val directory = File(context.cacheDir, "fakedecor")
                val cached = File(directory, "${data.skuId}.png")
                // Publish only complete files; simultaneous profile views must not decode a partial download.
                val downloadedFile = synchronized(stickerDownloadLocks.getOrPut(data.skuId) { Any() }) {
                    if (!cached.isFile || cached.length() == 0L) {
                        check(directory.isDirectory || directory.mkdirs()) { "Could not create Decor cache" }
                        val temporary = File.createTempFile("decoration-", ".png", directory)
                        try {
                            Http.simpleDownload(assetUrl(data.asset, true), temporary)
                            check(temporary.length() > 0L) { "Empty Decor image" }
                            check(temporary.renameTo(cached)) { "Could not cache Decor image" }
                        } finally {
                            temporary.delete()
                        }
                    }
                    cached
                }
                emitter.onNext(DownloadUtils.DownloadState.Completed(downloadedFile))
                emitter.onCompleted()
            } catch (error: Exception) {
                logger.error("Failed to download Decor profile decoration", error)
                emitter.onNext(DownloadUtils.DownloadState.Failure(error))
                emitter.onCompleted()
            }
        }
    }, Emitter.BackpressureMode.valueOf("BUFFER"))

    private fun original(user: User?): AvatarDecoration? = try {
        user?.avatarDecorationData
    } catch (_: Throwable) {
        null
    }

    private fun original(user: com.discord.api.user.User?): AvatarDecoration? = try {
        user?.avatarDecorationData
    } catch (_: Throwable) {
        null
    }

    private fun original(member: GuildMember?): AvatarDecoration? = try {
        member?.avatarDecorationData
    } catch (_: Throwable) {
        null
    }

    fun isPreserveOriginalDecor() = settings.getBool("preserveOriginalDecor", false)

    fun setPreserveOriginalDecor(preserve: Boolean) {
        settings.setBool("preserveOriginalDecor", preserve)
        synchronized(boundViews) { boundViews.keys.toList() }.forEach(::postRender)
    }

    @Synchronized fun setSelectedAsset(asset: String?) {
        val id = currentUserId()
        if (id == 0L) {
            Utils.showToast("Discord account is not ready yet")
            return
        }
        val normalized = normalizeAsset(asset)
        saveAccountValue("selectedAssets", id, normalized)
        userDecorations[id] = CachedDecoration(normalized.ifEmpty { null })
        postRenderForUser(id)
    }

    @Synchronized fun getSelectedAsset() = accountValue("selectedAssets", "selectedAsset")

    @Synchronized private fun getApiToken() = accountValue("apiTokens", "apiToken")

    @Synchronized private fun setApiToken(token: String?) {
        val id = currentUserId()
        if (id == 0L) return
        saveAccountValue("apiTokens", id, token?.trim().orEmpty())
        settings.remove("apiToken")
    }

    private fun accountValue(key: String, legacyKey: String, id: Long = currentUserId()): String {
        if (id == 0L) return ""
        readStringMap(key)[id.toString()]?.let { return it }
        val legacy = settings.getString(legacyKey, "").trim()
        if (legacy.isEmpty()) return ""
        val value = if (key == "selectedAssets") normalizeAsset(legacy) else legacy
        saveAccountValue(key, id, value)
        settings.remove(legacyKey)
        return value
    }

    private fun saveAccountValue(key: String, id: Long, value: String) {
        val values = readStringMap(key)
        if (value.isEmpty()) values.remove(id.toString()) else values[id.toString()] = value
        settings.setString(key, GsonUtils.toJson(values))
    }

    private fun readStringMap(key: String): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        try {
            GsonUtils.fromJson(settings.getString(key, "{}"), Map::class.java)?.forEach { (key, value) ->
                if (key != null && value != null) result[key.toString()] = value.toString()
            }
        } catch (_: Throwable) {
        }
        return result
    }

    fun isAuthorized() = getApiToken().isNotEmpty()

    fun disconnectDecor() {
        setApiToken(null)
        Utils.showToast("Decor authorization removed")
    }

    fun authorizeDecor(context: Context) {
        val url = Uri
            .parse("https://discord.com/oauth2/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", "1096966363416899624")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "$API_URL/authorize")
            .appendQueryParameter("scope", "identify")
            .appendQueryParameter("permissions", "0")
            .build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url))
            Utils.showToast("Authorize in the browser, copy the returned token, then tap Finish authorization")
        } catch (error: Throwable) {
            logger.error("Failed to open Decor authorization", error)
            Utils.showToast("Could not open Decor authorization")
        }
    }

    fun finishBrowserAuthorization(context: Context, page: FakeDecorSettings) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val token = if (clip != null &&
            clip.itemCount > 0
        ) {
            clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
        } else {
            null
        }
        if (token.isNullOrEmpty()) {
            Utils.showToast("Copy the returned Decor token first")
            return
        }
        background("Could not authorize Decor") {
            request("/users/@me/decorations", token = token) { it.execute().also(::checkResponse) }
            setApiToken(token)
            Utils.mainThread.post {
                if (page.isSettingsViewActive) page.refreshAuthState()
                Utils.showToast("Decor authorization saved")
            }
        }
    }

    fun refreshOwnDecoration() {
        val token = requireToken() ?: return
        background("Could not sync Decor decoration") {
            val asset = request("/users/@me/decoration", token = token) {
                decorationAsset(GsonUtils.fromJson(it.execute().also(::checkResponse).text(), Map::class.java))
            }
            setSelectedAsset(asset)
            toast(if (asset.isEmpty()) "Decor decoration removed" else "Decor decoration synced")
        }
    }

    fun applyToDecorService() {
        val token = requireToken() ?: return
        val selected = getSelectedAsset()
        background("Could not sync Decor decoration") {
            request("/users/@me/decoration", "PUT", token) {
                it
                    .executeWithMultipartForm(
                        mapOf<String, Any>("hash" to if (selected.isEmpty()) "null" else apiHash(selected)),
                    ).also(::checkResponse)
            }
            toast("Decor decoration synced")
        }
    }

    fun uploadDecoration(context: Context, uri: Uri) {
        val token = requireToken() ?: return
        background("Could not upload custom decoration") {
            val uploaded = context.contentResolver.openInputStream(uri).use { image ->
                checkNotNull(image) { "Could not open selected image" }
                val decoration = request("/users/@me/decoration", "PUT", token) {
                    GsonUtils.fromJson(
                        it
                            .executeWithMultipartForm(
                                mapOf<String, Any>(
                                    "image" to image,
                                    "alt" to "Custom mobile decoration",
                                ),
                            ).also(::checkResponse)
                            .text(),
                        Map::class.java,
                    )
                }
                val asset = decorationAsset(decoration)
                val pending = decoration?.get("reviewed") == false
                if (!pending && asset.isNotEmpty()) setSelectedAsset(asset)
                toast(if (pending) "Decoration submitted for review" else "Custom decoration uploaded and applied")
                true
            }
            check(uploaded)
        }
    }

    fun fetchPresets(page: FakeDecorSettings) {
        background("Could not load Decor presets") {
            val presets = GsonUtils.fromJson(Http.simpleGet("$API_URL/decorations/presets"), List::class.java).orEmpty()
            Utils.mainThread.post { if (page.isSettingsViewActive) page.showPresets(presets) }
        }
    }

    fun fetchOwnDecorations(page: FakeDecorSettings) {
        val token = requireToken() ?: return
        background("Could not load your decorations") {
            val decorations = request("/users/@me/decorations", token = token) {
                GsonUtils.fromJson(it.execute().also(::checkResponse).text(), List::class.java).orEmpty()
            }
            Utils.mainThread.post { if (page.isSettingsViewActive) page.showOwnDecorations(decorations) }
        }
    }

    fun deleteDecoration(hash: String) {
        val token = requireToken() ?: return
        background("Could not delete decoration") {
            request("/decorations/${URLEncoder.encode(hash, "UTF-8")}", "DELETE", token) {
                it.execute().also(::checkResponse)
            }
            if (apiHash(getSelectedAsset()) == hash) setSelectedAsset("")
            toast("Decoration deleted")
        }
    }

    private fun requireToken(): String? = getApiToken().ifEmpty {
        Utils.showToast("Authorize Decor first")
        return null
    }

    private fun <T> request(path: String, method: String = "GET", token: String, action: (Http.Request) -> T): T =
        Http.Request("$API_URL$path", method).use {
            it.setHeader("Authorization", "Bearer $token")
            action(it)
        }

    private fun checkResponse(response: Http.Response) {
        check(response.ok()) { "HTTP ${response.statusCode}" }
    }

    private fun background(failure: String, action: Runnable) {
        Utils.threadPool.execute {
            try {
                action.run()
            } catch (error: Throwable) {
                logger.error(failure, error)
                toast(failure)
            }
        }
    }

    private fun toast(message: String) {
        Utils.mainThread.post { Utils.showToast(message) }
    }

    override fun stop(context: Context) {
        running = false
        accountSubscription?.unsubscribe()
        accountSubscription = null
        for ((profile, listener) in profileAttachments) profile.removeOnAttachStateChangeListener(listener)
        profileAttachments.clear()
        localProfileDecorations.keys.toList().forEach(::removeLocalProfileDecoration)
        patcher.unpatchAll()
        synchronized(stickerPlaceholders) {
            for ((view, drawable) in stickerPlaceholders) view.setImageDrawable(drawable)
            stickerPlaceholders.clear()
            true
        }
        synchronized(touchOverrides) {
            for ((view, override) in touchOverrides) {
                try {
                    if (touchListener(view) === override.installed) view.setOnTouchListener(override.original)
                } catch (error: ReflectiveOperationException) {
                    logger.error("Could not restore the decoration touch listener", error)
                }
            }
            touchOverrides.clear()
            true
        }
        val restore = synchronized(boundViews) { boundViews.entries.map { it.key to it.value.original } }
        for ((parent, original) in restore) {
            renderedDecorations.remove(parent)
            (parent.findViewById<View>(decorationViewId) as? StickerView)?.let(::releaseSticker)
            configureDecoration(parent, original, false)
        }
        userDecorations.clear()
        decorationRequests.clear()
        synchronized(boundViews) {
            boundViews.clear()
            pendingRenders.clear()
            true
        }
        renderedDecorations.clear()
        editableHeaders.clear()
        customStickerSkuIds.clear()
    }

    private data class BoundView(
        val userId: Long,
        val original: AvatarDecoration?,
        val isMe: Boolean = false,
    )

    private class LocalProfileDecoration(val data: AvatarDecoration, val image: WeakReference<ImageView>) {
        @Volatile var active = true

        @Volatile var failed = false
        var subscription: Subscription? = null
        var job: Job? = null
    }

    private data class TouchOverride(val original: View.OnTouchListener?, val installed: View.OnTouchListener)

    private data class RenderedDecoration(
        val data: AvatarDecoration?,
        val custom: Boolean,
        val view: WeakReference<View>,
    )

    private data class CachedDecoration(val asset: String?, val fetchedAt: Long = System.currentTimeMillis())

    companion object {
        // Lets the picker fragment reach the plugin after the settings page that opened it is gone.
        @Volatile var instance: FakeDecor? = null

        private const val API_URL = "https://decor.fieryflames.dev/api"
        private const val CDN_URL = "https://ugc.decor.fieryflames.dev"
        private const val FETCH_COOLDOWN = 4L * 60 * 60 * 1000

        // Discord caches animated sticker files by ID. Sequential IDs reuse another asset's file after a restart.
        internal fun customStickerId(asset: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(assetUrl(asset, true).toByteArray(Charsets.UTF_8))
            return ByteBuffer.wrap(digest).long or Long.MIN_VALUE
        }

        internal fun decorationAsset(decoration: Map<*, *>?): String {
            val hash = decoration?.get("hash")?.toString()?.takeUnless { it == "null" } ?: return ""
            return normalizeAsset((if (decoration["animated"] == true) "a_" else "") + hash)
        }

        internal fun normalizeAsset(value: String?): String {
            val asset = value?.trim().orEmpty()
            if (asset.isEmpty() || asset == "null") return ""
            if (asset.startsWith("http://") || asset.startsWith("https://")) return asset
            return asset.substringBefore('?').substringAfterLast('/').removeSuffix(".png")
        }

        private fun apiHash(asset: String) = normalizeAsset(asset).removePrefix("a_")

        internal fun assetUrl(asset: String, animate: Boolean): String {
            val normalized = normalizeAsset(asset)
            if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                val uri = Uri.parse(normalized)
                return if (uri.path.orEmpty().endsWith(".png")) {
                    normalized
                } else {
                    uri.buildUpon().path(uri.path.orEmpty() + ".png").build().toString()
                }
            }
            return "$CDN_URL/${if (animate) normalized else normalized.removePrefix("a_")}.png"
        }

        private fun currentUserId() = try {
            StoreStream.getUsers().me?.id ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
