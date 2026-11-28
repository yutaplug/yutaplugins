package com.github.yutaplug.messageapps

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.discord.app.AppFragment
import com.discord.utilities.color.ColorCompat
import com.discord.views.SearchInputView
import com.facebook.drawee.view.SimpleDraweeView

class AppsPage internal constructor(
    private val plugin: MessageApps,
    private val target: CommandTarget,
    private val appCommands: List<MessageCommand>? = null,
) : AppFragment(Utils.getResId("widget_settings_authorized_apps", "layout")) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var bound: View? = null
    private var closed = false
    private var busy = false
    private var query = ""
    private var commands = emptyList<MessageCommand>()
    private var api: CommandApi? = null
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (closed || !plugin.active) {
            activity?.finish()
            return
        }
        bound = view
        generation++
        busy = false
        // The reused layout has XML toolbar text in addition to Discord's title layout.
        val appBar = (view as ViewGroup).getChildAt(0) as ViewGroup
        val toolbar = appBar.getChildAt(0) as Toolbar
        toolbar.title = null
        toolbar.subtitle = null
        setActionBarTitle(appCommands?.firstOrNull()?.appName ?: "Apps")
        setActionBarSubtitle(null as CharSequence?)
        setActionBarDisplayHomeAsUpEnabled()
        val body = findScroll(view)!!.getChildAt(0) as LinearLayout
        val header = body.getChildAt(0) as TextView
        status = body.getChildAt(1) as TextView
        val recycler = body.getChildAt(2)
        val nativeParams = recycler.layoutParams
        rows = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, recycler.paddingBottom)
        }
        body.removeView(recycler)
        body.addView(rows, nativeParams)
        header.text = if (appCommands == null) "Apps" else "Commands"
        status.setOnClickListener { if (appCommands == null && !busy) load() }
        if (appCommands == null) {
            val template = LayoutInflater.from(view.context).inflate(
                Utils.getResId("widget_gif_picker_search", "layout"),
                null,
            )
            val search = template.findViewById<SearchInputView>(Utils.getResId("search_input", "id"))
            val margins = search.layoutParams as ViewGroup.MarginLayoutParams
            (search.parent as ViewGroup).removeView(search)
            body.addView(search, 0, LinearLayout.LayoutParams(margins))
            search.setHint("Search commands")
            search.a(this) {
                query = it
                if (bound != null && !busy) render()
            }
            search.setText(query)
        }
        val token = CommandApi.token()
        api = token?.let { CommandApi(it) { plugin.active && !closed } }
        if (token == null) {
            status.text = "Sign in to Discord to use Apps."
        } else if (appCommands != null) {
            commands = appCommands
            render()
        } else {
            load()
        }
    }

    private fun findScroll(view: View): NestedScrollView? {
        if (view is NestedScrollView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findScroll(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun load(retries: Int = 0) {
        val service = api ?: return
        if (busy || !plugin.active) return
        busy = true
        status.visibility = View.VISIBLE
        status.text = "Loading apps..."
        val current = generation
        Utils.threadPool.execute {
            val result = runCatching { service.load(target) }
            main.post {
                if (!valid(current)) return@post
                busy = false
                result
                    .onSuccess {
                        commands = it
                        render()
                    }.onFailure {
                        if (it is CommandRateLimit && it.retryAt != null && retries < 2) {
                            val delay = maxOf(1L, it.retryAt - SystemClock.elapsedRealtime())
                            busy = true
                            status.text = "Apps will retry in ${(delay + 999) / 1000} seconds."
                            main.postDelayed({
                                if (valid(current)) {
                                    busy = false
                                    load(retries + 1)
                                }
                            }, delay)
                            return@onFailure
                        }
                        status.text = "Could not load apps: ${it.message ?: "Connection failed"}\nTap to retry."
                    }
            }
        }
    }

    private fun render() {
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        val matching = commands.filter { it.name.contains(query, true) || it.appName.contains(query, true) }
        if (appCommands == null) {
            matching.groupBy { it.appId }.values.forEach { group ->
                addRow(group.first(), group.first().appName, false) {
                    val page = AppsPage(plugin, target, commands.filter { it.appId == group.first().appId })
                    plugin.register(page)
                    Utils.openPageWithProxy(requireContext(), page)
                }
            }
        } else {
            matching.forEach { command -> addRow(command, command.name, true) { send(command) } }
        }
        status.visibility = if (matching.isEmpty()) View.VISIBLE else View.GONE
        status.text = if (query.isNotEmpty()) "No matching commands." else if (target.commandType == 2) {
            "No user commands available here."
        } else {
            "No message commands available here."
        }
    }

    private fun addRow(command: MessageCommand, label: String, execute: Boolean, action: () -> Unit) {
        val card = LayoutInflater.from(requireContext()).inflate(
            Utils.getResId("widget_settings_authorized_apps_list_item", "layout"),
            rows,
            false,
        ) as ViewGroup
        val column = card.getChildAt(0) as ViewGroup
        while (column.childCount > 1) column.removeViewAt(1)
        val row = column.getChildAt(0) as ViewGroup
        row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingTop)
        row.findViewById<TextView>(Utils.getResId("oauth_application_name_tv", "id")).text = label
        row
            .findViewById<SimpleDraweeView>(Utils.getResId("oauth_application_icon_iv", "id"))
            .setImageURI(command.iconUrl)
        val arrow = row.findViewById<ImageView>(Utils.getResId("oauth_application_deauthorize_btn", "id"))
        arrow.setImageResource(
            Utils.getResId(if (execute) "ic_send_white_a60_24dp" else "exo_ic_chevron_right", "drawable"),
        )
        arrow.imageTintList = ColorStateList.valueOf(
            ColorCompat.getThemedColor(requireContext(), Utils.getResId("colorInteractiveNormal", "attr")),
        )
        arrow.contentDescription = if (execute) "Run $label" else "Open $label"
        row.isFocusable = true
        row.isClickable = true
        val background = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            row.background = background.getDrawable(0)
        } finally {
            background.recycle()
        }
        row.setOnClickListener { if (!busy && plugin.active) action() }
        arrow.setOnClickListener { row.performClick() }
        rows.addView(card)
    }

    private fun send(command: MessageCommand) {
        val service = api ?: return
        if (busy || !plugin.active) return
        busy = true
        status.visibility = View.VISIBLE
        status.text = "Sending ${command.name}..."
        val current = generation
        Utils.threadPool.execute {
            val result = runCatching { service.execute(target, command) }
            main.post {
                if (!valid(current)) return@post
                busy = false
                result
                    .onSuccess {
                        Utils.showToast("Command sent")
                        activity?.finish()
                    }.onFailure {
                        status.text = "Could not send command: ${it.message ?: "Connection failed"}"
                    }
            }
        }
    }

    private fun valid(current: Int) = bound != null && generation == current && !closed && plugin.active

    internal fun close() {
        closed = true
        generation++
        main.removeCallbacksAndMessages(null)
        bound?.let { activity?.finish() }
    }

    override fun onDestroyView() {
        generation++
        bound = null
        main.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
