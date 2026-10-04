package com.github.yutaplug.gamementions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.ReflectUtils
import com.discord.utilities.dimen.DimenUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.views.SearchInputView
import com.discord.widgets.chat.input.WidgetChatInputEditText
import com.discord.widgets.chat.input.autocomplete.AutocompleteViewModel
import com.discord.widgets.chat.input.autocomplete.GlobalRoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.InputAutocomplete
import com.discord.widgets.chat.input.autocomplete.ViewState
import com.lytefast.flexinput.widget.FlexEditText
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin
class GameMentions : Plugin() {
    private val handler = Handler(Looper.getMainLooper())
    private val pickers = mutableMapOf<FlexEditText, Picker>()
    private val autocompletes = WeakHashMap<FlexEditText, InputAutocomplete>()
    private val index = GameIndex { handler.post { if (running) pickers.values.forEach { it.refresh() } } }

    // A picked game rides through Discord's native mention pipeline as a GlobalRoleAutocompletable
    // (the @here/@everyone type): Autocompletable is sealed, so it cannot be subclassed. Its equality is by text.
    private val carriers = ConcurrentHashMap<String, Game>()

    @Volatile private var running = false

    override fun start(context: Context) {
        running = true
        val cache = File(context.cacheDir, CACHE_FILE)
        Utils.threadPool.execute { index.load(cache) }

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
                if (!running) return@Hook
                val input = ReflectUtils.getField(frame.thisObject, "editText") as FlexEditText
                autocompletes[input] = frame.thisObject as InputAutocomplete
                update(input)
            },
        )
        patcher.patch(
            GlobalRoleAutocompletable::class.java.getDeclaredMethod("getInputReplacement"),
            Hook { frame ->
                val game = carriers[(frame.thisObject as GlobalRoleAutocompletable).text] ?: return@Hook
                frame.result = "<@\$${game.id}>"
            },
        )
    }

    private fun token(input: FlexEditText): IntRange? {
        val text = input.text ?: return null
        val cursor = input.selectionStart
        if (cursor < TRIGGER.length || cursor > text.length || cursor != input.selectionEnd) return null
        val start = cursor - TRIGGER.length
        if (!text.subSequence(start, cursor).toString().equals(TRIGGER, ignoreCase = true)) return null
        if (start > 0 && !text[start - 1].isWhitespace()) return null
        if (cursor < text.length && !text[cursor].isWhitespace()) return null
        // Slash command options and literal @game inside inline/fenced code are not mentions.
        if (text.startsWith("/")) return null
        val prefix = text.subSequence(0, start).toString()
        if (prefix.count { it == '`' } % 2 != 0) return null
        return start until cursor
    }

    private fun update(input: FlexEditText) {
        if (!input.isAttachedToWindow) return
        val range = token(input)
        val picker = pickers[input]
        // The picker's own search field takes focus away from the chat input while it is open.
        if (range == null || !input.isShown || !(input.hasFocus() || picker?.hasFocus() == true)) {
            picker?.hide()
            return
        }
        (picker ?: createPicker(input)?.also { pickers[input] = it })?.show(range)
    }

    private fun createPicker(input: FlexEditText): Picker? {
        val wrap = input.rootView.findViewById<LinearLayout>(Utils.getResId("chat_input_wrap", "id")) ?: return null
        val contextBar = wrap.findViewById<View>(Utils.getResId("chat_input_context_bar", "id")) ?: return null
        val panel = LayoutInflater.from(input.context).inflate(
            Utils.getResId("widget_chat_input_application_commands", "layout"),
            wrap,
            false,
        ) as ConstraintLayout
        panel.visibility = View.GONE
        // This is a separate native-layout instance, not the selected slash command's view.
        panel.id = View.NO_ID
        wrap.addView(panel, wrap.indexOfChild(contextBar))
        return Picker(input, panel).also { picker ->
            input.addOnAttachStateChangeListener(picker)
        }
    }

    private inner class Picker(val input: FlexEditText, val panel: ConstraintLayout) :
        View.OnAttachStateChangeListener {
        private var results = emptyList<Game>()
        private var range: IntRange? = null
        private val search = SearchInputView(panel.context, null)
        private val field = search.editText as EditText
        private val description = panel.findViewById<TextView>(
            Utils.getResId("chat_input_application_commands_option_description", "id"),
        )
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
            val margin = DimenUtils.dpToPixels(8)
            search.id = View.generateViewId()
            search.setHint("Search for a game")
            panel.addView(
                search,
                ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    setMargins(margin, margin, margin, 0)
                },
            )
            (recycler.layoutParams as ConstraintLayout.LayoutParams).topToBottom = search.id
            field.isSingleLine = true
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH
            field.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) {
                        search.b(s?.toString().orEmpty())
                        refresh()
                    }
                },
            )
            field.setOnEditorActionListener { _, _, _ ->
                results.firstOrNull()?.let(::insert)
                true
            }

            recycler.layoutManager = LinearLayoutManager(input.context)
            recycler.adapter = object : RecyclerView.Adapter<Row>() {
                override fun getItemCount() = results.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
                    LayoutInflater.from(parent.context).inflate(
                        Utils.getResId("widget_chat_input_autocomplete_item", "layout"),
                        parent,
                        false,
                    ),
                )

                override fun onBindViewHolder(holder: Row, position: Int) {
                    val game = results[position]
                    val item = holder.itemView
                    item.findViewById<TextView>(Utils.getResId("chat_input_item_name", "id")).text = game.name
                    for (name in UNUSED_ROW_VIEWS) {
                        item.findViewById<View>(Utils.getResId(name, "id")).visibility = View.GONE
                    }
                    val avatar = item.findViewById<ImageView>(Utils.getResId("chat_input_item_avatar", "id"))
                    val icon = game.icon
                    if (icon == null) {
                        avatar.visibility = View.GONE
                    } else {
                        avatar.visibility = View.VISIBLE
                        MGImages.setImage(avatar, IconUtils.getApplicationIcon(game.id, icon, ICON_SIZE))
                    }
                    item.setOnClickListener { insert(game) }
                }
            }
        }

        fun hasFocus() = field.hasFocus()

        fun refresh() {
            val query = field.text?.toString().orEmpty()
            results = index.suggestions(query, MAX_RESULTS)
            description.text = when {
                !index.loaded -> "@game · Loading games…"
                // Not isNotBlank(): it iterates an IntRange, which crashes under Discord's renamed stdlib.
                results.isEmpty() && GameIndex.normalize(query).isNotEmpty() -> "@game · No games found"
                else -> "@game · Mention a game"
            }
            recycler.adapter?.notifyDataSetChanged()
        }

        fun show(newRange: IntRange) {
            if (panel.visibility != View.VISIBLE || range != newRange) {
                range = newRange
                field.setText("")
                refresh()
            }
            for (view in nativeViews) {
                if (view.visibility != View.GONE) hiddenViews[view] = view.visibility
                view.visibility = View.GONE
            }
            panel.visibility = View.VISIBLE
        }

        private fun insert(game: Game) {
            val selectedRange = range ?: return
            if (token(input) != selectedRange || !input.isAttachedToWindow) return
            carriers[game.name] = game
            hide()
            input.requestFocus()
            val viewModel = autocompletes[input]?.let { getViewModel.invoke(it) as? AutocompleteViewModel }
            if (viewModel != null) {
                // Discord swaps its current "@game" token for an "@Name" pill and sends getInputReplacement().
                viewModel.selectAutocompleteItem(GlobalRoleAutocompletable(game.name))
            } else {
                val mention = "<@\$${game.id}> "
                input.text?.replace(selectedRange.first, selectedRange.last + 1, mention)
                input.setSelection(selectedRange.first + mention.length)
            }
        }

        fun hide(restoreViews: Boolean = false) {
            if (field.hasFocus()) field.clearFocus()
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
        autocompletes.clear()
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        handler.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) clearPickers() else handler.post { clearPickers() }
        carriers.clear()
        index.clear()
    }

    private companion object {
        const val TRIGGER = "@game"
        const val CACHE_FILE = "GameMentions-detectable.tsv"
        const val MAX_RESULTS = 5
        const val ICON_SIZE = 64

        val UNUSED_ROW_VIEWS = listOf("chat_input_item_description", "chat_input_item_emoji", "chat_input_item_status")

        val getViewModel = InputAutocomplete::class.java.getDeclaredMethod("getViewModel").apply {
            isAccessible = true
        }
    }
}
