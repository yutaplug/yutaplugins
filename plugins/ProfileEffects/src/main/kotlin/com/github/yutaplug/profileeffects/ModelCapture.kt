package com.github.yutaplug.profileeffects

import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.discord.api.message.Message
import com.discord.api.role.GuildRole
import com.discord.api.user.User
import com.discord.stores.StoreGuilds
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.stream.JsonReader
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.ConcurrentHashMap
import com.discord.api.guildmember.GuildMember as ApiMember

/**
 * Discord 126.21's reflective Gson models skip fields they don't declare with
 * `JsonReader.skipValue()`. While one of the models we care about is being read, that skip is
 * intercepted for `display_name_styles` (members, and users on base apps without Aliucord's
 * user field) and `colors` (roles), so the value is kept without reparsing the payload.
 *
 * Global user styles normally come from Aliucord's own user field instead (see [ModelStyles]).
 */
internal class ModelCapture(private val log: (String, Throwable) -> Unit) {
    private class Frame(
        val kind: Int,
        val adapter: Any,
        val reader: JsonReader,
        val depth: Int,
        val parent: Frame?,
    ) {
        var value: Any? = null
        var captured = false

        /** A member's style from inside a message; the message supplies its guild and author. */
        var memberStyle: NameStyle? = null
        var hasMemberStyle = false
    }

    private val kinds = ConcurrentHashMap<Any, Int>()
    private val frames = ThreadLocal<Frame?>()
    private val elementAdapter: TypeAdapter<JsonElement> = GsonUtils.gson.i(JsonElement::class.java)
    private val boundFields = ReflectiveTypeAdapterFactory.Adapter::class.java.declaredFields
        .single { Map::class.java.isAssignableFrom(it.type) }
        .apply { isAccessible = true }

    @Volatile var active = false

    fun install(patcher: com.aliucord.api.PatcherAPI) {
        patcher.patch(
            ReflectiveTypeAdapterFactory.Adapter::class.java.getDeclaredMethod("read", JsonReader::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!active) return
                    val kind = kind(param.thisObject)
                    if (kind == NONE) return
                    val reader = param.args[0] as JsonReader
                    frames.set(Frame(kind, param.thisObject, reader, reader.w + 1, frames.get()))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // Frames nest like the reads, so only the frame this call pushed can be on top.
                    val frame = frames.get() ?: return
                    if (frame.adapter !== param.thisObject || frame.reader !== param.args[0]) return
                    frames.set(frame.parent)
                    if (param.throwable != null) return
                    try {
                        finish(param.thisObject, frame, param.result)
                    } catch (error: Throwable) {
                        log("Could not read name styles", error)
                    }
                }
            },
        )
        patcher.patch(
            JsonReader::class.java.getDeclaredMethod("U"),
            PreHook { call ->
                val frame = frames.get() ?: return@PreHook
                val reader = call.thisObject as JsonReader
                if (frame.reader !== reader || reader.w != frame.depth || frame.depth <= 0) return@PreHook
                val name = reader.x[frame.depth - 1] ?: return@PreHook
                val wanted = when (frame.kind) {
                    ROLE -> name == "colors"
                    USER, MEMBER -> name == "display_name_styles"
                    else -> false
                }
                if (!wanted) return@PreHook
                // Read the value instead of skipping it; the reader ends up in the same place.
                frame.value = Json.value(elementAdapter.read(reader))
                frame.captured = true
                call.result = null
            },
        )
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

    private fun kind(adapter: Any): Int {
        kinds[adapter]?.let { return it }
        val keys = (boundFields.get(adapter) as? Map<*, *>)?.keys ?: emptySet<Any>()
        fun has(vararg names: String) = names.any { keys.contains(it) }
        val kind = when {
            has("discriminator") && has("username") && has("public_flags", "publicFlags") -> USER
            has("mentionable") && has("hoist") && has("permissions") -> ROLE
            has("joined_at", "joinedAt") && has("nick") && has("roles") -> MEMBER
            has("author") && has("mention_everyone", "mentionEveryone") -> MESSAGE
            else -> NONE
        }
        kinds[adapter] = kind
        return kind
    }

    private fun finish(adapter: Any, frame: Frame, result: Any?) {
        when (frame.kind) {
            USER -> {
                if (result !is User) return mismatch(adapter)
                if (frame.captured) NameStyles.setUser(result.id, NameStyle.parse(frame.value))
            }

            ROLE -> {
                if (result !is GuildRole) return mismatch(adapter)
                // Seeing a role at all (with or without colors) means it needs no REST fallback.
                NameStyles.setRole(result.id, if (frame.captured) frame.value as? Map<*, *> else null)
            }

            MEMBER -> {
                if (result !is ApiMember) return mismatch(adapter)
                if (!frame.captured) return
                val style = NameStyle.parse(frame.value)
                val userId = result.m()?.id ?: result.n() ?: 0L
                val parent = frame.parent
                when {
                    result.f() > 0 && userId > 0 -> NameStyles.setMember(result.f(), userId, style)

                    // Message members omit their user and guild; the message has both.
                    parent != null && parent.kind == MESSAGE -> {
                        parent.memberStyle = style
                        parent.hasMemberStyle = true
                    }

                    userId > 0 -> NameStyles.setUnresolved(userId, style)
                }
            }

            MESSAGE -> {
                if (result !is Message) return mismatch(adapter)
                if (!frame.hasMemberStyle) return
                val guild = result.m() ?: return
                val author = result.e() ?: return
                NameStyles.setMember(guild, author.id, frame.memberStyle)
            }
        }
    }

    private fun mismatch(adapter: Any) {
        kinds[adapter] = NONE
    }

    private companion object {
        const val NONE = 0
        const val USER = 1
        const val ROLE = 2
        const val MEMBER = 3
        const val MESSAGE = 4
    }
}
