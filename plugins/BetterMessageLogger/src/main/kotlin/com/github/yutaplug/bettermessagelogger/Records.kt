package com.github.yutaplug.bettermessagelogger

import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.GsonUtils.toJson
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.premium.PremiumTier
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.message.Message
import com.discord.nullserializable.NullSerializable
import com.discord.api.message.Message as ApiMessage

/** A replaced version of a message: its text, and the attachments the edit removed. */
internal data class MessageEdit(
    val timestamp: Long,
    val content: String,
    val removed: List<MessageAttachment> = emptyList(),
) {
    fun removedJson(): String? = if (removed.isEmpty()) null else Json.write(removed.toTypedArray())

    companion object {
        fun removedFrom(json: String?): List<MessageAttachment> =
            json?.let { Json.read(it, Array<MessageAttachment>::class.java) }?.toList().orEmpty()
    }
}

/**
 * An immutable snapshot of a message, safe to pass between the store, UI and database threads. [message] is the
 * live model while Discord still has it; [payload] is its serialized form, which is what the database keeps.
 */
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
    /** Only deleted or edited messages are worth keeping; everything else lives in the bounded live cache. */
    val logged get() = deleted || edits.isNotEmpty()

    fun serialized(): String? = message?.let { Json.write(it.synthesizeApiMessage()) } ?: payload

    /** A private copy that never shares mutable attachment or embed objects with Discord's stores. */
    fun detached(): Message = parse(serialized()) ?: fallback()

    fun toMessage(): Message = message ?: parse(payload) ?: fallback()

    fun attachments(): List<MessageAttachment> = toMessage().attachments.orEmpty()

    private fun parse(json: String?): Message? {
        json ?: return null
        val api = Json.read(json, ApiMessage::class.java) ?: return null
        // Damaged or mismatched payloads fall back to a text-only message.
        return if (api.o() == id && api.g() == channelId) Message(api) else null
    }

    private fun fallback(): Message {
        val author = User(
            authorId, authorName, authorAvatar?.let { NullSerializable.b(it) }, null, "0000", null, null, bot,
            false, null, null, null, null, null, null, null, null, PremiumTier.NONE, null, null, null, null, 0,
        )
        return Message(
            id, channelId, guildId, author, content, UtcDateTime(timestamp), editedTimestamp?.let(::UtcDateTime),
            false, false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, false, null, 0,
            null, null, null, null, null, emptyList(), emptyList(), null, null, null, emptyList(), null, false, null,
            false, null, null, null, null, null, null,
        )
    }

    companion object {
        fun of(message: Message, guildId: Long?): MessageRecord {
            val author = message.author
            return MessageRecord(
                id = message.id,
                channelId = message.channelId,
                guildId = guildId,
                authorId = author?.id ?: 0,
                authorName = author?.username?.takeIf { it.trim().isNotEmpty() } ?: "Unknown user",
                authorAvatar = author?.a()?.a(),
                bot = author?.e() == true,
                content = message.content.orEmpty(),
                timestamp = message.timestamp?.g() ?: snowflakeTime(message.id),
                editedTimestamp = message.editedTimestamp?.g(),
                message = message,
            )
        }

        fun snowflakeTime(id: Long) = (id shr 22) + 1420070400000L
    }
}

/** Discord's own gateway Gson, which knows how to (de)serialize its API models. */
internal object Json {
    private val gson get() = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance

    fun write(value: Any): String = gson.toJson(value)

    fun <T> read(json: String, type: Class<T>): T? = try {
        gson.fromJson(json, type)
    } catch (_: Exception) {
        null
    }
}
