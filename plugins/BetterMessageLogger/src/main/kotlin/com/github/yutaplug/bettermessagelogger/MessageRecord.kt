package com.github.yutaplug.bettermessagelogger

import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.GsonUtils.toJson
import com.discord.api.premium.PremiumTier
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.message.Message
import com.discord.nullserializable.NullSerializable

internal data class MessageEdit(val timestamp: Long, val content: String)

/** Immutable snapshots can safely cross the store, UI and database threads. */
internal data class MessageRecord(
    val id: Long,
    val channelId: Long,
    val guildId: Long?,
    val authorId: Long,
    val authorName: String,
    val authorAvatar: String?,
    val bot: Boolean,
    val content: String,
    val timestamp: Long,
    val editedTimestamp: Long? = null,
    val deleted: Boolean = false,
    val deletedTimestamp: Long? = null,
    val edits: List<MessageEdit> = emptyList(),
    val payload: String? = null,
    val message: Message? = null,
) {
    val logged get() = deleted || edits.isNotEmpty()

    fun serializedMessage(): String? = message?.let {
        InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance.toJson(it.synthesizeApiMessage())
    } ?: payload

    /** A private copy that never shares mutable attachment objects with Discord's message store. */
    fun toDetachedMessage(): Message = parse(runCatching { serializedMessage() }.getOrNull()) ?: toMessage()

    fun toMessage(): Message {
        message?.let { return it }
        parse(payload)?.let { return it }
        val author = User(
            authorId,
            authorName,
            authorAvatar?.let { NullSerializable.b(it) },
            null,
            "0000",
            null,
            null,
            bot,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            PremiumTier.NONE,
            null,
            null,
            null,
            null,
            0,
        )
        return Message(
            id,
            channelId,
            guildId,
            author,
            content,
            UtcDateTime(timestamp),
            editedTimestamp?.let(::UtcDateTime),
            false,
            false,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            null,
            false,
            null,
            0,
            null,
            null,
            null,
            null,
            null,
            emptyList(),
            emptyList(),
            null,
            null,
            null,
            emptyList(),
            null,
            false,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
        )
    }

    private fun parse(json: String?): Message? {
        json ?: return null
        return try {
            val api = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
                .fromJson(json, com.discord.api.message.Message::class.java)
            if (api.o() == id && api.g() == channelId) Message(api) else null
        } catch (_: Exception) {
            // Older or damaged payloads still have a usable text-only record.
            null
        }
    }

    companion object {
        fun fromMessage(message: Message, guildId: Long?): MessageRecord {
            val author = message.author
            return MessageRecord(
                message.id,
                message.channelId,
                guildId,
                author?.id ?: 0,
                // isBlank() casts Discord's obfuscated range iterator to Kotlin's IntIterator.
                author?.username?.takeIf { name -> name.any { !it.isWhitespace() } } ?: "Unknown user",
                author?.a()?.a(),
                author?.e() == true,
                message.content.orEmpty(),
                message.timestamp?.g() ?: ((message.id shr 22) + 1420070400000L),
                message.editedTimestamp?.g(),
                message = message,
            )
        }
    }
}
