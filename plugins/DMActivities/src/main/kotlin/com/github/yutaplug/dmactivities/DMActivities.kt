package com.github.yutaplug.dmactivities

import android.content.Context
import android.view.ViewGroup
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import com.aliucord.wrappers.users.globalName
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.api.voice.state.VoiceState
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import rx.Observable
import rx.Subscriber
import rx.Subscription
import rx.functions.Func3
import java.lang.ref.WeakReference

@AliucordPlugin
class DMActivities : Plugin() {
    private var subscription: Subscription? = null

    // Only touched on the main thread.
    private var cards: List<ActivityCard> = emptyList()
    private var adapterRef: WeakReference<WidgetChannelsListAdapter>? = null
    private var lastDmItems: List<ChannelListItem>? = null

    override fun start(context: Context) {
        patcher.patch(
            MGRecyclerAdapterSimple::class.java.getDeclaredMethod("setData", List::class.java),
            PreHook { call ->
                val adapter = call.thisObject as? WidgetChannelsListAdapter ?: return@PreHook

                @Suppress("UNCHECKED_CAST")
                val items = (call.args[0] as List<ChannelListItem>).filter { it !is ActivitiesItem }
                if (items.none { it is ChannelListItemPrivate }) {
                    if (adapterRef?.get() === adapter) lastDmItems = null
                    call.args[0] = items
                    return@PreHook
                }
                adapterRef = WeakReference(adapter)
                lastDmItems = items
                call.args[0] = if (cards.isEmpty()) items else listOf(ActivitiesItem(cards)) + items
            },
        )
        patcher.patch(
            WidgetChannelsListAdapter::class.java.getDeclaredMethod(
                "onCreateViewHolder",
                ViewGroup::class.java,
                Int::class.javaPrimitiveType,
            ),
            PreHook { call ->
                if (call.args[1] != ActivitiesItem.TYPE) return@PreHook
                val adapter = call.thisObject as WidgetChannelsListAdapter
                call.result = ActivitiesViewHolder(ActivitiesViewHolder.createView(adapter.context), adapter)
            },
        )
        subscribe()
    }

    override fun stop(context: Context) {
        subscription?.unsubscribe()
        subscription = null
        patcher.unpatchAll()
        cards = emptyList()
        refreshList()
        adapterRef = null
        lastDmItems = null
    }

    private fun subscribe() {
        val voiceStore = StoreStream.getVoiceStates()
        // StoreVoiceStates only offers per-guild observables, so watch the whole store.
        // Same call StoreVoiceStates.observe makes: emits now and on every change, keeping the latest.
        @Suppress("UNCHECKED_CAST")
        val voiceStates = ObservationDeck.`connectRx$default`(
            ObservationDeckProvider.get(),
            arrayOf<ObservationDeck.UpdateSource>(voiceStore),
            false,
            null,
            null,
            { voiceStore.get() },
            14,
            null,
        ) as Observable<Map<Long, Map<Long, VoiceState>>>
        subscription = Observable
            .i(
                StoreStream.getPresences().observeAllPresences(),
                StoreStream.getUserRelationships().observeForType(RELATIONSHIP_FRIEND),
                voiceStates,
                Func3<Map<Long, Presence>, Map<Long, Int>, Map<Long, Map<Long, VoiceState>>, List<ActivityCard>> {
                        presences, friends, voice ->
                    buildCards(presences, friends.keys, voice)
                },
            ).U(
                object : Subscriber<List<ActivityCard>>() {
                    override fun onNext(next: List<ActivityCard>) {
                        Utils.mainThread.post {
                            if (next == cards || subscription == null) return@post
                            cards = next
                            refreshList()
                        }
                    }

                    override fun onError(error: Throwable) {
                        logger.error("Failed to observe friend activities", error)
                    }

                    override fun onCompleted() {}
                },
            )
    }

    private fun refreshList() {
        val adapter = adapterRef?.get() ?: return
        val items = lastDmItems ?: return
        // Goes through the setData hook, which inserts the carousel with the latest cards.
        adapter.setData(items)
    }

    private fun buildCards(
        presences: Map<Long, Presence>,
        friendIds: Collection<Long>,
        voiceStates: Map<Long, Map<Long, VoiceState>>,
    ): List<ActivityCard> {
        val users = StoreStream.getUsers().users
        val friends = friendIds.toHashSet()
        val inVoice = HashMap<Long, VoiceInfo>()
        // Voice states from the READY payload have no guild ID, so use the store's key instead.
        for ((guildId, guildStates) in voiceStates) {
            for ((userId, state) in guildStates) {
                val channelId = state.a() ?: continue
                if (userId in friends) inVoice[userId] = voiceInfo(guildId, channelId, state.i())
            }
        }
        val result = ArrayList<ActivityCard>()
        for (id in friendIds) {
            val voice = inVoice[id]
            val presence = presences[id]
            val online = presence != null &&
                presence.status != ClientStatus.OFFLINE && presence.status != ClientStatus.INVISIBLE
            // Friends who appear offline still show up when they are in a voice channel.
            if (!online && voice == null) continue
            val user = users[id] ?: continue
            val activities = if (online) presence?.activities.orEmpty() else emptyList()
            val custom = activities.firstOrNull { it.p() == ActivityType.CUSTOM_STATUS }
                ?.takeIf { hasText(it.l()) || it.f() != null }
            val primary = activities.firstOrNull { it.p() != ActivityType.CUSTOM_STATUS && it.p() != ActivityType.UNKNOWN }
            if (voice == null && primary == null && custom == null) continue
            result += ActivityCard(user, displayName(user), presence, primary, custom, voice)
        }
        // Voice comes first, then rich activities newest first; custom statuses follow alphabetically.
        return result.sortedWith(
            compareBy<ActivityCard> { it.voice == null }
                .thenBy { it.activity == null }
                .thenByDescending { it.activity?.d() ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    private fun voiceInfo(guildId: Long, channelId: Long, streaming: Boolean): VoiceInfo {
        val channel = StoreStream.getChannels().getChannel(channelId)
        // DM and group calls are stored under guild 0.
        val guild = if (guildId != 0L) StoreStream.getGuilds().getGuild(guildId) else null
        return VoiceInfo(
            channelName = channel?.p()?.takeIf { hasText(it) },
            guildName = guild?.name,
            guildIcon = guild?.takeIf { it.icon != null }?.let { IconUtils.getForGuild(it, null, true, DimenUtils.dpToPx(72)) },
            streaming = streaming,
        )
    }

    private fun displayName(user: User): String = user.globalName?.takeIf { hasText(it) } ?: user.username

    data class ActivityCard(
        val user: User,
        val name: String,
        val presence: Presence?,
        val activity: Activity?,
        val customStatus: Activity?,
        val voice: VoiceInfo?,
    )

    /** A friend's voice channel; [guildName] is null for DM and group calls. */
    data class VoiceInfo(
        val channelName: String?,
        val guildName: String?,
        val guildIcon: String?,
        val streaming: Boolean,
    )

    class ActivitiesItem(val cards: List<ActivityCard>) : ChannelListItem {
        override fun getKey() = "DMActivities"

        override fun getType() = TYPE

        override fun equals(other: Any?) = other is ActivitiesItem && other.cards == cards

        override fun hashCode() = cards.hashCode()

        companion object {
            const val TYPE = 0x444D41
        }
    }

    companion object {
        private const val RELATIONSHIP_FRIEND = 1

        // Kotlin's isBlank breaks against the older stdlib bundled in Discord 126.21, so check manually.
        fun hasText(text: CharSequence?): Boolean {
            if (text == null) return false
            for (i in 0 until text.length) {
                if (!Character.isWhitespace(text[i])) return true
            }
            return false
        }
    }
}
