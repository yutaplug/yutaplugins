package com.github.yutaplug.rawgithubupdater

import android.content.Context
import com.aliucord.Http
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import java.util.regex.Pattern

@AliucordPlugin
class RawGitHubUpdater : Plugin() {
    // https://cdn.jsdelivr.net/gh/<user>/<repo>[@<ref>]/<path>
    // java.util.regex rather than kotlin.text.Regex, which is broken by Discord's obfuscated stdlib.
    private val jsDelivrPattern = Pattern.compile(
        "^https?://(?:cdn|fastly|gcore|testingcf|originfastly)\\.jsdelivr\\.net/gh/([^/@]+)/([^/@]+)(?:@([^/]+))?/(.+)$",
    )

    private fun toRawGitHub(url: String): String {
        val m = jsDelivrPattern.matcher(url)
        if (!m.matches()) return url
        val branch = m.group(3) ?: "HEAD"
        return "https://raw.githubusercontent.com/${m.group(1)}/${m.group(2)}/$branch/${m.group(4)}"
    }

    override fun start(context: Context) {
        // Every Http.Request constructor funnels into (url, method), including the updater's
        // update info fetch (which Aliucord itself rewrites to jsDelivr) and plugin zip downloads.
        patcher.patch(
            Http.Request::class.java.getDeclaredConstructor(String::class.java, String::class.java),
            PreHook { param ->
                val url = param.args[0] as? String ?: return@PreHook
                val rewritten = toRawGitHub(url)
                if (rewritten != url) {
                    logger.debug("Rewrote $url -> $rewritten")
                    param.args[0] = rewritten
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
