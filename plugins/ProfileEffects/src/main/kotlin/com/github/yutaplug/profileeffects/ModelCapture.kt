package com.github.yutaplug.profileeffects

import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.discord.api.message.Message
import com.discord.api.role.GuildRole
import com.discord.api.user.User
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.stores.StoreGuilds
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.ref.WeakReference
import com.discord.api.guildmember.GuildMember as ApiMember
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory.a as BoundField

/**
 * Discord 126.21's reflective Gson models skip the fields they don't declare. Each reflective
 * adapter looks fields up in its own name -> field map, so this adds fields to that map for
 * `display_name_styles` (members, and users on base apps without Aliucord's user field) and
 * `colors` (roles): Gson then hands those values to this plugin instead of skipping them.
 *
 * The values arrive while the model is still being read, often before its IDs. The fields that
 * carry the IDs are wrapped too, so a value is matched as soon as the IDs it needs are read.
 *
 * This only hooks the adapter constructor, which runs once per model class and Gson instance.
 * Hooking the reads themselves (run for every field Discord parses) crashed Android 7 at launch.
 *
 * Global user styles normally come from Aliucord's own user field instead (see [ModelStyles]).
 */
internal class ModelCapture(private val log: (String, Throwable) -> Unit) {
    /** A value read for a model whose IDs may not have been read yet. */
    private class Pending(val value: Any?)

    private val pending = WeakIdentityMap<Pending>()
    private val elementAdapter: TypeAdapter<JsonElement> = GsonUtils.gson.i(JsonElement::class.java)
    private val boundFields = ReflectiveTypeAdapterFactory.Adapter::class.java.declaredFields
        .single { Map::class.java.isAssignableFrom(it.type) }
        .apply { isAccessible = true }

    /** Adapters this added fields to, so [uninstall] can take them out again. */
    private val injected = ArrayList<WeakReference<Any>>()

    @Volatile var active = false

    fun install(patcher: com.aliucord.api.PatcherAPI) {
        patcher.patch(
            ReflectiveTypeAdapterFactory.Adapter::class.java.declaredConstructors.single(),
            Hook { call -> inject(call.thisObject) },
        )
        // The gateway may have created some adapters before the plugin started.
        try {
            val gson = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
            for (type in arrayOf(User::class.java, GuildRole::class.java, ApiMember::class.java, Message::class.java)) {
                inject(gson.i(type))
            }
        } catch (error: Throwable) {
            log("Could not add name style fields to the gateway models", error)
        }
        // Chunks and member list updates give the guild once, outside the member objects. Every
        // stored guild member passes through here with its guild, so pending styles are matched then.
        patcher.patch(
            StoreGuilds::class.java,
            "handleGuildMember",
            arrayOf(ApiMember::class.java, Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            PreHook { call ->
                if (!active) return@PreHook
                val userId = (call.args[0] as ApiMember).m()?.id ?: return@PreHook
                NameStyles.resolve(call.args[1] as Long, userId)
            },
        )
    }

    /** Puts Discord's own fields back into every adapter this changed. */
    fun uninstall() {
        active = false
        val adapters = synchronized(injected) { injected.mapNotNull { it.get() }.also { injected.clear() } }
        for (adapter in adapters) {
            try {
                @Suppress("UNCHECKED_CAST")
                val map = boundFields.get(adapter) as? Map<String, BoundField> ?: continue
                val restored = LinkedHashMap<String, BoundField>()
                for ((name, field) in map) {
                    when (field) {
                        is ValueField -> {}
                        is IdField -> restored[name] = field.delegate
                        else -> restored[name] = field
                    }
                }
                boundFields.set(adapter, restored)
            } catch (error: Throwable) {
                log("Could not restore a model adapter", error)
            }
        }
    }

    private fun inject(adapter: Any?) {
        if (adapter !is ReflectiveTypeAdapterFactory.Adapter<*>) return
        try {
            @Suppress("UNCHECKED_CAST")
            val map = boundFields.get(adapter) as? Map<String, BoundField> ?: return
            if (map.values.any { it is ValueField || it is IdField }) return
            val kind = kind(map.keys)
            if (kind == NONE) return
            // A copy, so a Gson already using this adapter never sees the map change under it.
            val fields = LinkedHashMap(map)
            when (kind) {
                USER -> {
                    // Aliucord's patched base app reads this field itself.
                    if (fields.containsKey(STYLES)) return
                    fields[STYLES] = ValueField(STYLES, kind)
                    wrap(fields, kind, "id")
                }

                ROLE -> {
                    if (!fields.containsKey(COLORS)) fields[COLORS] = ValueField(COLORS, kind)
                    wrap(fields, kind, "id")
                }

                MEMBER -> {
                    if (fields.containsKey(STYLES)) return
                    fields[STYLES] = ValueField(STYLES, kind)
                    wrap(fields, kind, "user", "user_id", "userId", "guild_id", "guildId")
                }

                MESSAGE -> {
                    wrap(fields, kind, "member", "author", "guild_id", "guildId")
                }
            }
            boundFields.set(adapter, fields)
            synchronized(injected) { injected.add(WeakReference(adapter)) }
        } catch (error: Throwable) {
            log("Could not add name style fields to a model", error)
        }
    }

    private fun wrap(fields: MutableMap<String, BoundField>, kind: Int, vararg names: String) {
        for (name in names) {
            val field = fields[name] ?: continue
            fields[name] = IdField(field, kind)
        }
    }

    private fun kind(keys: Set<String>): Int {
        fun has(vararg names: String) = names.any { keys.contains(it) }
        return when {
            has("discriminator") && has("username") && has("public_flags", "publicFlags") -> USER
            has("mentionable") && has("hoist") && has("permissions") -> ROLE
            has("joined_at", "joinedAt") && has("nick") && has("roles") -> MEMBER
            has("author") && has("mention_everyone", "mentionEveryone") -> MESSAGE
            else -> NONE
        }
    }

    /** Reads the value Discord's model would skip, and keeps it until the model's IDs are known. */
    private inner class ValueField(name: String, private val kind: Int) : BoundField(name, false, true) {
        override fun a(reader: JsonReader, instance: Any) {
            // Read the value instead of skipping it; the reader ends up in the same place.
            val value = Json.value(elementAdapter.read(reader))
            if (!active) return
            try {
                pending.put(instance, Pending(value))
                match(instance, kind)
            } catch (error: Throwable) {
                log("Could not read name styles", error)
            }
        }

        override fun b(writer: JsonWriter, instance: Any) {}

        override fun c(instance: Any): Boolean = false
    }

    /** One of Discord's own fields; after reading it, checks whether a kept value can be matched now. */
    private inner class IdField(val delegate: BoundField, private val kind: Int) :
        BoundField(delegate.a, delegate.b, delegate.c) {
        override fun a(reader: JsonReader, instance: Any) {
            delegate.a(reader, instance)
            if (!active) return
            try {
                match(instance, kind)
            } catch (error: Throwable) {
                log("Could not read name styles", error)
            }
        }

        override fun b(writer: JsonWriter, instance: Any) = delegate.b(writer, instance)

        override fun c(instance: Any): Boolean = delegate.c(instance)
    }

    /** Stores [instance]'s kept value once the IDs it needs have been read. */
    private fun match(instance: Any, kind: Int) {
        when (kind) {
            USER -> {
                val user = instance as? User ?: return
                if (user.id <= 0) return
                val kept = pending.remove(instance) ?: return
                NameStyles.setUser(user.id, NameStyle.parse(kept.value))
            }

            ROLE -> {
                val role = instance as? GuildRole ?: return
                if (role.id <= 0) return
                val kept = pending.remove(instance)
                // Seeing a role at all (with or without colors) means it needs no REST fallback.
                when {
                    kept != null -> NameStyles.setRole(role.id, kept.value as? Map<*, *>)
                    !NameStyles.knowsRole(role.id) -> NameStyles.setRole(role.id, null)
                }
            }

            MEMBER -> {
                val member = instance as? ApiMember ?: return
                val kept = pending.get(instance) ?: return
                val userId = member.m()?.id ?: member.n() ?: 0L
                if (userId <= 0) return // Message members omit their user; the message matches them.
                val style = NameStyle.parse(kept.value)
                if (member.f() > 0) {
                    pending.remove(instance)
                    NameStyles.setMember(member.f(), userId, style)
                } else {
                    // The guild may still follow, or come from the chunk around the member.
                    NameStyles.setUnresolved(userId, style)
                }
            }

            MESSAGE -> {
                val message = instance as? Message ?: return
                val member = message.q() ?: return
                val kept = pending.get(member) ?: return
                val guild = message.m() ?: return
                val author = message.e() ?: return
                pending.remove(member)
                NameStyles.setMember(guild, author.id, NameStyle.parse(kept.value))
            }
        }
    }

    private companion object {
        const val STYLES = "display_name_styles"
        const val COLORS = "colors"

        const val NONE = 0
        const val USER = 1
        const val ROLE = 2
        const val MEMBER = 3
        const val MESSAGE = 4
    }
}
