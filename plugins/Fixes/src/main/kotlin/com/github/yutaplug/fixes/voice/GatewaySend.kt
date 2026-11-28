package com.github.yutaplug.fixes.voice

import com.discord.gateway.io.Outgoing
import com.discord.stores.StoreStream

/**
 * Sends a payload on Discord's main gateway socket, for opcodes 126.21 doesn't know (like 31, Request Soundboard
 * Sounds, and 43, Request Channel Info). Uses the socket's own private `send` with its null-omitting Gson.
 */
internal object GatewaySend {
    /** Returns false when there's no connected socket (yet). Throws on reflection failures. */
    fun send(op: Int, payload: Any): Boolean {
        val store = StoreStream.getGatewaySocket()
        val socketField = store.javaClass.getDeclaredField("socket").apply { isAccessible = true }
        val socket = socketField.get(store) ?: return false
        val gson = socket.javaClass.getDeclaredField("gsonOmitNulls").apply { isAccessible = true }.get(null)
        val send = socket.javaClass.getDeclaredMethod(
            "send",
            Outgoing::class.java,
            Boolean::class.javaPrimitiveType,
            Class.forName("com.google.gson.Gson"),
        ).apply { isAccessible = true }
        send.invoke(socket, Outgoing(op, payload), true, gson)
        return true
    }
}
