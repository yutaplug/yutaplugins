package com.github.yutaplug.fixes.updater

import com.aliucord.Logger
import com.aliucord.PluginManager
import com.aliucord.entities.Plugin
import com.aliucord.updater.ManagerBuild
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Checks Aliucord and every plugin with an updater URL for updates, straight from GitHub. */
internal class UpdateChecker(private val logger: Logger) {
    private val files = GitHubFiles(logger)

    fun check(): CheckResult {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val coreFuture = pool.submit(Callable { checkCore() })

            val plugins = PluginManager.plugins.values.filter { !isCorePlugin(it) && it.__filename != null }
            val byRepo = LinkedHashMap<String, MutableList<Plugin>>()
            val untracked = ArrayList<PluginInfo>()
            for (plugin in plugins) {
                val url = plugin.manifest.updateUrl?.trim()
                if (url == null || url.isEmpty()) {
                    untracked.add(PluginInfo(plugin.name, plugin.manifest.version ?: "?", null))
                } else {
                    byRepo.getOrPut(url) { ArrayList() }.add(plugin)
                }
            }
            val repoFutures = LinkedHashMap<String, Future<JSONObject>>()
            for (url in byRepo.keys) repoFutures[url] = pool.submit(Callable { JSONObject(files.text(url)) })

            val (core, latestCore, coreError) = try {
                coreFuture.get(60, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                logger.error("Could not check for Aliucord updates", e)
                Triple(null, null, "Couldn't check: ${describe(e)}")
            }

            val updates = ArrayList<PluginUpdate>()
            val upToDate = ArrayList<PluginInfo>()
            val errors = LinkedHashMap<String, String>()
            for ((url, repoPlugins) in byRepo) {
                val repo = GitHubFiles.parse(url)?.repoName ?: hostOf(url)
                val json = try {
                    repoFutures[url]!!.get(60, TimeUnit.SECONDS)
                } catch (e: Throwable) {
                    logger.warn("Could not fetch $url: ${describe(e)}")
                    val reason = describe(e)
                    for (plugin in repoPlugins) errors[plugin.name] = reason
                    continue
                }
                for (plugin in repoPlugins) {
                    val name = plugin.manifest.name ?: plugin.name
                    val current = plugin.manifest.version ?: "0.0.0"
                    val entry = json.optJSONObject(name) ?: json.optJSONObject(plugin.name)
                    val build = entry?.let { PluginBuild.parse(it) }
                    if (build == null) {
                        errors[plugin.name] = "Not listed in $repo's updater"
                        continue
                    }
                    if (Versions.newer(build.version, current) || Versions.parse(current) == null) {
                        updates.add(
                            PluginUpdate(plugin.name, plugin.__filename!!, current, build, repo, latestCore),
                        )
                    } else {
                        upToDate.add(PluginInfo(plugin.name, current, repo))
                    }
                }
            }
            updates.sortBy { it.name.lowercase() }
            upToDate.sortBy { it.name.lowercase() }
            untracked.sortBy { it.name.lowercase() }
            return CheckResult(System.currentTimeMillis(), core, latestCore, coreError, updates, upToDate, errors, untracked)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun checkCore(): Triple<CoreUpdate?, String?, String?> {
        if (CoreBuilds.isCustomCoreLoaded()) {
            return Triple(null, null, "You're running a custom Aliucord core, so it isn't updated.")
        }
        val data = JSONObject(files.text(CoreBuilds.DATA_URL))
        val latest = data.optString("coreVersion", "")
        if (latest.isEmpty()) return Triple(null, null, "GitHub didn't list an Aliucord version.")
        if (!Versions.newer(latest, Versions.core)) return Triple(null, latest, null)

        val discordVersion = data.optString("versionCode", "").toIntOrNull() ?: 0
        val reason = when {
            discordVersion > Versions.discord -> "It needs a newer Discord base."
            !has { ManagerBuild.hasInjector(data.optString("injectorVersion", "0.0.0")) } -> "It needs a newer injector."
            !has { ManagerBuild.hasPatches(data.optString("patchesVersion", "0.0.0")) } -> "It needs newer patches."
            !has { ManagerBuild.hasKotlin(data.optString("kotlinVersion", "0.0.0")) } -> "It needs a newer Kotlin library."
            else -> null
        }?.let { "$it Reinstall Aliucord with Aliucord Manager to get it." }
        return Triple(CoreUpdate(Versions.core, latest, reason), latest, null)
    }

    /** The resolver this check used, so downloads come from the same commits as the versions it found. */
    fun files(): GitHubFiles = files

    private inline fun has(check: () -> Boolean): Boolean = try {
        check()
    } catch (_: Throwable) {
        true
    }

    companion object {
        private val corePluginClass: Class<*>? = try {
            Class.forName("com.aliucord.entities.CorePlugin")
        } catch (_: Throwable) {
            null
        }

        // CorePlugin is internal to Aliucord.
        private fun isCorePlugin(plugin: Plugin) = corePluginClass?.isInstance(plugin) == true

        fun describe(e: Throwable): String {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            return when (cause) {
                is GitHubFiles.NotFound -> "The updater file wasn't found"
                is java.net.UnknownHostException -> "No internet connection"
                is java.net.SocketTimeoutException -> "Timed out"
                is java.util.concurrent.TimeoutException -> "Timed out"
                is org.json.JSONException -> "The updater file isn't valid"
                else -> cause.message ?: cause.javaClass.simpleName
            }
        }

        private fun hostOf(url: String): String = try {
            java.net.URL(url).host
        } catch (_: Throwable) {
            url
        }
    }
}
