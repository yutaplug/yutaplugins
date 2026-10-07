package com.github.yutaplug.dmactivities

import android.content.Context
import android.view.ViewGroup
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.wrappers.users.globalName
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import rx.Observable
import rx.Subscriber
import rx.Subscription
import rx.functions.Func2
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
        subscription = Observable
            .j(
                StoreStream.getPresences().observeAllPresences(),
                StoreStream.getUserRelationships().observeForType(RELATIONSHIP_FRIEND),
                Func2<Map<Long, Presence>, Map<Long, Int>, List<ActivityCard>> { presences, friends ->
                    buildCards(presences, friends.keys)
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

    private fun buildCards(presences: Map<Long, Presence>, friendIds: Collection<Long>): List<ActivityCard> {
        val users = StoreStream.getUsers().users
        val result = ArrayList<ActivityCard>()
        for (id in friendIds) {
            val presence = presences[id] ?: continue
            if (presence.status == ClientStatus.OFFLINE || presence.status == ClientStatus.INVISIBLE) continue
            val user = users[id] ?: continue
            val activities = presence.activities.orEmpty()
            val custom = activities.firstOrNull { it.p() == ActivityType.CUSTOM_STATUS }
                ?.takeIf { hasText(it.l()) || it.f() != null }
            val primary = activities.firstOrNull { it.p() != ActivityType.CUSTOM_STATUS && it.p() != ActivityType.UNKNOWN }
            if (primary == null && custom == null) continue
            result += ActivityCard(user, displayName(user), presence, primary, custom)
        }
        // Rich activities come first, newest first; custom statuses follow alphabetically.
        return result.sortedWith(
            compareBy<ActivityCard> { it.activity == null }
                .thenByDescending { it.activity?.d() ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    private fun displayName(user: User): String = user.globalName?.takeIf { hasText(it) } ?: user.username

    data class ActivityCard(
        val user: User,
        val name: String,
        val presence: Presence,
        val activity: Activity?,
        val customStatus: Activity?,
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
