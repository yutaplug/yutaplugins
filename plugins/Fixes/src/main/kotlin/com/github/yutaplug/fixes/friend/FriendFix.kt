package com.github.yutaplug.fixes.friend

import android.content.Context
import com.aliucord.patcher.Hook
import com.aliucord.patcher.InsteadHook
import com.discord.widgets.friends.WidgetFriendsAddById
import com.github.yutaplug.fixes.Fix
import de.robv.android.xposed.XposedBridge
import okhttp3.Request
import okhttp3.RequestBody
import okio.ByteString

/** Ported from TsyQax's FriendFix: https://github.com/tsyqax/aliucord_plugins_tsq */
internal class FriendFix :
    Fix(
        "FriendFix",
        "Sending friend requests",
        "Fixes sending friend requests to users without a discriminator.",
        author = "TsyQax",
    ) {
    override fun start(context: Context) {
        // Request.Builder.build(): replace the placeholder discriminator with null.
        patcher.patch(
            Request.a::class.java.getDeclaredMethod("a"),
            Hook { param ->
                val request = param.result as? Request ?: return@Hook
                val url = request.b ?: return@Hook
                if (!url.toString().contains("relationships")) return@Hook
                val body = request.e ?: return@Hook
                try {
                    val dataField = body.javaClass.getDeclaredField("a").apply { isAccessible = true }
                    val data = dataField.get(body) as? ByteString ?: return@Hook
                    val content = data.q() ?: return@Hook
                    if (!content.contains(PLACEHOLDER.toString())) return@Hook
                    val patched = content.replace("\"$PLACEHOLDER\"", "null").replace(PLACEHOLDER.toString(), "null")
                    val newBody = RequestBody.create(body.contentType(), patched)
                    param.result = Request(url, request.c, request.d, newBody, request.f)
                } catch (error: Exception) {
                    logger.error("Could not patch the friend request body", error)
                }
            },
        )

        // Usernames without "#1234" get a placeholder discriminator, removed again above.
        val result = WidgetFriendsAddById.Companion.UserNameDiscriminator::class.java
            .getDeclaredConstructor(String::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }
        patcher.patch(
            WidgetFriendsAddById.Companion::class.java.getDeclaredMethod(
                "extractUsernameAndDiscriminator",
                CharSequence::class.java,
            ),
            InsteadHook { param ->
                val input = param.args.getOrNull(0)?.toString() ?: ""
                try {
                    if (!input.contains("#")) {
                        result.newInstance(input, PLACEHOLDER)
                    } else {
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                } catch (error: Exception) {
                    logger.error("Could not parse the username", error)
                    null
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private companion object {
        const val PLACEHOLDER = 12345
    }
}
