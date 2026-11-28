package com.github.yutaplug.fixes.voiceself

import android.content.Context
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.guild.Guild
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelPayload
import com.discord.stores.StoreVoiceParticipants
import com.discord.stores.StoreVoiceStates
import com.github.yutaplug.fixes.Fix

/**
 * Discord 126.21 hides you from voice channels when you're in one on another device: StoreVoiceStates'
 * updateVoiceState drops (and removes) any voice state that is yours (`myUserId`) but from a different gateway
 * session. Current Discord shows you there.
 *
 * The filter is the only use of `myUserId`, so this clears it once the session opens, and re-adds each server's voice
 * states (READY's were already filtered) through the public handleGuildAdd. That filter also kept call screens from
 * thinking this device is in the call, since CallModel.isConnected looks for your own participant's voice state; so
 * the participant built for you gets no voice state when it's from another session, as before.
 */
internal class VoiceSelfFix :
    Fix(
        "VoiceOtherDevice",
        "Yourself in voice channels",
        "Shows you in a voice channel's member list when you're in it on another device, like newer Discord.",
        // The voice states come with the session, so it takes effect from the next connection.
        requiresRestart = true,
    ) {
    override fun start(context: Context) {
        val myUserId = StoreVoiceStates::class.java.getDeclaredField("myUserId").apply { isAccessible = true }
        val sessionField = StoreVoiceStates::class.java.getDeclaredField("sessionId").apply { isAccessible = true }

        patcher.patch(
            StoreVoiceStates::class.java.getDeclaredMethod("handleConnectionOpen", ModelPayload::class.java),
            Hook { param ->
                val store = param.thisObject as StoreVoiceStates
                myUserId.set(store, null)
                val guilds: List<Guild>? = (param.args[0] as ModelPayload).guilds
                guilds?.forEach { store.handleGuildAdd(it) }
            },
        )

        patcher.patch(
            StoreVoiceParticipants.VoiceUser::class.java.declaredConstructors.first { it.parameterTypes.size == 11 },
            PreHook { param ->
                val isMe = param.args[4] as? Boolean ?: return@PreHook
                val state = param.args[1] as? VoiceState ?: return@PreHook
                if (!isMe) return@PreHook
                val store = com.discord.stores.StoreStream.getVoiceStates()
                val mySession = sessionField.get(store) as? String ?: return@PreHook
                if (state.k() != mySession) param.args[1] = null
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
