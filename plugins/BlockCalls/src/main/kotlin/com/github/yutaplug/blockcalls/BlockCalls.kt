package com.github.yutaplug.blockcalls

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.discord.models.domain.ModelCall
import com.discord.stores.StoreCallsIncoming
import com.discord.stores.StoreStream
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.fcm.NotificationData
import com.discord.utilities.fcm.NotificationRenderer

@AliucordPlugin
class BlockCalls : Plugin() {
    override fun start(context: Context) {
        // The incoming call screen (and its ringing sound) opens when this store marks a
        // channel as ringing us. Skip that, but still let it clear calls that stop ringing.
        patcher.patch(
            StoreCallsIncoming::class.java,
            "handleCallCreateOrUpdate",
            arrayOf(ModelCall::class.java),
            PreHook { frame ->
                val call = frame.args[0] as ModelCall
                val me = StoreStream.getUsers().me?.id ?: return@PreHook
                if (call.ringing?.contains(me) == true) frame.result = null
            },
        )

        // Push notifications for calls (shown while the app is in the background or closed,
        // including the full-screen incoming call) and the in-app call popup.
        patcher.patch(
            NotificationRenderer::class.java,
            "display",
            arrayOf(Context::class.java, NotificationData::class.java, NotificationClient.SettingsV2::class.java),
            PreHook { frame -> if (isCallRing(frame.args[1])) frame.result = null },
        )
        patcher.patch(
            NotificationRenderer::class.java,
            "displayInApp",
            arrayOf(Context::class.java, NotificationData::class.java),
            PreHook { frame -> if (isCallRing(frame.args[1])) frame.result = null },
        )
    }

    private fun isCallRing(data: Any?) = (data as? NotificationData)?.type == NotificationData.TYPE_CALL_RING

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
