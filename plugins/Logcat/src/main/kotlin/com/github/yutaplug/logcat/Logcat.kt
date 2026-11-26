package com.github.yutaplug.logcat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import com.aliucord.Utils
import com.discord.widgets.debugging.WidgetDebugging
import de.robv.android.xposed.XC_MethodHook
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import b.a.d.j as ScreenLauncher

@AliucordPlugin
class Logcat : Plugin() {
    init {
        settingsTab = SettingsTab(LogcatSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val log = Logger("Logcat")
    private val myPid = android.os.Process.myPid()

    /** Guards every field below, and the session file. */
    private val lock = Any()

    /** This process's lines. */
    private val buffer = java.util.ArrayDeque<String>(MAX_LINES)

    /** The previous session's lines, as saved to [PREVIOUS_FILE] while it ran. */
    private val previousSaved = ArrayList<String>()

    /**
     * Lines from earlier processes that the device log still had but the saved file is missing,
     * usually the crash itself, which is logged right before the process dies.
     */
    private val previousRecovered = ArrayList<String>()
    private val previousSet = HashSet<String>()

    private var sessionDir: File? = null
    private var writer: Writer? = null
    private var writtenChars = 0L

    @Volatile private var running = false

    @Volatile private var process: Process? = null

    private var thread: Thread? = null

    @Volatile var status: String = "Not started"
        private set

    @Volatile var totalLines = 0L
        private set

    var startedAt = 0L
        private set

    /** Index into [LEVELS]; lines below this level are left out of exports. */
    var minLevel: Int
        get() = settings.getInt("minLevel", 0).coerceIn(0, LEVELS.size - 1)
        set(value) = settings.setInt("minLevel", value)

    /** Comma-separated tag substrings; empty means every tag. */
    var tagFilter: String
        get() = settings.getString("tags", "").orEmpty()
        set(value) = settings.setString("tags", value.trim())

    private val timings by lazy { NetworkTimings(patcher, log) }

    /** Whether Discord's network timings are added to the log. */
    var networkTimings: Boolean
        get() = settings.getBool("networkTimings", true)
        set(value) {
            settings.setBool("networkTimings", value)
            if (!running) return
            if (value) {
                timings.start()
            } else {
                timings.stop()
                // NetworkTimings.stop() removes every hook of this plugin, this one too.
                hookDebugLog()
            }
        }

    fun networkSummary(): String = if (timings.running) timings.summary() else "Off"

    /** Whether Aliucord's Open Debug Log entry (and anything else opening Discord's page) opens [DebugLogPage]. */
    var replaceDebugLog: Boolean
        get() = settings.getBool("replaceDebugLog", true)
        set(value) = settings.setBool("replaceDebugLog", value)

    private var debugLogHook: Runnable? = null

    /**
     * Every screen, Discord's Debug page included (Aliucord's entry uses Utils.openPage), is launched
     * through this one method, so swap the screen there.
     */
    private fun hookDebugLog() {
        debugLogHook?.run()
        debugLogHook = try {
            patcher.patch(
                ScreenLauncher::class.java.getDeclaredMethod(
                    "d",
                    Context::class.java,
                    Class::class.java,
                    Parcelable::class.java,
                ),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[1] != WidgetDebugging::class.java || !replaceDebugLog) return
                        val context = param.args[0] as? Context ?: return
                        try {
                            Utils.openPageWithProxy(context, DebugLogPage())
                            param.result = null
                        } catch (e: Throwable) {
                            log.error("Could not open the debug log", e)
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            log.error("Could not replace the Debug Log page", e)
            null
        }
    }

    override fun start(context: Context) {
        hookDebugLog()
        // After every plugin has loaded, so DebugLogEnhancer can be stopped and unloaded cleanly.
        Utils.mainThread.post { ReplacedPlugins.uninstall(log) }
        running = true
        startedAt = System.currentTimeMillis()
        openSession(context)
        DiagLog.sink = { addOwn(NetworkTimings.TAG, it) }
        if (networkTimings) timings.start()
        val canReadAll = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
        thread = Thread({ readLoop(canReadAll) }, "Logcat-reader").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop(context: Context) {
        running = false
        debugLogHook?.run()
        debugLogHook = null
        timings.stop()
        DiagLog.sink = null
        DiagLog.clear()
        process?.destroy()
        process = null
        thread?.interrupt()
        thread = null
        synchronized(lock) {
            closeWriter()
        }
        status = "Stopped"
    }

    // region Session files

    /**
     * Moves the last process's log to previous.log (unless this is the same process, e.g. the plugin was
     * toggled), loads it, and starts a fresh current.log for this process.
     */
    private fun openSession(context: Context) {
        val dir = File(context.filesDir, "logcat-session").apply { mkdirs() }
        val current = File(dir, CURRENT_FILE)
        val previous = File(dir, PREVIOUS_FILE)
        synchronized(lock) {
            sessionDir = dir
            try {
                if (settings.getInt("sessionPid", 0) != myPid && current.exists()) {
                    previous.delete()
                    if (!current.renameTo(previous)) current.delete()
                }
                settings.setInt("sessionPid", myPid)
                buffer.clear()
                totalLines = 0
                previousSaved.clear()
                previousRecovered.clear()
                previousSet.clear()
                if (previous.exists()) {
                    val lines = previous.readLines(Charsets.UTF_8)
                    val from = if (lines.size > MAX_LINES) lines.size - MAX_LINES else 0
                    for (k in from until lines.size) previousSaved.add(lines[k])
                    previousSet.addAll(previousSaved)
                }
                // The logcat dump refills this process's earlier lines, so start the file empty.
                openWriter(current, append = false)
            } catch (e: Throwable) {
                log.error("Could not open the session log", e)
            }
        }
    }

    private fun openWriter(file: File, append: Boolean) {
        closeWriter()
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, append), Charsets.UTF_8), 16384)
        writtenChars = if (append) file.length() else 0L
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
    }

    /** Called with [lock] held. Writes a line and, once the file gets big, rewrites it from the buffer. */
    private fun persist(line: String, flush: Boolean) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            writtenChars += line.length + 1
            if (writtenChars > MAX_FILE_CHARS) {
                val dir = sessionDir ?: return
                closeWriter()
                openWriter(File(dir, CURRENT_FILE), append = false)
                val nw = writer ?: return
                for (l in buffer) {
                    nw.write(l)
                    nw.write("\n")
                    writtenChars += l.length + 1
                }
                nw.flush()
            } else if (flush) {
                w.flush()
            }
        } catch (e: Throwable) {
            log.error("Could not write the session log", e)
            closeWriter()
        }
    }

    // endregion

    // region Capture

    private fun readLoop(canReadAll: Boolean) {
        // Without READ_LOGS, logcat only returns this app's own lines (Android 4.1+), including earlier
        // processes, which is what lets us recover a crash. With READ_LOGS (granted over adb) it returns
        // every app's lines, so narrow it to this app's UID (Android 9+) or this process.
        var filter = when {
            !canReadAll -> Filter.NONE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Filter.UID
            else -> Filter.PID
        }
        var tailOnly = false
        while (running) {
            val read = runLogcat(filter, tailOnly)
            if (!running) return
            if (read < 0) {
                // logcat rejected the filter option
                filter = if (filter == Filter.UID) Filter.PID else Filter.PID_MANUAL
                continue
            }
            if (read == 0 && !tailOnly) {
                status = "No output. Logging may be turned off on this device."
                return
            }
            // logcat ended (killed or buffer rotated); restart after a short pause, only for new lines.
            tailOnly = true
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Runs logcat until it exits. Returns the number of lines read, or -1 if it rejected the filter. */
    private fun runLogcat(filter: Filter, tailOnly: Boolean): Int {
        val cmd = ArrayList<String>()
        cmd.add("logcat")
        cmd.add("-v")
        cmd.add("threadtime")
        when (filter) {
            Filter.UID -> cmd.add("--uid=" + android.os.Process.myUid())
            Filter.PID -> cmd.add("--pid=$myPid")
            else -> {}
        }
        if (tailOnly) {
            cmd.add("-T")
            cmd.add("1")
        }
        var read = 0
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = p
            status = when (filter) {
                Filter.NONE -> "Capturing this app's log"
                Filter.UID -> "Capturing this app's log (filtered by app)"
                else -> "Capturing this process's log"
            }
            BufferedReader(InputStreamReader(p.inputStream), 8192).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    val usesOption = filter == Filter.UID || filter == Filter.PID
                    if (read == 0 && usesOption && (line.contains("--uid") || line.contains("--pid"))) {
                        // "unrecognized option" on logcat versions that lack it
                        p.destroy()
                        return -1
                    }
                    read++
                    if (line.startsWith("--------- ")) continue // "beginning of main" markers
                    val pid = linePid(line)
                    if (pid != null && pid != myPid) {
                        if (filter == Filter.PID_MANUAL) continue
                        addRecovered(line)
                    } else {
                        add(line, flush = !reader.ready())
                    }
                }
            }
            p.destroy()
        } catch (e: Throwable) {
            if (running) {
                log.error("logcat failed", e)
                status = "Could not start logcat: ${e.message}"
            }
        }
        return read
    }

    private fun add(line: String, flush: Boolean) {
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) buffer.pollFirst()
            buffer.addLast(line)
            totalLines++
            persist(line, flush)
        }
    }

    /**
     * Adds a line written by this plugin in logcat's threadtime format, so it sorts, filters and saves like
     * the rest. It doesn't go through android.util.Log, so it's kept on ROMs that turn logging off too.
     */
    private fun addOwn(tag: String, message: String) {
        if (!running) return
        val tid = android.os.Process.myTid()
        synchronized(lock) {
            val time = LINE_TIME.format(Date())
            add(String.format(Locale.US, "%s %5d %5d I %s: %s", time, myPid, tid, tag, message), flush = true)
        }
    }

    private fun addRecovered(line: String) {
        synchronized(lock) {
            if (!previousSet.add(line)) return
            if (previousRecovered.size >= MAX_LINES) previousRecovered.removeAt(0)
            previousRecovered.add(line)
        }
    }

    // endregion

    // region Export

    fun lineCount(): Int = synchronized(lock) { buffer.size }

    fun previousLineCount(): Int = synchronized(lock) { previousSaved.size + previousRecovered.size }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            previousSaved.clear()
            previousRecovered.clear()
            previousSet.clear()
            DiagLog.clear()
            val dir = sessionDir ?: return
            File(dir, PREVIOUS_FILE).delete()
            if (writer != null) {
                try {
                    openWriter(File(dir, CURRENT_FILE), append = false)
                } catch (e: Throwable) {
                    log.error("Could not clear the session log", e)
                }
            }
        }
    }

    /** Filtered, unredacted lines with section headers, most recent last. */
    private fun filteredRaw(): List<String> {
        val tags = tagFilter.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val min = minLevel
        val filtering = min > 0 || tags.isNotEmpty()
        val previous: List<String>
        val current: List<String>
        synchronized(lock) {
            previous = mergeByTime(previousSaved, previousRecovered)
            current = ArrayList(buffer)
        }
        val out = ArrayList<String>(previous.size + current.size + 2)
        if (previous.isNotEmpty()) {
            out.add("===== Before the last restart or crash =====")
            for (line in previous) if (!filtering || matches(line, min, tags)) out.add(line)
            out.add("===== This session (since " + DATE.format(Date(startedAt)) + ") =====")
        }
        for (line in current) if (!filtering || matches(line, min, tags)) out.add(line)
        return out
    }

    /** The latest [limit] filtered lines, redacted. */
    fun filteredLines(limit: Int = Int.MAX_VALUE): List<String> {
        val raw = filteredRaw()
        val from = if (raw.size > limit) raw.size - limit else 0
        val out = ArrayList<String>(raw.size - from)
        for (k in from until raw.size) out.add(Redactor.redact(raw[k]))
        return out
    }

    /** The redacted log as text, trimmed from the start to at most [maxChars]. */
    fun export(context: Context, maxChars: Int = Int.MAX_VALUE): String {
        val lines = filteredLines()
        val header = StringBuilder()
        header.append("Discord logcat (Logcat plugin)\n")
        header.append("Exported: ").append(DATE.format(Date())).append('\n')
        header.append("Capture started: ").append(DATE.format(Date(startedAt))).append('\n')
        header.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
            .append("), ").append(Build.MANUFACTURER).append('\n')
        header.append("Status: ").append(status).append('\n')
        val tags = tagFilter
        header.append("Filter: level ").append(LEVELS[minLevel])
            .append(if (tags.isEmpty()) "" else ", tags $tags").append('\n')
        header.append("Tokens, credentials, emails, URL query strings and IP addresses are redacted.\n\n")
        if (timings.running) {
            header.append("Network timings (").append(NetworkTimings.TAG).append(" lines) since the last clear:\n")
            for (line in timings.buildReport(context).split('\n')) {
                if (line.isNotEmpty()) header.append(Redactor.redact(line)).append('\n')
            }
            header.append('\n')
        }

        val budget = maxChars - header.length
        var used = 0
        var start = lines.size
        while (start > 0 && used + lines[start - 1].length + 1 <= budget) {
            used += lines[start - 1].length + 1
            start--
        }
        if (start > 0) header.append("[").append(start).append(" older lines left out to fit]\n")
        for (j in start until lines.size) header.append(lines[j]).append('\n')
        return header.toString()
    }

    // endregion

    private enum class Filter { NONE, UID, PID, PID_MANUAL }

    companion object {
        const val MAX_LINES = 5000
        val LEVELS = arrayOf("Verbose", "Debug", "Info", "Warning", "Error", "Fatal")
        private const val LEVEL_CHARS = "VDIWEF"
        private const val CURRENT_FILE = "current.log"
        private const val PREVIOUS_FILE = "previous.log"

        /** current.log is rewritten from the in-memory buffer once it passes this size. */
        private const val MAX_FILE_CHARS = 3_000_000L
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** threadtime's own timestamp format; only used with [lock] held. */
        private val LINE_TIME = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

        // threadtime: "11-25 12:34:56.789  1234  1250 D Tag     : message"
        private val HEADER = Pattern.compile("^\\s*\\d\\d-\\d\\d\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+([VDIWEFA])\\s+(.*?)\\s*: ")

        private fun linePid(line: String): Int? {
            val m = HEADER.matcher(line)
            return if (m.find()) m.group(1)?.toIntOrNull() else null
        }

        /** "MM-DD HH:MM:SS.mmm", which sorts correctly as text (except across New Year). */
        private fun timeKey(line: String): String? =
            if (line.length >= 18 && line[2] == '-' && line[5] == ' ' && line[8] == ':') line.substring(0, 18) else null

        /** Merges two chronological lists; lines without a timestamp keep the one before them. */
        private fun mergeByTime(a: List<String>, b: List<String>): List<String> {
            if (b.isEmpty()) return ArrayList(a)
            if (a.isEmpty()) return ArrayList(b)
            val out = ArrayList<String>(a.size + b.size)
            var i = 0
            var j = 0
            var ka = ""
            var kb = ""
            while (i < a.size || j < b.size) {
                if (i < a.size) ka = timeKey(a[i]) ?: ka
                if (j < b.size) kb = timeKey(b[j]) ?: kb
                if (j >= b.size || (i < a.size && ka <= kb)) out.add(a[i++]) else out.add(b[j++])
            }
            return out
        }

        private fun matches(line: String, min: Int, tags: List<String>): Boolean {
            val m = HEADER.matcher(line)
            if (!m.find()) return line.startsWith("=====")
            val levelChar = m.group(2)?.firstOrNull() ?: return false
            val level = if (levelChar == 'A') LEVEL_CHARS.length - 1 else LEVEL_CHARS.indexOf(levelChar)
            if (level < min) return false
            if (tags.isEmpty()) return true
            val tag = m.group(3).orEmpty().lowercase()
            return tags.any { tag.contains(it) }
        }
    }
}
