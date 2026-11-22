package com.github.yutaplug.fixes.searchhistory

import android.content.Context
import com.aliucord.patcher.PreHook
import com.discord.stores.StoreSearch
import com.discord.utilities.persister.Persister
import com.discord.utilities.search.history.MGPreferenceSearchHistoryCache
import com.discord.utilities.search.history.MGPreferenceSearchHistoryCache.TargetHistory
import com.discord.utilities.search.query.node.QueryNode
import com.github.yutaplug.fixes.Fix
import java.util.LinkedList

/** Persists search-history changes immediately without mutating published history. */
internal class SearchHistoryFix : Fix(
    "SearchHistoryFix",
    "Search history",
    "Fixes cleared search history coming back after an app restart.",
) {
    override fun start(context: Context) {
        try {
            patcher.patch(
                MGPreferenceSearchHistoryCache::class.java,
                "clear",
                arrayOf(StoreSearch.SearchTarget::class.java),
                PreHook { frame ->
                    val cache = frame.thisObject as MGPreferenceSearchHistoryCache
                    val target = frame.args[0] as StoreSearch.SearchTarget
                    // getAndSet holds the Persister lock across the read, update and write.
                    backingCache(cache).getAndSet(true) { history ->
                        clearHistory(history, target)
                    }
                    frame.result = null
                },
            )
            patcher.patch(
                MGPreferenceSearchHistoryCache::class.java,
                "persistQuery",
                arrayOf(StoreSearch.SearchTarget::class.java, List::class.java),
                PreHook { frame ->
                    val cache = frame.thisObject as MGPreferenceSearchHistoryCache
                    val target = frame.args[0] as StoreSearch.SearchTarget
                    @Suppress("UNCHECKED_CAST")
                    val query = (frame.args[1] as List<QueryNode>).toList()
                    backingCache(cache).getAndSet(true) { history ->
                        addQuery(history, target, query)
                    }
                    frame.result = null
                },
            )
        } catch (error: Throwable) {
            // Do not leave only half of the replacement installed if setup fails.
            patcher.unpatchAll()
            throw error
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun backingCache(cache: MGPreferenceSearchHistoryCache): Persister<LinkedList<TargetHistory>> =
        MGPreferenceSearchHistoryCache.`access$getBackingCache$p`(cache) as Persister<LinkedList<TargetHistory>>

    internal companion object {
        private const val MAX_SEARCH_TARGETS = 10
        private const val MAX_QUERIES_PER_TARGET = 5

        internal fun clearHistory(
            history: LinkedList<TargetHistory>,
            target: StoreSearch.SearchTarget,
        ): LinkedList<TargetHistory> = LinkedList(history.filter { it.searchTarget != target })

        internal fun addQuery(
            history: LinkedList<TargetHistory>,
            target: StoreSearch.SearchTarget,
            query: List<QueryNode>,
        ): LinkedList<TargetHistory> {
            // Copy the query and its history so callers and existing observers cannot
            // change one another's lists. Preserve Discord's ordering and limits.
            val queries = LinkedList<List<QueryNode>>()
            queries.add(query.toList())
            history.firstOrNull { it.searchTarget == target }?.recentQueries
                ?.filter { it != query }
                ?.take(MAX_QUERIES_PER_TARGET - 1)
                ?.forEach { queries.add(it.toList()) }

            val updated = LinkedList<TargetHistory>()
            updated.add(TargetHistory(target, queries))
            history.filter { it.searchTarget != target }
                .take(MAX_SEARCH_TARGETS - 1)
                .forEach { updated.add(it) }
            return updated
        }
    }
}
