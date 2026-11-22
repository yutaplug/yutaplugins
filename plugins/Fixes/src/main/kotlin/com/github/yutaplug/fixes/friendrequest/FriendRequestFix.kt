package com.github.yutaplug.fixes.friendrequest

import android.content.Context
import android.util.Base64
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ObservableExtensionsKt
import com.github.yutaplug.fixes.Fix
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.stream.JsonWriter
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

internal class FriendRequestFix : Fix(
    "FriendRequestFix",
    "Accepting friend requests",
    "Fixes accepting incoming friend requests.",
) {
    private val accepting = Collections.synchronizedMap(WeakHashMap<RestAPIParams.UserRelationship, Boolean>())

    override fun start(context: Context) {
        try {
            patcher.patch(
                ReflectiveTypeAdapterFactory.Adapter::class.java,
                "write",
                arrayOf(JsonWriter::class.java, Any::class.java),
                PreHook { call ->
                    val body = call.args[1] as? RestAPIParams.UserRelationship ?: return@PreHook
                    if (!accepting.containsKey(body)) return@PreHook
                    val writer = call.args[0] as JsonWriter
                    writer.c()
                    writer.n("type").A(1)
                    writer.n("confirm_stranger_request").I(true)
                    // Preserve the original token and captcha payload for Discord's retries.
                    writeString(writer, body, "friendToken", "friend_token")
                    writeString(writer, body, "captchaKey", "captcha_key")
                    writeString(writer, body, "captchaRqtoken", "captcha_rqtoken")
                    writer.f()
                    call.result = null
                },
            )
            patcher.patch(
                RestAPI::class.java,
                "addRelationship",
                arrayOf(
                    String::class.java,
                    Long::class.javaPrimitiveType!!,
                    Int::class.javaObjectType,
                    String::class.java,
                    CaptchaHelper.CaptchaPayload::class.java,
                ),
                PreHook { call ->
                    val userId = call.args[1] as Long
                    val type = call.args[2] as Int?
                    // Relationship type 3 is incoming. Never alter blocks or outgoing requests.
                    if (type != null && type != 1) return@PreHook
                    if (StoreStream.getUserRelationships().relationships[userId] != 3) return@PreHook
                    val captcha = call.args[4] as? CaptchaHelper.CaptchaPayload
                    val body = RestAPIParams.UserRelationship(
                        1,
                        call.args[3] as String?,
                        captcha?.captchaKey,
                        captcha?.captchaRqtoken,
                    )
                    accepting[body] = true
                    val location = JSONObject().put("location", call.args[0]).toString()
                    val encoded = Base64.encodeToString(location.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val request = (call.thisObject as RestAPI).addRelationship(userId, body, encoded)
                    call.result = ObservableExtensionsKt.restSubscribeOn(request, false)
                },
            )
        } catch (error: Throwable) {
            patcher.unpatchAll()
            accepting.clear()
            throw error
        }
    }

    private fun writeString(writer: JsonWriter, body: Any, field: String, name: String) {
        val value = ReflectUtils.getField(body, field) as? String ?: return
        writer.n(name).H(value)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        accepting.clear()
    }
}
