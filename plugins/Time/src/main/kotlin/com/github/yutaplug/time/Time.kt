package com.github.yutaplug.time

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.ReflectUtils
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.textprocessing.node.TimestampNode
import com.discord.widgets.chat.input.WidgetChatInputEditText
import com.discord.widgets.chat.input.autocomplete.InputAutocomplete
import com.discord.widgets.chat.input.autocomplete.ViewState
import com.lytefast.flexinput.widget.FlexEditText
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.regex.Pattern

@AliucordPlugin
class Time : Plugin() {
    private val handler = Handler(Looper.getMainLooper())
    private val pickers = mutableMapOf<FlexEditText, Picker>()
    private val timestampPattern = Pattern.compile("^<t:(-?\\d{1,17})(?::(t|T|d|D|f|F|R|s|S))?>")
    private var running = false

    override fun start(context: Context) {
        running = true
        patchTimestamps()
        patcher.patch(
            Class.forName("com.discord.widgets.chat.input.WidgetChatInputEditText\$setOnTextChangedListener\$1"),
            "afterTextChanged",
            arrayOf(Editable::class.java),
            Hook { frame ->
                val owner = ReflectUtils.getField(frame.thisObject, "this\$0") as WidgetChatInputEditText
                val input = ReflectUtils.getField(owner, "editText") as FlexEditText
                // Wait until Discord has finished updating its own autocomplete views.
                handler.post { if (running) update(input) }
            },
        )
        patcher.patch(
            InputAutocomplete::class.java,
            "configureUI",
            arrayOf(ViewState::class.java),
            Hook { frame ->
                if (running) update(ReflectUtils.getField(frame.thisObject, "editText") as FlexEditText)
            },
        )
    }

    private fun patchTimestamps() {
        // 126.21 only recognizes the original seven styles. Keep the existing parser and renderer,
        // extending recognition to the two date/time styles now used by desktop.
        patcher.patch(
            Rule::class.java,
            "match",
            arrayOf(CharSequence::class.java, String::class.java, Any::class.java),
            Hook { frame ->
                if (frame.thisObject.javaClass.name !=
                    "com.discord.utilities.textprocessing.Rules\$createTimestampRule\$1"
                ) {
                    return@Hook
                }
                if (frame.result != null) return@Hook
                val matcher = timestampPattern.matcher(frame.args[0] as CharSequence)
                if (matcher.find()) frame.result = matcher
            },
        )
        patcher.patch(
            TimestampNode::class.java.getDeclaredConstructor(String::class.java, String::class.java),
            Hook { frame ->
                val style = frame.args[1] as? String
                if (style != "s" && style != "S") return@Hook
                val seconds = (frame.args[0] as String).toLongOrNull() ?: return@Hook
                ReflectUtils.setField(frame.thisObject, "formatted", shortDateTime(seconds, style))
            },
        )
    }

    private fun shortDateTime(seconds: Long, style: String): String = DateFormat
        .getDateTimeInstance(
            DateFormat.SHORT,
            if (style == "S") DateFormat.MEDIUM else DateFormat.SHORT,
        ).format(Date(seconds * 1000))

    private class Token(val range: IntRange, val query: String)

    // "@time" optionally followed by a value typed in place, like desktop's option box: "@time 5pm".
    private fun token(input: FlexEditText): Token? {
        val text = input.text ?: return null
        val cursor = input.selectionStart
        if (cursor < 5 || cursor > text.length || cursor != input.selectionEnd) return null
        if (cursor < text.length && !text[cursor].isWhitespace()) return null
        val line = text.subSequence(0, cursor).toString().substringAfterLast('\n')
        val index = line.lastIndexOf("@time")
        if (index < 0) return null
        val start = cursor - line.length + index
        val rest = line.substring(index + 5)
        if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
        if (rest.length > 32 || '<' in rest || '@' in rest) return null
        if (start > 0 && !text[start - 1].isWhitespace()) return null
        // A literal @time inside inline/fenced code or escaped text is not a command.
        val prefix = text.subSequence(0, start).toString()
        if (prefix.count { it == '`' } % 2 != 0) return null
        return Token(start until cursor, rest.trim())
    }

    private fun update(input: FlexEditText) {
        if (!input.isAttachedToWindow) return
        val token = token(input)
        val items = token?.let { items(it.query) }
        if (token == null || items == null || !input.hasFocus() || !input.isShown) {
            pickers[input]?.hide()
            return
        }
        val picker = pickers[input] ?: createPicker(input)?.also { pickers[input] = it } ?: return
        picker.show(token, items)
    }

    private sealed class Item {
        class Header(val text: String) : Item()

        class Option(val seconds: Long, val style: String, val label: String) : Item()
    }

    private val dateTimeStyles = listOf(
        "s" to "Short date and time",
        "f" to "Long date and time",
        "F" to "Full date and time",
        "R" to "Relative time",
    )

    private fun options(seconds: Long, styles: List<Pair<String, String>>) =
        styles.map { (style, label) -> Item.Option(seconds, style, label) }

    /** Picker rows for [query], or null when it isn't a time this parser understands. */
    private fun items(query: String): List<Item>? {
        val now = System.currentTimeMillis() / 1000
        if (query.isEmpty()) return options(now, listOf("S" to "Short date and time") + dateTimeStyles.drop(1))
        val header = Item.Header("Time formats for $query")
        parseRelative(query, now)?.let { return listOf(header) + options(it, dateTimeStyles) }
        val (hour, minute, second) = parseTimeOfDay(query) ?: return null
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        val next = (today.clone() as Calendar).apply { if (timeInMillis / 1000 <= now) add(Calendar.DATE, 1) }
        val previous = (today.clone() as Calendar).apply { if (timeInMillis / 1000 > now) add(Calendar.DATE, -1) }
        val timeStyle = if (second != 0) "T" to "Long time" else "t" to "Short time"
        return listOf(header) +
            options(today.timeInMillis / 1000, listOf(timeStyle)) +
            options(next.timeInMillis / 1000, dateTimeStyles) +
            options(previous.timeInMillis / 1000, dateTimeStyles)
    }

    private val timeOfDayPattern = Pattern.compile(
        "^(\\d{1,2})(?:[:.](\\d{2}))?(?::(\\d{2}))?\\s*(?:([ap])\\.?\\s*m?\\.?)?$",
        Pattern.CASE_INSENSITIVE,
    )

    private fun parseTimeOfDay(query: String): Triple<Int, Int, Int>? {
        val matcher = timeOfDayPattern.matcher(query)
        if (!matcher.matches()) return null
        var hour = matcher.group(1)!!.toInt()
        val minute = matcher.group(2)?.toInt() ?: 0
        val second = matcher.group(3)?.toInt() ?: 0
        if (minute > 59 || second > 59) return null
        when (matcher.group(4)?.lowercase()) {
            null -> if (hour > 23) return null
            else -> {
                if (hour !in 1..12) return null
                hour %= 12
                if (matcher.group(4).equals("p", ignoreCase = true)) hour += 12
            }
        }
        return Triple(hour, minute, second)
    }

    private val relativePattern = Pattern.compile(
        "^(in\\s+)?(\\d{1,6})\\s*([a-z]+)(\\s+ago)?$",
        Pattern.CASE_INSENSITIVE,
    )

    private fun parseRelative(query: String, now: Long): Long? {
        val matcher = relativePattern.matcher(query)
        if (!matcher.matches()) return null
        val past = matcher.group(4) != null
        if (past && matcher.group(1) != null) return null
        val unit = when (matcher.group(3)!!.lowercase()) {
            "s", "sec", "secs", "second", "seconds" -> Calendar.SECOND
            "m", "min", "mins", "minute", "minutes" -> Calendar.MINUTE
            "h", "hr", "hrs", "hour", "hours" -> Calendar.HOUR_OF_DAY
            "d", "day", "days" -> Calendar.DATE
            "w", "wk", "wks", "week", "weeks" -> Calendar.WEEK_OF_YEAR
            "mo", "month", "months" -> Calendar.MONTH
            "y", "yr", "yrs", "year", "years" -> Calendar.YEAR
            else -> return null
        }
        val amount = matcher.group(2)!!.toInt()
        return Calendar.getInstance().apply {
            timeInMillis = now * 1000
            add(unit, if (past) -amount else amount)
        }.timeInMillis / 1000
    }

    private fun createPicker(input: FlexEditText): Picker? {
        val wrap = input.rootView.findViewById<LinearLayout>(Utils.getResId("chat_input_wrap", "id")) ?: return null
        val contextBar = wrap.findViewById<View>(Utils.getResId("chat_input_context_bar", "id")) ?: return null
        val panel = LayoutInflater.from(input.context).inflate(
            Utils.getResId("widget_chat_input_application_commands", "layout"),
            wrap,
            false,
        ) as ViewGroup
        panel.visibility = View.GONE
        // This is a separate native-layout instance, not the selected slash command's view.
        panel.id = View.NO_ID
        wrap.addView(panel, wrap.indexOfChild(contextBar))
        return Picker(input, panel).also { picker ->
            input.addOnAttachStateChangeListener(picker)
        }
    }

    private inner class Picker(val input: FlexEditText, val panel: ViewGroup) : View.OnAttachStateChangeListener {
        private var items = emptyList<Item>()
        private var range: IntRange? = null
        private var query: String? = null
        private val recycler = panel.findViewById<RecyclerView>(
            Utils.getResId("chat_input_application_commands_recycler", "id"),
        )
        private val nativeViews = listOf(
            "chat_input_mentions_recycler",
            "chat_input_emoji_matching_header",
            "chat_input_categories_recycler",
        ).mapNotNull { input.rootView.findViewById<View>(Utils.getResId(it, "id")) }
        private val hiddenViews = mutableMapOf<View, Int>()

        init {
            panel
                .findViewById<TextView>(
                    Utils.getResId("chat_input_application_commands_option_description", "id"),
                ).text = "@time [time] · Refer to a time in the viewer's time zone"
            recycler.layoutManager = LinearLayoutManager(input.context)
            recycler.adapter = object : RecyclerView.Adapter<Row>() {
                override fun getItemCount() = items.size

                override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 1 else 0

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
                    LayoutInflater.from(parent.context).inflate(
                        Utils.getResId(
                            if (viewType == 1) {
                                "widget_chat_input_command_application_header_item"
                            } else {
                                "widget_chat_input_autocomplete_item"
                            },
                            "layout",
                        ),
                        parent,
                        false,
                    ),
                )

                override fun onBindViewHolder(holder: Row, position: Int) {
                    when (val item = items[position]) {
                        is Item.Header -> bindHeader(holder.itemView, item)
                        is Item.Option -> bindOption(holder.itemView, item)
                    }
                }
            }
        }

        private fun bindHeader(view: View, item: Item.Header) {
            view.findViewById<View>(Utils.getResId("chat_input_application_avatar", "id")).visibility = View.GONE
            view.findViewById<TextView>(Utils.getResId("chat_input_application_name", "id")).apply {
                text = item.text
                // The hidden avatar normally supplies the 16dp inset that aligns the header with the rows.
                (layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                    (16 * resources.displayMetrics.density).toInt()
            }
        }

        private fun bindOption(view: View, item: Item.Option) {
            view.findViewById<TextView>(Utils.getResId("chat_input_item_name", "id")).text =
                if (item.style == "S" || item.style == "s") {
                    shortDateTime(item.seconds, item.style)
                } else {
                    TimestampNode<TimestampNode.RenderContext>(item.seconds.toString(), item.style).formatted
                }
            view.findViewById<TextView>(Utils.getResId("chat_input_item_description", "id")).apply {
                text = item.label
                visibility = View.VISIBLE
            }
            view.findViewById<View>(Utils.getResId("chat_input_item_emoji", "id")).visibility = View.GONE
            view.findViewById<View>(Utils.getResId("chat_input_item_status", "id")).visibility = View.GONE
            view.setOnClickListener { insert(item) }
        }

        fun show(token: Token, newItems: List<Item>) {
            if (panel.visibility != View.VISIBLE || range != token.range || query != token.query) {
                range = token.range
                query = token.query
                items = newItems
                recycler.adapter?.notifyDataSetChanged()
                limitHeight()
            }
            for (view in nativeViews) {
                if (view.visibility != View.GONE) hiddenViews[view] = view.visibility
                view.visibility = View.GONE
            }
            panel.visibility = View.VISIBLE
        }

        // A typed time has up to 12 rows; keep the list scrollable above the keyboard instead of covering the chat.
        private fun limitHeight() {
            val frame = Rect().also { input.getWindowVisibleDisplayFrame(it) }
            val max = frame.height() / 2
            val width = (panel.parent as View).width
            recycler.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            if (width > 0 && max > 0) {
                recycler.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                if (recycler.measuredHeight > max) recycler.layoutParams.height = max
            }
            recycler.requestLayout()
            recycler.scrollToPosition(0)
        }

        private fun insert(item: Item.Option) {
            val selectedRange = range ?: return
            if (token(input)?.range != selectedRange || !input.isAttachedToWindow) return
            val timestamp = "<t:${item.seconds}:${item.style}>"
            val end = selectedRange.last + 1
            val suffix = if (end == input.text!!.length) " " else ""
            hide()
            input.text?.replace(selectedRange.first, end, timestamp + suffix)
            input.setSelection(selectedRange.first + timestamp.length + suffix.length)
        }

        fun hide(restoreViews: Boolean = false) {
            panel.visibility = View.GONE
            range = null
            if (restoreViews) {
                for ((view, visibility) in hiddenViews) view.visibility = visibility
            }
            hiddenViews.clear()
        }

        fun remove() {
            hide(restoreViews = true)
            input.removeOnAttachStateChangeListener(this)
            (panel.parent as? ViewGroup)?.removeView(panel)
        }

        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) {
            pickers.remove(input)
            remove()
        }
    }

    private class Row(view: View) : RecyclerView.ViewHolder(view)

    private fun clearPickers() {
        pickers.values.toList().forEach { it.remove() }
        pickers.clear()
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        handler.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) clearPickers() else handler.post { clearPickers() }
    }
}
