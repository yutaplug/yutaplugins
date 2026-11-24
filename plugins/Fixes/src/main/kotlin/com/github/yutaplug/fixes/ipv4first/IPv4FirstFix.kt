package com.github.yutaplug.fixes.ipv4first

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.aliucord.patcher.PreHook
import com.github.yutaplug.fixes.Fix
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Discord 126.21's OkHttp 4.8 and Android's HttpURLConnection (used by Aliucord's Http, so UploadSize uploads, and
 * by Fresco) try a host's addresses one by one, with no fast IPv6 -> IPv4 fallback. On networks without working
 * IPv6 where IPv6 packets are silently dropped instead of rejected, each IPv6 address first waits for its connect
 * timeout (10 s in OkHttp, ~35 s with no timeout set): a diagnostics log showed a 56 KB upload taking 107 s
 * because of three IPv6 attempts. Newer Discord falls back quickly, so it isn't affected.
 *
 * When the active network has no public IPv6 address, IPv6 connects can't work, so this makes them fail
 * immediately (as the kernel does when there's no IPv6 route) and the caller moves on to the next (IPv4) address.
 * Reordering DNS answers isn't enough: most lookups bypass a hook on InetAddress.getAllByName (ART inlines it into
 * its callers), while Socket.connect is reliably hooked.
 */
internal class IPv4FirstFix :
    Fix(
        "IPv4First",
        "Slow uploads and downloads",
        "Skips IPv6 connections when your network has no IPv6. Fixes uploads and media loading slowly " +
            "because the app first waits for IPv6 connections to time out.",
    ) {
    private var connectivity: ConnectivityManager? = null

    override fun start(context: Context) {
        connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        patcher.patch(
            Socket::class.java.getDeclaredMethod("connect", SocketAddress::class.java, Int::class.javaPrimitiveType),
            PreHook { param ->
                val target = (param.args[0] as? InetSocketAddress)?.address as? Inet6Address ?: return@PreHook
                // Local and private (ULA) IPv6 destinations can work without a public address, e.g. through a VPN.
                if (target.isLinkLocalAddress || target.isSiteLocalAddress || target.isLoopbackAddress ||
                    (target.address[0].toInt() and 0xfe) == 0xfc
                ) {
                    return@PreHook
                }
                if (hasPublicIPv6()) return@PreHook
                param.throwable = ConnectException("IPv6 skipped, network has no IPv6: ENETUNREACH (Network is unreachable)")
            },
        )
    }

    /** Whether the active network has a public IPv6 address. Unknown counts as yes, so nothing changes. */
    private fun hasPublicIPv6(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            val cm = connectivity ?: return true
            val network = cm.activeNetwork ?: return true
            val link = cm.getLinkProperties(network) ?: return true
            link.linkAddresses.any {
                val a = it.address
                a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress &&
                    (a.address[0].toInt() and 0xfe) != 0xfc
            }
        } catch (e: Throwable) {
            true
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        connectivity = null
    }
}
