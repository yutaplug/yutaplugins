package com.github.yutaplug.fallbackfont

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.MetricAffectingSpan
import android.util.SparseBooleanArray
import android.widget.EditText
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.widgets.chat.input.autocomplete.InputAutocomplete
import com.discord.widgets.chat.input.autocomplete.InputEditTextAction
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class FallbackFont : Plugin() {
    @Volatile private var typeface: Typeface? = null
    private val styledTypefaces = arrayOfNulls<Typeface>(4)
    private val systemPaint = Paint()
    private val fallbackPaint = Paint()

    // Codepoint -> whether the fallback font should draw it. Only filled for non-ASCII codepoints.
    private val glyphCache = SparseBooleanArray()

    /** Called on the main thread after the font changes, so an open settings page can update. */
    var onFontChanged: (() -> Unit)? = null

    init {
        instance = this
        settingsTab = SettingsTab(FallbackFontSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    fun onFontPicked(uri: Uri) {
        val context = Utils.appContext
        Utils.threadPool.execute {
            val error = importFont(context, uri)
            Utils.mainThread.post {
                onFontChanged?.invoke()
                Utils.showToast(error ?: "Fallback font applied")
            }
        }
    }

    override fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            logger.warn("FallbackFont needs Android 6.0 or newer")
            return
        }
        loadFont(context)
        patcher.patch(
            TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
            ),
            PreHook { call ->
                val view = call.thisObject
                if (view is EditText) {
                    // Typed text never goes through setText, so inputs like the chat box get a watcher instead.
                    // TextView's constructor calls setText, so every EditText passes through here once.
                    if (watchedInputs.add(view)) view.addTextChangedListener(inputWatcher)
                    return@PreHook
                }
                if (typeface == null) return@PreHook
                val text = call.args[0] as? CharSequence ?: return@PreHook
                applyFallback(text)?.let { call.args[0] = it }
            },
        )
        // The chat box's autocomplete strips every CharacterStyle span after each edit to restyle mentions,
        // which includes ours, so reapply once it is done.
        val editTextField = InputAutocomplete::class.java.getDeclaredField("editText").apply { isAccessible = true }
        patcher.patch(
            InputAutocomplete::class.java.getDeclaredMethod("applyEditTextAction", InputEditTextAction::class.java),
            Hook { call ->
                if (typeface == null) return@Hook
                (editTextField.get(call.thisObject) as? EditText)?.editableText?.let(::applyFallback)
            },
        )
    }

    private val watchedInputs = Collections.newSetFromMap(WeakHashMap<EditText, Boolean>())

    private val inputWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            // Adding spans to the Editable fires span callbacks, not text callbacks, so this does not recurse.
            if (typeface != null && s != null) applyFallback(s)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        setTypeface(null)
    }

    val fontName: String?
        get() = settings.getString(KEY_NAME, null)?.takeIf { fontFile(Utils.appContext).isFile }

    private fun fontFile(context: Context) = File(context.filesDir, "FallbackFont/font")

    private fun loadFont(context: Context) {
        val file = fontFile(context)
        setTypeface(
            if (file.isFile) {
                try {
                    Typeface.createFromFile(file)
                } catch (e: RuntimeException) {
                    logger.error("Failed to load fallback font", e)
                    null
                }
            } else {
                null
            },
        )
    }

    private fun setTypeface(value: Typeface?) {
        synchronized(glyphCache) {
            typeface = value
            styledTypefaces.fill(null)
            fallbackPaint.typeface = value
            glyphCache.clear()
        }
    }

    /** Copies the picked font into private storage. Runs off the main thread; returns an error message or null. */
    fun importFont(context: Context, uri: Uri): String? {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Custom font"
        val file = fontFile(context)
        val temp = File(file.parentFile, "font.tmp")
        try {
            file.parentFile!!.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return "Unable to open the file"
            input.use { src -> temp.outputStream().use { src.copyTo(it) } }
            val loaded = try {
                Typeface.createFromFile(temp)
            } catch (e: RuntimeException) {
                temp.delete()
                return "This file is not a valid TTF or OTF font"
            }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) return "Unable to save the font"
            }
            settings.setString(KEY_NAME, name)
            setTypeface(loaded)
            return null
        } catch (e: Exception) {
            temp.delete()
            logger.error("Failed to import fallback font", e)
            return "Unable to read the file"
        }
    }

    fun removeFont(context: Context) {
        fontFile(context).delete()
        settings.remove(KEY_NAME)
        setTypeface(null)
    }

    /** Returns text with [FallbackSpan]s over unrenderable characters, or null when unchanged. */
    private fun applyFallback(text: CharSequence): CharSequence? {
        var spannable: Spannable? = null
        if (text is Spannable) {
            for (span in text.getSpans(0, text.length, FallbackSpan::class.java)) text.removeSpan(span)
        }
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val start = i
            i += Character.charCount(cp)
            if (!needsFallback(cp)) continue
            // Extend over adjacent unrenderable codepoints so each run gets a single span.
            while (i < text.length) {
                val next = Character.codePointAt(text, i)
                if (!needsFallback(next)) break
                i += Character.charCount(next)
            }
            val target = spannable ?: (text as? Spannable ?: SpannableString(text)).also { spannable = it }
            target.setSpan(FallbackSpan(this), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return if (spannable === text) null else spannable
    }

    private fun needsFallback(cp: Int): Boolean {
        if (cp < 0x80) return false
        when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt(),
            -> return false
        }
        // Variation selectors are drawn together with the preceding character.
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return false
        synchronized(glyphCache) {
            val index = glyphCache.indexOfKey(cp)
            if (index >= 0) return glyphCache.valueAt(index)
            val str = String(Character.toChars(cp))
            // Paint.hasGlyph also searches the system fallback chain, so false means it would render as tofu.
            val result = !systemPaint.hasGlyph(str) && fallbackPaint.typeface != null && fallbackPaint.hasGlyph(str)
            glyphCache.put(cp, result)
            return result
        }
    }

    internal fun typefaceFor(style: Int): Typeface? {
        val base = typeface ?: return null
        val index = style and Typeface.BOLD_ITALIC
        return styledTypefaces[index] ?: Typeface.create(base, index).also { styledTypefaces[index] = it }
    }

    private class FallbackSpan(private val plugin: FallbackFont) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)

        override fun updateMeasureState(tp: TextPaint) = apply(tp)

        private fun apply(paint: TextPaint) {
            val style = paint.typeface?.style ?: Typeface.NORMAL
            val replacement = plugin.typefaceFor(style) ?: return
            // Synthesize styles the font file does not provide, like TypefaceSpan does.
            val missing = style and replacement.style.inv()
            if (missing and Typeface.BOLD != 0) paint.isFakeBoldText = true
            if (missing and Typeface.ITALIC != 0) paint.textSkewX = -0.25f
            paint.typeface = replacement
        }
    }

    companion object {
        private const val KEY_NAME = "fontName"

        // Lets the picker fragment reach the plugin after the settings page that opened it is gone.
        @Volatile var instance: FallbackFont? = null
    }
}
