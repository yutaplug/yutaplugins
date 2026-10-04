package com.github.yutaplug.markdownfix

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.aliucord.Utils
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.button.MaterialButton

/**
 * Discord's own notice dialog: the `widget_notice_dialog` layout themed with `?attr/dialogTheme`,
 * exactly as WidgetNoticeDialog builds it. Custom content replaces the native body text.
 */
internal class DiscordDialog(context: Context, title: String, destructive: Boolean = false) {
    val dialog: AppCompatDialog
    val body: LinearLayout
    private val root: View
    private val positive: MaterialButton
    private val negative: MaterialButton

    init {
        val value = TypedValue()
        val attribute = Utils.getResId(if (destructive) "notice_theme_positive_red" else "dialogTheme", "attr")
        context.theme.resolveAttribute(attribute, value, true)
        dialog = AppCompatDialog(context, value.resourceId)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        root = LayoutInflater.from(dialog.context).inflate(Utils.getResId("widget_notice_dialog", "layout"), null)
        root.findViewById<TextView>(id("notice_header")).text = title
        body = root.findViewById(id("notice_body_container"))
        // Name lookups miss these upper-case IDs; Discord exposes them as constants.
        positive = root.findViewById(WidgetNoticeDialog.OK_BUTTON)
        negative = root.findViewById(WidgetNoticeDialog.CANCEL_BUTTON)
        positive.visibility = View.GONE
        negative.visibility = View.GONE
        dialog.setContentView(root)
        // AppDialog clears the window background so the native page background shows.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /** Replaces the native body text with [view]. */
    fun content(view: View): DiscordDialog = apply {
        body.removeAllViews()
        body.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    /** Uses the native body text. */
    fun message(text: CharSequence): DiscordDialog = apply {
        root.findViewById<TextView>(id("notice_body_text")).text = text
    }

    /** [click] returns false to keep the dialog open, e.g. after a validation error. */
    fun positive(text: String, click: () -> Boolean = { true }): DiscordDialog = apply { bind(positive, text, click) }

    fun negative(text: String, click: () -> Unit = {}): DiscordDialog = apply {
        bind(negative, text) {
            click()
            true
        }
    }

    /** An extra footer action, placed at the start like Android's neutral button. Never dismisses. */
    fun neutral(text: String, click: () -> Unit): DiscordDialog = apply {
        val container = negative.parent as ViewGroup
        val style = Utils.getResId("buttonBarNegativeButtonStyle", "attr")
        val button = MaterialButton(dialog.context, null, style).apply {
            this.text = text
            setOnClickListener { click() }
        }
        container.addView(button, 0, LinearLayout.LayoutParams(-2, -2))
        // Pushes the cancel and confirm buttons back to the end.
        container.addView(View(dialog.context), 1, LinearLayout.LayoutParams(0, 0, 1f))
    }

    fun show(input: Boolean = false): DiscordDialog = apply {
        if (input) {
            @Suppress("DEPRECATION") // ADJUST_RESIZE is needed on the Android 5+ versions plugins support.
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
        }
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    private fun bind(button: MaterialButton, text: String, click: () -> Boolean) {
        button.text = text
        button.visibility = View.VISIBLE
        button.setOnClickListener { if (click()) dialog.dismiss() }
    }

    private fun id(name: String) = Utils.getResId(name, "id")
}
