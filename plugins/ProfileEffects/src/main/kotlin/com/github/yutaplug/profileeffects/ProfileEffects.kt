package com.github.yutaplug.profileeffects

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.app.AppBottomSheet
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.api.channel.ChannelUtils
import com.discord.databinding.WidgetChannelsListItemChannelPrivateBinding
import com.discord.databinding.WidgetFriendsListAdapterItemFriendBinding
import com.discord.databinding.WidgetFriendsListAdapterItemPendingBinding
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.stores.StoreMessageReplies
import com.discord.models.member.GuildMember as ModelGuildMember
import com.discord.models.user.User as ModelUser
import com.discord.widgets.friends.FriendsListViewModel
import com.discord.widgets.friends.WidgetFriendsListAdapter
import com.discord.databinding.WidgetHomeBinding
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.home.WidgetHomeHeaderManager
import com.discord.widgets.home.WidgetHomeModel
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

@AliucordPlugin
class ProfileEffects : Plugin() {
    /** Everything this plugin shows on one user sheet header. */
    private class Binding(val key: ProfileKey, var animate: Boolean, header: UserProfileHeaderView) {
        var profile = Profile()
        var overlay: ProfileOverlay? = null
        val theme = ProfileTheme(header)
        var detachListener: View.OnAttachStateChangeListener? = null
        var loading = false
        var refreshAt = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<UserProfileHeaderView, Binding>()
    private val clips = ClipOwners()
    private var repository: ProfileRepository? = null
    private var capture: ModelCapture? = null
    private var names: NameRenderer? = null
    private lateinit var prefs: Prefs
    private var refreshQueued = false
    private val stylesPending = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        settingsTab = SettingsTab(ProfileEffectsSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
    }

    override fun start(context: Context) {
        instance = this
        prefs = Prefs(settings)
        val log: (String, Throwable) -> Unit = { message, error -> logger.error(message, error) }
        val repository = ProfileRepository(log)
        this.repository = repository
        val names = NameRenderer(
            prefs,
            { guild -> repository.loadRoles(guild) { main.post(::queueNameRefresh) } },
            log,
        )
        this.names = names
        NameFonts.setListener(::queueNameRefresh)
        NameFonts.log = { message, error -> logger.error(message, error) }
        // Styles usually arrive just before Discord binds the views, but a profile or role fetch can finish later.
        NameStyles.onChange = {
            if (stylesPending.compareAndSet(false, true)) {
                main.postDelayed({
                    stylesPending.set(false)
                    queueNameRefresh()
                }, 300)
            }
        }
        capture = ModelCapture(log).also {
            it.active = true
            it.install(patcher)
        }
        patchSheets()
        patchNames()
        patchHeader()
        removeReplacedPlugins()
    }

    /**
     * ModernUserStyles and ModernProfiles show the same profile and name styles and would patch the
     * same views. Uninstall them after every plugin has started: unloading during Aliucord's start
     * loop would modify the plugin map while it is iterated.
     */
    private fun removeReplacedPlugins() {
        val replaced = ReplacedPlugins.installed(listOf("ModernUserStyles", "ModernProfiles"))
        if (replaced.isEmpty()) return
        main.post {
            val removed = ReplacedPlugins.uninstall(replaced, logger)
            if (removed.isNotEmpty()) {
                Utils.showToast("ProfileEffects replaced and uninstalled: ${removed.joinToString(", ")}", true)
            }
        }
    }

    private fun patchSheets() {
        patcher.patch(
            UserProfileHeaderView::class.java,
            "updateViewState",
            arrayOf(UserProfileHeaderViewModel.ViewState.Loaded::class.java),
            Hook { call ->
                val header = call.thisObject as UserProfileHeaderView
                val state = call.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
                val id = state.user.id
                val guild = state.guildMember?.guildId?.takeIf { it > 0 }
                    ?: bindings[header]?.key?.takeIf { it.userId == id }?.guildId
                styleHeaderName(header, id, guild, state.user)
                if (!header.hasResourceName("user_sheet_profile_header_view")) return@Hook
                bind(header, ProfileKey(id, guild), !state.reducedMotionEnabled || state.allowAnimationInReducedMotion)
            },
        )
        // The sheet supplies its guild even when membership hasn't loaded into the header yet.
        patcher.patch(
            WidgetUserSheet::class.java,
            "configureUI",
            arrayOf(WidgetUserSheetViewModel.ViewState::class.java),
            Hook { call ->
                val header = header(call.thisObject as WidgetUserSheet) ?: return@Hook
                val state = call.args[0] as? WidgetUserSheetViewModel.ViewState.Loaded
                if (state == null) {
                    release(header)
                    return@Hook
                }
                val guild = state.guildMember?.guildId?.takeIf { it > 0 }
                    ?: state.currentGuildId?.takeIf { it > 0 }
                val animate = bindings[header]?.animate ?: !AccessibilityUtils.INSTANCE.isReducedMotionEnabled()
                bind(header, ProfileKey(state.user.id, guild), animate)
            },
        )
        patcher.patch(
            WidgetUserSheet::class.java,
            "onResume",
            emptyArray(),
            Hook { call ->
                val header = header(call.thisObject as WidgetUserSheet) ?: return@Hook
                val binding = bindings[header] ?: return@Hook
                bind(header, binding.key, binding.animate)
                binding.overlay?.restartAnimation()
            },
        )
        // This method is inherited, so patch the declaring class and restrict the callback to user sheets.
        patcher.patch(
            AppBottomSheet::class.java,
            "onDestroyView",
            emptyArray(),
            PreHook { call ->
                val sheet = call.thisObject as? WidgetUserSheet ?: return@PreHook
                header(sheet)?.let(::release)
            },
        )
    }

    private fun patchNames() {
        val chatName = Utils.getResId("chat_list_adapter_item_text_name", "id")
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { call ->
                val entry = call.args[1] as? MessageEntry ?: return@Hook
                val item = call.thisObject as WidgetChatListAdapterItemMessage
                val view = item.itemView.findViewById<TextView>(chatName) ?: return@Hook
                val message = entry.message
                val author = message.author ?: return@Hook
                val member = entry.author
                val guild = member?.guildId?.takeIf { it > 0 } ?: message.guildId
                // Messages without a guild are in DMs, which follow the DM color setting.
                names?.apply(view, author.id, guild, member?.roles, true, guild == null, author)
            },
        )
        // Reply previews: the replied-to author's name above the message. configureReplyAuthor is a
        // private method that compiled code inlines, so hooking it only works some of the time;
        // style the name from onConfigure, which always runs, by finding the author the same way.
        val replyName = Utils.getResId("chat_list_adapter_item_text_decorator_reply_name", "id")
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { call ->
                val entry = call.args[1] as? MessageEntry ?: return@Hook
                val item = call.thisObject as WidgetChatListAdapterItemMessage
                val view = item.itemView.findViewById<TextView>(replyName) ?: return@Hook
                val names = names ?: return@Hook
                val reply = replyAuthor(entry)
                if (reply == null) {
                    // A recycled row may now show a reply without an author, or none.
                    names.forget(view)
                    return@Hook
                }
                val (user, member, message) = reply
                val guild = member?.guildId?.takeIf { it > 0 } ?: message.guildId
                names.apply(view, user.id, guild, member?.roles, true, guild == null, user)
            },
        )
        patcher.patch(
            ChannelMembersListViewHolderMember::class.java,
            "bind",
            arrayOf(ChannelMembersListAdapter.Item.Member::class.java, Function0::class.java),
            Hook { call ->
                val member = call.args[0] as ChannelMembersListAdapter.Item.Member
                val holder = call.thisObject as ChannelMembersListViewHolderMember
                val view = usernameText(holder.itemView) ?: return@Hook
                names?.apply(view, member.userId, member.guildId, null, true)
            },
        )

        // DM list: only one-on-one DMs have a single user's name.
        val dmBinding = WidgetChannelsListAdapter.ItemChannelPrivate::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }
        patcher.patch(
            WidgetChannelsListAdapter.ItemChannelPrivate::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChannelListItem::class.java),
            Hook { call ->
                val item = call.args[1] as? ChannelListItemPrivate ?: return@Hook
                val binding = dmBinding[call.thisObject] as WidgetChannelsListItemChannelPrivateBinding
                val recipient = ChannelUtils.a(item.channel)
                styleListName(binding.f, recipient?.id, recipient)
            },
        )

        // Friend list rows (friends and pending requests).
        val friendBinding = WidgetFriendsListAdapter.ItemUser::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }
        patcher.patch(
            WidgetFriendsListAdapter.ItemUser::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, FriendsListViewModel.Item::class.java),
            Hook { call ->
                val item = call.args[1] as? FriendsListViewModel.Item.Friend ?: return@Hook
                val binding = friendBinding[call.thisObject] as WidgetFriendsListAdapterItemFriendBinding
                styleListName(binding.f, item.user.id, item.user)
            },
        )
        val pendingBinding = WidgetFriendsListAdapter.ItemPendingUser::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }
        patcher.patch(
            WidgetFriendsListAdapter.ItemPendingUser::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, FriendsListViewModel.Item::class.java),
            Hook { call ->
                val item = call.args[1] as? FriendsListViewModel.Item.PendingFriendRequest ?: return@Hook
                val binding = pendingBinding[call.thisObject] as WidgetFriendsListAdapterItemPendingBinding
                styleListName(binding.f, item.user.id, item.user)
            },
        )
    }

    /**
     * The author whose name a message's reply preview shows, found the way Discord's
     * configureReplyPreview does: the command user for interactions, otherwise the replied-to
     * message's author when that message is loaded, not blocked and not a system message.
     */
    private fun replyAuthor(entry: MessageEntry): Triple<com.discord.api.user.User, ModelGuildMember?, com.discord.models.message.Message>? {
        val message = entry.message
        if (message.isInteraction) {
            val user = message.interaction?.c() ?: return null
            return Triple(user, entry.interactionAuthor, message)
        }
        val reply = entry.replyData ?: return null
        if (message.type != REPLY_TYPE || reply.isRepliedUserBlocked) return null
        if (reply.messageState !is StoreMessageReplies.MessageState.Loaded) return null
        val replied = reply.messageEntry ?: return null
        val type = replied.message.type
        if (type == USER_JOIN_TYPE || type == ROLE_SUBSCRIPTION_TYPE) return null
        val user = replied.message.author ?: return null
        return Triple(user, replied.author, replied.message)
    }

    /** The chat header's title: the other person's name in a one-on-one DM. */
    private fun patchHeader() {
        patcher.patch(
            WidgetHomeHeaderManager::class.java,
            "configure",
            arrayOf(WidgetHome::class.java, WidgetHomeModel::class.java, WidgetHomeBinding::class.java),
            Hook { call ->
                val home = call.args[0] as WidgetHome
                val model = call.args[1] as WidgetHomeModel
                val title = home.actionBarTitleLayout?.title ?: return@Hook
                val channel = model.channel
                // The same title shows channel and group names, which must not keep a user's style.
                val recipient = if (channel != null && ChannelUtils.m(channel)) ChannelUtils.a(channel) else null
                if (recipient == null) {
                    listTokens.remove(title)
                    names?.forget(title)
                } else {
                    styleListName(title, recipient.id, recipient)
                }
            },
        )
    }

    /**
     * Aliucord's RNAPI replaces these names with the global name in hooks that run after this
     * plugin's, so style them once the bind (and every hook on it) has finished.
     */
    private fun styleListName(view: TextView, userId: Long?, model: Any? = null) {
        val names = names ?: return
        if (userId == null) {
            // Cancel a style still queued from this row's previous (one-on-one) DM.
            listTokens.remove(view)
            names.apply(view, 0, null, null, false, true)
            return
        }
        val token = Any()
        listTokens[view] = token
        view.post {
            if (listTokens[view] === token && this.names === names) names.apply(view, userId, null, null, false, true, model)
        }
    }

    private val listTokens = java.util.WeakHashMap<TextView, Any>()

    private fun styleHeaderName(header: UserProfileHeaderView, userId: Long, guildId: Long?, model: Any? = null) {
        val nameView = header.findViewById<View>(Utils.getResId("user_profile_header_primary_name", "id")) ?: return
        val text = usernameText(nameView) ?: return
        // Discord 126.21 never colors profile names by role, so only display name styles apply here.
        names?.apply(text, userId, guildId, null, false, false, model)
    }

    private fun usernameText(root: View): TextView? = root.findViewById(Utils.getResId("username_text", "id"))

    private fun queueNameRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        main.post {
            refreshQueued = false
            names?.refreshAll()
        }
    }

    private fun header(sheet: WidgetUserSheet): UserProfileHeaderView? =
        sheet.view?.findViewById(Utils.getResId("user_sheet_profile_header_view", "id"))

    private fun bind(header: UserProfileHeaderView, key: ProfileKey, animate: Boolean) {
        val repository = repository ?: return
        if (key.userId <= 0) {
            release(header)
            return
        }
        var binding = bindings[header]
        if (binding == null || binding.key != key) {
            release(header)
            binding = Binding(key, animate, header)
            bindings[header] = binding
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val current = bindings[header] ?: return
                    // Adding sibling overlays must also wait for Android's attach traversal to finish.
                    main.post { if (bindings[header] === current) render(header, current) }
                }

                override fun onViewDetachedFromWindow(view: View) = release(header)
            }
            binding.detachListener = listener
            header.addOnAttachStateChangeListener(listener)
        }
        binding.animate = animate
        render(header, binding)
        if (binding.loading || now() < binding.refreshAt) return
        binding.loading = true
        val weakBinding = WeakReference(binding)
        val weakHeader = WeakReference(header)
        repository.load(key) { result ->
            // Network completions never hold a sheet/activity alive or touch Android views off the main thread.
            main.post {
                if (this.repository !== repository) return@post
                val target = weakHeader.get() ?: return@post
                val current = weakBinding.get() ?: return@post
                if (bindings[target] !== current) return@post
                if (result.complete) {
                    current.loading = false
                    current.refreshAt = now() + result.ttl
                }
                current.profile = result.value ?: Profile()
                render(target, current)
                // The profile may have brought this user's display name style.
                styleHeaderName(target, current.key.userId, current.key.guildId)
                queueNameRefresh()
            }
        }
    }

    private fun render(header: UserProfileHeaderView, binding: Binding) {
        if (repository == null || bindings[header] !== binding || header.windowToken == null) return
        val profile = binding.profile
        binding.theme.apply(if (prefs.themes) profile.theme else null)
        val shown = Profile(
            if (prefs.effects) profile.effect else null,
            if (prefs.frames) profile.frame else null,
        )
        if (shown.effect == null && shown.frame == null) {
            binding.overlay?.dispose()
            binding.overlay = null
            return
        }
        try {
            var overlay = binding.overlay
            if (overlay != null && !overlay.matches(header)) {
                overlay.dispose()
                overlay = null
            }
            if (overlay == null) {
                overlay = ProfileOverlay.create(header, clips) { message, error -> logger.error(message, error) }
                binding.overlay = overlay
            }
            overlay?.bind(shown, binding.animate)
        } catch (error: Exception) {
            binding.overlay?.dispose()
            binding.overlay = null
            logger.error("Could not attach profile decorations", error)
        }
    }

    private fun release(header: UserProfileHeaderView) {
        val binding = bindings.remove(header) ?: return
        binding.detachListener?.let { header.removeOnAttachStateChangeListener(it) }
        binding.overlay?.dispose()
        binding.theme.restore()
    }

    private fun onSettingsChanged() {
        for ((header, binding) in ArrayList(bindings.entries)) render(header, binding)
        queueNameRefresh()
    }

    private fun now() = System.nanoTime() / 1_000_000L

    override fun stop(context: Context) {
        capture?.active = false
        capture = null
        repository?.close()
        repository = null
        patcher.unpatchAll()
        main.removeCallbacksAndMessages(null)
        refreshQueued = false
        for (header in ArrayList(bindings.keys)) release(header)
        names?.clear()
        names = null
        NameFonts.setListener(null)
        NameFonts.log = null
        NameStyles.onChange = null
        stylesPending.set(false)
        NameStyles.clear()
        if (instance === this) instance = null
    }

    companion object {
        private var instance: ProfileEffects? = null

        // Discord message types.
        private const val USER_JOIN_TYPE = 7
        private const val REPLY_TYPE = 19
        private const val ROLE_SUBSCRIPTION_TYPE = 25

        internal fun settingsChanged() {
            instance?.let { plugin -> plugin.main.post { if (plugin.repository != null) plugin.onSettingsChanged() } }
        }
    }
}
