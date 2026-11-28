package com.github.yutaplug.fixes.leaderboard

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PorterDuff
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.users.globalName
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreGuildMemberRequester
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemSystemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.github.yutaplug.fixes.Fix
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import de.robv.android.xposed.XC_MethodHook
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Renders the server leaderboard's system messages (message type 69, `GUILD_SPACE_MESSAGE`), which
 * Discord 126.21 shows as "Original message was deleted". Their details are in the message's
 * `guild_space_data`, a field 126.21's message model doesn't have, so it's read during parsing.
 */
internal class LeaderboardMessagesFix :
    Fix(
        "LeaderboardMessagesFix",
        "Leaderboard messages",
        "Shows the server gaming leaderboard's messages (a new week, a new 1st place, the weekly winner) " +
            "instead of \"Original message was deleted\".",
    ) {
    private val gson = Gson()
    private val lock = Any()

    /** Parsed api messages waiting for Discord to turn them into a model message, which has the ID. */
    private val pending = ArrayList<Pair<WeakReference<Any>, String>>()

    /** `guild_space_data` by message ID. */
    private val byId = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > MAX_MESSAGES
    }

    /** Gson adapters of api messages, with the field map they had before [GuildSpaceField] was added. */
    private val adapters = WeakHashMap<Any, Map<*, *>>()
    private val requested = Collections.synchronizedSet(HashSet<Long>())
    private val boundFields = ReflectiveTypeAdapterFactory.Adapter::class.java.declaredFields
        .single { Map::class.java.isAssignableFrom(it.type) }
        .apply { isAccessible = true }

    @Volatile private var running = false

    override fun start(context: Context) {
        running = true
        try {
            patchParsing()
            patchRows()
        } catch (error: Throwable) {
            stop(context)
            throw error
        }
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        synchronized(lock) {
            for ((adapter, fields) in adapters) {
                try {
                    boundFields.set(adapter, fields)
                } catch (error: Exception) {
                    logger.error("Could not restore a message adapter", error)
                }
            }
            adapters.clear()
            pending.clear()
            byId.clear()
        }
        requested.clear()
    }

    private fun patchParsing() {
        // Teach every Gson adapter of api messages the guild_space_data field the first time it reads one.
        patcher.patch(
            ReflectiveTypeAdapterFactory.Adapter::class.java,
            "read",
            arrayOf(JsonReader::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fields = boundFields.get(param.thisObject) as? Map<*, *> ?: return
                    if (fields is FieldMap || fields.containsKey(KEY)) return
                    if (!MESSAGE_FIELDS.all { fields.containsKey(it) }) return
                    synchronized(lock) {
                        if (!running || adapters.containsKey(param.thisObject)) return
                        @Suppress("UNCHECKED_CAST")
                        val withField = FieldMap(fields as Map<String, ReflectiveTypeAdapterFactory.a>)
                        withField[KEY] = GuildSpaceField()
                        boundFields.set(param.thisObject, withField)
                        adapters[param.thisObject] = fields
                    }
                }
            },
        )
        patcher.patch(
            Message::class.java.getConstructor(com.discord.api.message.Message::class.java),
            Hook { call ->
                val api = call.args[0] ?: return@Hook
                val message = call.thisObject as Message
                synchronized(lock) {
                    if (pending.isEmpty()) return@Hook
                    val iterator = pending.iterator()
                    while (iterator.hasNext()) {
                        val (ref, data) = iterator.next()
                        val parsed = ref.get()
                        if (parsed === api) {
                            byId[message.id] = data
                            iterator.remove()
                        } else if (parsed == null) {
                            iterator.remove()
                        }
                    }
                }
            },
        )
    }

    private fun patchRows() {
        patcher.patch(
            WidgetChatListAdapterItemSystemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { call ->
                val entry = call.args[1] as? MessageEntry ?: return@Hook
                val message = entry.message
                if (message.type != MESSAGE_TYPE) return@Hook
                val data = synchronized(lock) { byId[message.id] } ?: return@Hook
                val item = call.thisObject as WidgetChatListAdapterItemSystemMessage
                try {
                    configure(item, LeaderboardEvent.parse(data) ?: return@Hook)
                } catch (error: Throwable) {
                    logger.error("Could not show a leaderboard message", error)
                }
            },
        )
    }

    private fun configure(item: WidgetChatListAdapterItemSystemMessage, event: LeaderboardEvent) {
        val template = event.template() ?: return
        val binding = WidgetChatListAdapterItemSystemMessage.`access$getBinding$p`(item)
        val data = WidgetChatListAdapterItemSystemMessage.`access$getAdapter$p`(item).data
        val guildId = data.guildId
        val channelId = data.channelId
        val textView = binding.h
        val context = textView.context
        val nameColor = ColorCompat.getThemedColor(context, Utils.getResId("colorHeaderPrimary", "attr"))

        val text = SpannableStringBuilder(template)
        replaceName(text, LeaderboardEvent.NAME, event.userId, guildId, channelId, nameColor)
        if (event.event == LeaderboardEvent.LEADER_CHANGED) {
            replaceName(text, LeaderboardEvent.PREVIOUS_NAME, event.secondaryUserId, guildId, channelId, nameColor)
        }
        textView.text = text

        trophy(context)?.let {
            it.setColorFilter(
                ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr")),
                PorterDuff.Mode.SRC_IN,
            )
            binding.f.setImageDrawable(it)
        }
    }

    /** Puts the user's server name where [placeholder] is, in their role color, opening their profile. */
    private fun replaceName(
        text: SpannableStringBuilder,
        placeholder: String,
        userId: Long?,
        guildId: Long,
        channelId: Long,
        defaultColor: Int,
    ) {
        // On a String, not the CharSequence extension, which walks an IntProgression (see CLAUDE.md).
        val start = text.toString().indexOf(placeholder)
        if (start < 0) return
        val member = if (userId == null) null else StoreStream.getGuilds().members[guildId]?.get(userId)
        val user = if (userId == null) null else StoreStream.getUsers().users[userId]
        if (userId != null && user == null) requestMember(guildId, userId)
        val name = member?.nick?.takeIf { it.trim().isNotEmpty() }
            ?: user?.globalName?.takeIf { it.trim().isNotEmpty() }
            ?: user?.username
            ?: ""
        text.replace(start, start + placeholder.length, name)
        if (userId == null || name.isEmpty()) return
        val span = NameSpan(userId, channelId, guildId, GuildMember.getColor(member, defaultColor))
        text.setSpan(span, start, start + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Loads a user who isn't cached, like Discord does for message authors; the row updates when they arrive. */
    private fun requestMember(guildId: Long, userId: Long) {
        if (guildId == 0L || !requested.add(userId)) return
        try {
            val companion = StoreStream::class.java.getDeclaredField("Companion").get(null)!!
            val requester = companion.javaClass.getMethod("getGuildMemberRequester").invoke(companion)
                as StoreGuildMemberRequester
            requester.queueRequest(guildId, userId)
            requester.performQueuedRequests()
        } catch (error: Throwable) {
            logger.error("Could not load a leaderboard user", error)
        }
    }

    private fun trophy(context: Context) = resources?.let { res ->
        val id = res.getIdentifier("ic_leaderboard_trophy_24dp", "drawable", "com.github.yutaplug.fixes")
        if (id == 0) null else ResourcesCompat.getDrawable(res, id, context.theme)?.mutate()
    }

    /** Marks a message adapter's field map that already has [GuildSpaceField]. */
    private class FieldMap(fields: Map<String, ReflectiveTypeAdapterFactory.a>) :
        LinkedHashMap<String, ReflectiveTypeAdapterFactory.a>(fields)

    /** Reads `guild_space_data` into [pending]; never written back. */
    private inner class GuildSpaceField : ReflectiveTypeAdapterFactory.a(KEY, false, true) {
        override fun a(reader: JsonReader, value: Any) {
            val json = gson.d<JsonElement>(reader, JsonElement::class.java)?.toString() ?: return
            if (!running || json == "null") return
            synchronized(lock) {
                if (pending.size >= MAX_PENDING) pending.removeAt(0)
                pending.add(Pair(WeakReference(value), json))
            }
        }

        override fun b(writer: JsonWriter, value: Any) {}

        override fun c(value: Any) = false
    }

    private class NameSpan(
        private val userId: Long,
        private val channelId: Long,
        private val guildId: Long,
        private val color: Int,
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            // The tapped screen: Aliucord's appActivity can be a screen that was already closed.
            var context: Context? = widget.context
            while (context is ContextWrapper && context !is FragmentActivity) context = context.baseContext
            val activity = context as? FragmentActivity ?: Utils.appActivity
            WidgetUserSheet.show(
                userId,
                channelId.takeIf { it != 0L },
                activity.supportFragmentManager,
                guildId.takeIf { it != 0L },
            )
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = false
        }
    }

    private companion object {
        const val MESSAGE_TYPE = 69
        const val KEY = "guild_space_data"
        const val MAX_MESSAGES = 500
        const val MAX_PENDING = 100

        /** Fields only Discord's message models have, to tell their Gson adapters apart. */
        val MESSAGE_FIELDS = arrayOf("referenced_message", "mention_everyone", "sticker_items", "role_subscription_data")
    }
}
