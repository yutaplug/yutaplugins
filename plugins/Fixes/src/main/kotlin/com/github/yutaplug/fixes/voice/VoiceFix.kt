package com.github.yutaplug.fixes.voice

import android.content.Context
import com.aliucord.api.CommandsAPI
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.github.yutaplug.fixes.Fix
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Collections
import java.util.WeakHashMap

/**
 * Voice and video calls with Discord's current voice protocol: voice gateway v8, end-to-end encryption (DAVE) and
 * the rtpsize transport encryption modes. Discord 126.21's voice engine is left as it is behind a local UDP [Relay];
 * [VoiceGateway] does the protocol work on the app's own voice gateway socket (b.a.q.n0.a).
 */
internal class VoiceFix :
    Fix(
        "DaveVoice",
        "Voice and video calls",
        "Makes voice calls, server voice channels, camera video and screen sharing work again, with the " +
            "end-to-end encryption (DAVE) Discord now requires. /davevoice shows the connection status.",
    ) {
    private val gateways: MutableMap<b.a.q.n0.a, VoiceGateway> = Collections.synchronizedMap(WeakHashMap())
    private val connecting = ThreadLocal<Boolean>()

    private fun gateway(socket: b.a.q.n0.a): VoiceGateway =
        synchronized(gateways) { gateways.getOrPut(socket) { VoiceGateway(socket) } }

    override fun start(context: Context) {
        val socketClass = b.a.q.n0.a::class.java

        // Voice gateway v8 instead of v5: the connect method builds the URL with "?v=5".
        patcher.patch(socketClass.getDeclaredMethod("e"), PreHook { connecting.set(true) })
        patcher.patch(socketClass.getDeclaredMethod("e"), Hook { connecting.set(false) })
        patcher.patch(
            Request.a::class.java.getDeclaredMethod("f", String::class.java),
            PreHook { param ->
                val url = param.args[0] as? String ?: return@PreHook
                if (connecting.get() == true && url.endsWith("?v=5")) {
                    param.args[0] = url.substring(0, url.length - 4) + "?v=8"
                }
            },
        )

        patcher.patch(
            socketClass.getDeclaredMethod("onMessage", WebSocket::class.java, String::class.java),
            PreHook { param ->
                val text = param.args[1] as? String ?: return@PreHook
                val rewritten = gateway(param.thisObject as b.a.q.n0.a).onText(text)
                if (rewritten == null) param.result = null else param.args[1] = rewritten
            },
        )

        patcher.patch(
            WebSocketListener::class.java.getDeclaredMethod("onMessage", WebSocket::class.java, ByteString::class.java),
            PreHook { param ->
                val socket = param.thisObject as? b.a.q.n0.a ?: return@PreHook
                val bytes = (param.args[1] as ByteString).l()
                gateway(socket).onBinary(bytes.copyOf())
            },
        )

        patcher.patch(
            socketClass.getDeclaredMethod("n", Int::class.javaPrimitiveType, Any::class.java),
            PreHook { param ->
                val socket = param.thisObject as b.a.q.n0.a
                try {
                    if (gateway(socket).outgoing(param.args[0] as Int, param.args[1])) param.result = null
                } catch (e: Throwable) {
                    VoiceGateway.logger.error("sending op ${param.args[0]} failed", e)
                }
            },
        )

        // Connection closed for good: stop the relay.
        val cleanup = Hook { param ->
            synchronized(gateways) { gateways.remove(param.thisObject as b.a.q.n0.a) }?.close()
        }
        patcher.patch(socketClass.getDeclaredMethod("c"), cleanup)
        patcher.patch(
            socketClass.getDeclaredMethod(
                "f",
                Boolean::class.javaPrimitiveType,
                Int::class.javaObjectType,
                String::class.java,
            ),
            cleanup,
        )

        plugin.commandsApi.registerCommand(COMMAND, "Show the voice connection and end-to-end encryption status") {
            val list = synchronized(gateways) { ArrayList(gateways.values) }
            val text = if (list.isEmpty()) "Not in a call." else list.joinToString("\n\n") { it.status() }
            CommandsAPI.CommandResult("```\n$text\n```", null, false)
        }
    }

    override fun stop(context: Context) {
        plugin.commandsApi.unregisterCommand(COMMAND)
        val list = synchronized(gateways) { ArrayList(gateways.values).also { gateways.clear() } }
        for (g in list) g.close()
    }

    private companion object {
        const val COMMAND = "davevoice"
    }
}
