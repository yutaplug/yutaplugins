package com.github.yutaplug.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.app.AppLog
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import rx.Subscription
import rx.functions.Action1
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discord's debug log (AppLog), replacing its own Debug page. It lists the same entries, adds search,
 * a level filter and file export, and stays fast with thousands of entries: new entries arrive on any
 * thread, so they are batched onto the main thread and only the new rows are inserted.
 */
class DebugLogPage : SettingsPage() {
    private class Entry(val seq: Long, val item: AppLog.LoggedItem) {
        val priority: Int = item.k
        val text: String = item.l + (item.m?.let { "\n$it" } ?: "")
        val lower: String = text.lowercase()
    }

    private val all = ArrayList<Entry>()
    private var shown = ArrayList<Entry>()
    private val expanded = HashSet<Long>()
    private var nextSeq = 0L
    private var query = ""

    /** Entries waiting for the main thread; also guards [flushScheduled]. */
    private val pending = ArrayList<AppLog.LoggedItem>()
    private var flushScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flush() }
    private var subscription: Subscription? = null
    private var skipToMarker = false

    private var recycler: RecyclerView? = null
    private var layoutManager: LinearLayoutManager? = null
    private var levelView: TextView? = null
    private val adapter = Adapter()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // Thousands of entries, so rows must recycle instead of sitting in a scrolling layout.
        removeScrollView()
        setActionBarTitle("Debug log")
        setActionBarSubtitle(null)
        val ctx = requireContext()
        all.clear()
        shown = ArrayList()
        expanded.clear()

        headerBar.menu.apply {
            add("Share log").setOnMenuItemClickListener { share(); true }
            add("Save to Downloads").setOnMenuItemClickListener { save(); true }
            add("Copy log").setOnMenuItemClickListener { copy(); true }
            add("Clear log").setOnMenuItemClickListener { clearLog(); true }
        }

        val p = DimenUtils.dpToPx(16)
        val search = TextInput(ctx, "Search")
        search.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(editable: Editable?) {
                query = editable?.toString().orEmpty().trim().lowercase()
                refilter()
            }
        })
        linearLayout.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, p / 2, 0, p / 2) })

        levelView = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            val ripple = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            setPadding(0, DimenUtils.dpToPx(4), 0, DimenUtils.dpToPx(8))
            setOnClickListener { pickLevel() }
            linearLayout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

        val lm = LinearLayoutManager(ctx).apply { reverseLayout = true } // newest at the bottom, like Discord's
        layoutManager = lm
        recycler = RecyclerView(ctx).apply {
            layoutManager = lm
            adapter = this@DebugLogPage.adapter
            isVerticalScrollBarEnabled = true
            linearLayout.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        // AppLog's subject replays its last 5000 entries right here, during subscribe.
        skipToMarker = true
        subscription = try {
            AppLog.d.W(Action1<AppLog.LoggedItem> { enqueue(it) }, Action1<Throwable> { })
        } catch (e: Throwable) {
            Utils.showToast("Could not read the debug log: ${e.message ?: e.javaClass.simpleName}", true)
            null
        }
        handler.removeCallbacks(flushRunnable)
        flush()
        updateCount()
    }

    override fun onDestroyView() {
        subscription?.unsubscribe()
        subscription = null
        handler.removeCallbacks(flushRunnable)
        synchronized(pending) {
            pending.clear()
            flushScheduled = false
        }
        recycler = null
        layoutManager = null
        levelView = null
        super.onDestroyView()
    }

    // region Entries

    /** Called on whichever thread logged the entry. */
    private fun enqueue(item: AppLog.LoggedItem) {
        synchronized(pending) {
            pending.add(item)
            if (flushScheduled) return
            flushScheduled = true
        }
        handler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
    }

    private fun flush() {
        val batch: List<AppLog.LoggedItem>
        synchronized(pending) {
            batch = ArrayList(pending)
            pending.clear()
            flushScheduled = false
        }
        if (recycler == null) return
        var items = batch
        if (skipToMarker) {
            skipToMarker = false
            // Hide what was cleared earlier. If the marker is gone from the replay, everything is newer.
            val marker = clearedAfter
            if (marker != null) {
                val i = items.indexOfFirst { it === marker }
                if (i >= 0) items = items.subList(i + 1, items.size)
            }
        }
        if (items.isEmpty()) return

        val atBottom = (layoutManager?.findFirstVisibleItemPosition() ?: 0) <= 0
        val before = shown.size
        var added = 0
        for (item in items) {
            val entry = Entry(nextSeq++, item)
            all.add(entry)
            if (matches(entry)) {
                shown.add(entry)
                added++
            }
        }
        var removed = 0
        if (all.size > MAX_ENTRIES) {
            val drop = all.size - MAX_ENTRIES
            val cutoff = all[drop].seq
            all.subList(0, drop).clear()
            while (removed < shown.size && shown[removed].seq < cutoff) removed++
            if (removed > 0) shown.subList(0, removed).clear()
            expanded.removeAll { it < cutoff }
        }

        // Adapter position 0 is the newest entry, so new rows go in at 0 and dropped old rows leave the end.
        if (before == 0 || removed > before) {
            adapter.notifyDataSetChanged()
        } else {
            if (added > 0) adapter.notifyItemRangeInserted(0, added)
            if (removed > 0) adapter.notifyItemRangeRemoved(before + added - removed, removed)
        }
        if (atBottom) recycler?.scrollToPosition(0)
        updateCount()
    }

    private fun matches(entry: Entry) =
        entry.priority >= minPriority && (query.isEmpty() || entry.lower.contains(query))

    private fun refilter() {
        val out = ArrayList<Entry>(all.size)
        for (entry in all) if (matches(entry)) out.add(entry)
        shown = out
        adapter.notifyDataSetChanged()
        recycler?.scrollToPosition(0)
        updateCount()
    }

    private fun updateCount() {
        val level = levelIndex()
        val filter = if (level == 0) "All levels" else LEVELS[level].first + " and above"
        levelView?.text = "$filter. Showing ${shown.size} of ${all.size} entries. Tap to change the level."
    }

    private fun pickLevel() {
        SelectDialog().apply {
            title = "Level"
            items = Array(LEVELS.size) { if (it == 0) "All levels" else LEVELS[it].first + " and above" }
            onResultListener = { which ->
                minPriority = LEVELS[which].second
                refilter()
            }
        }.show(parentFragmentManager, "DebugLogLevel")
    }

    private fun clearLog() {
        flush()
        all.lastOrNull()?.let { clearedAfter = it.item }
        all.clear()
        shown = ArrayList()
        expanded.clear()
        adapter.notifyDataSetChanged()
        updateCount()
        Utils.showToast("Debug log cleared")
    }

    // endregion

    // region Export

    /** The redacted entries shown now, trimmed from the start to at most [maxChars]. */
    private fun export(entries: List<Entry>, filter: String, maxChars: Int = Int.MAX_VALUE): String {
        val header = StringBuilder()
        header.append("Discord debug log (Logcat plugin)\n")
        header.append("Exported: ").append(DATE.format(Date())).append('\n')
        header.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
            .append("), ").append(Build.MANUFACTURER).append('\n')
        header.append("Filter: ").append(filter).append('\n')
        header.append("Tokens, credentials, emails, URL query strings and IP addresses are redacted.\n\n")

        val blocks = ArrayList<String>(entries.size)
        for (entry in entries) {
            val letter = LEVELS.firstOrNull { it.second == entry.priority }?.first?.first() ?: '?'
            val text = StringBuilder().append(letter).append(' ').append(entry.item.l)
            entry.item.m?.let { text.append('\n').append(trimEnd(stackTrace(it))) }
            blocks.add(Redactor.redact(text.toString()))
        }
        val budget = maxChars - header.length
        var used = 0
        var start = blocks.size
        while (start > 0 && used + blocks[start - 1].length + 1 <= budget) {
            used += blocks[start - 1].length + 1
            start--
        }
        if (start > 0) header.append("[").append(start).append(" older entries left out to fit]\n")
        for (j in start until blocks.size) header.append(blocks[j]).append('\n')
        return header.toString()
    }

    private fun filterDescription(): String {
        val level = levelIndex()
        val levels = if (level == 0) "all levels" else LEVELS[level].first + " and above"
        return if (query.isEmpty()) levels else "$levels, search \"$query\""
    }

    // The exports run on a background thread, so they get a copy of the list and the app context.
    private fun copy() {
        val entries = ArrayList(shown)
        val filter = filterDescription()
        LogFiles.copy(requireContext(), "Discord debug log") { export(entries, filter, it) }
    }

    private fun share() {
        val entries = ArrayList(shown)
        val filter = filterDescription()
        LogFiles.share(this, "discord-debug-log", "Discord debug log") { export(entries, filter) }
    }

    private fun save() {
        val entries = ArrayList(shown)
        val filter = filterDescription()
        LogFiles.save(requireContext(), "discord-debug-log") { export(entries, filter) }
    }

    // endregion

    // region Rows

    private class Row(val root: View, val text: TextView) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Row>() {
        private var colors: IntArray? = null

        /** Discord's own colors for each priority (WidgetDebugging.Adapter.Item.getColor). */
        private fun color(ctx: Context, priority: Int): Int {
            val c = colors ?: intArrayOf(
                ColorCompat.getThemedColor(ctx, Utils.getResId("primary_300", "attr")),
                ColorCompat.getColor(ctx, Utils.getResId("status_green_600", "color")),
                ColorCompat.getColor(ctx, Utils.getResId("status_yellow_500", "color")),
                ColorCompat.getColor(ctx, Utils.getResId("status_red_500", "color")),
                ColorCompat.getThemedColor(ctx, Utils.getResId("primary_100", "attr")),
            ).also { colors = it }
            return when (priority) {
                2 -> c[0]
                3 -> c[1]
                5 -> c[2]
                6 -> c[3]
                else -> c[4]
            }
        }

        override fun getItemCount() = shown.size

        private fun entryAt(position: Int) = shown[shown.size - 1 - position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            // Discord's own Debug page row: the message and a divider.
            val root = LayoutInflater.from(parent.context)
                .inflate(Utils.getResId("widget_debugging_adapter_item", "layout"), parent, false)
            val row = Row(root, root.findViewById(Utils.getResId("log_message", "id")))
            root.setOnClickListener {
                val pos = row.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val seq = entryAt(pos).seq
                if (!expanded.remove(seq)) expanded.add(seq)
                notifyItemChanged(pos)
            }
            root.setOnLongClickListener {
                val pos = row.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val entry = entryAt(pos)
                val text = entry.item.l + (entry.item.m?.let { "\n" + trimEnd(stackTrace(it)) } ?: "")
                try {
                    val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Debug log entry", text))
                    Utils.showToast("Copied to clipboard")
                } catch (e: Throwable) {
                    Utils.showToast("Could not copy the entry: ${e.message ?: e.javaClass.simpleName}", true)
                }
                true
            }
            return row
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val entry = entryAt(position)
            val tv = holder.text
            tv.setTextColor(color(tv.context, entry.priority))
            val throwable = entry.item.m
            if (entry.seq in expanded) {
                tv.maxLines = Int.MAX_VALUE
                if (throwable == null) {
                    tv.text = entry.item.l
                } else {
                    // Like Aliucord's own Debug page addition: the stack trace in a smaller size.
                    val sb = SpannableStringBuilder(entry.item.l).append('\n')
                    val from = sb.length
                    sb.append(trimEnd(stackTrace(throwable)))
                    sb.setSpan(AbsoluteSizeSpan(12, true), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tv.text = sb
                }
            } else {
                tv.maxLines = 2
                tv.text = entry.text
            }
        }
    }

    // endregion

    companion object {
        /** AppLog's subject keeps this many entries. */
        private const val MAX_ENTRIES = 5000
        private const val FLUSH_DELAY_MS = 250L

        /** Name and AppLog priority (android.util.Log levels). */
        private val LEVELS = arrayOf("Verbose" to 2, "Debug" to 3, "Info" to 4, "Warning" to 5, "Error" to 6)
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** Discord's page hides verbose entries by default too. */
        private var minPriority = 3

        /** The newest entry when the log was last cleared; AppLog itself can't be cleared. */
        @Volatile private var clearedAfter: AppLog.LoggedItem? = null

        /** Log.getStackTraceString returns "" for UnknownHostException, so print it directly. */
        private fun stackTrace(t: Throwable): String {
            val sw = StringWriter()
            PrintWriter(sw).use { t.printStackTrace(it) }
            return sw.toString()
        }

        // Plain loops: Kotlin's trimEnd() and array indexOfFirst iterate ranges, which Discord's stripped
        // stdlib copies break at runtime.
        private fun trimEnd(s: String): String {
            var end = s.length
            while (end > 0 && Character.isWhitespace(s[end - 1])) end--
            return s.substring(0, end)
        }

        private fun levelIndex(): Int {
            var i = 0
            while (i < LEVELS.size) {
                if (LEVELS[i].second == minPriority) return i
                i++
            }
            return 0
        }
    }
}
