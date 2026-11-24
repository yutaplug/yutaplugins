package com.github.yutaplug.fixes.servernickname

import android.content.Context
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.github.yutaplug.fixes.Fix
import okhttp3.Request
import okhttp3.RequestBody
import okio.ByteString
import org.json.JSONObject

/** Ported from TsyQax's ServerNicknameFix: https://github.com/tsyqax/aliucord_plugins_tsq */
internal class ServerNicknameFix :
    Fix(
        "ServerNicknameFix",
        "Server nickname",
        "Fixes not being able to change your server nickname.",
        author = "TsyQax",
    ) {
    override fun start(context: Context) {
        // Request.Builder.build(): non-Nitro users may not send a per-server bio, even an empty one.
        patcher.patch(
            Request.a::class.java.getDeclaredMethod("a"),
            Hook { param ->
                if (StoreStream.getUsers().me.premiumTier.ordinal == 4) return@Hook
                val request = param.result as? Request ?: return@Hook
                val url = request.b ?: return@Hook
                if (!url.toString().contains("members/@me")) return@Hook
                val body = request.e ?: return@Hook
                try {
                    val dataField = body.javaClass.getDeclaredField("a").apply { isAccessible = true }
                    val data = dataField.get(body) as? ByteString ?: return@Hook
                    val content = data.q() ?: return@Hook
                    val json = try {
                        JSONObject(content)
                    } catch (_: Exception) {
                        return@Hook
                    }
                    if (!json.has("bio") || json.optString("bio") != "") return@Hook
                    json.remove("bio")
                    val newBody = RequestBody.create(body.contentType(), json.toString())
                    param.result = Request(url, request.c, request.d, newBody, request.f)
                } catch (error: Exception) {
                    logger.error("Could not patch the nickname request", error)
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
