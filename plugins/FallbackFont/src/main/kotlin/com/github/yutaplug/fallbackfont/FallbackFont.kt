package com.github.yutaplug.fallbackfont

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import android.util.SparseIntArray
import android.widget.EditText
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import android.view.View
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.widgets.chat.list.actions.EmojiItem
import com.discord.widgets.chat.list.actions.EmojiViewHolder
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.widgets.chat.input.emoji.WidgetEmojiAdapter
import com.facebook.drawee.view.SimpleDraweeView
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.widgets.chat.input.autocomplete.InputAutocomplete
import com.discord.widgets.chat.input.autocomplete.InputEditTextAction
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/** A font the user can pick. [file] and [nameKey] of [FALLBACK] predate the other slots and stay unchanged. */
enum class FontSlot(val title: String, val file: String, val nameKey: String) {
    TEXT("Text font", "text", "textFontName"),
    EMOJI("Emoji font", "emoji", "emojiFontName"),
    EMOJI_FALLBACK("Fallback emoji font", "emojiFallback", "emojiFallbackFontName"),
    FALLBACK("Fallback font", "font", "fontName"),
    FALLBACK_2("Second fallback font", "fallback2", "fallback2FontName"),
}

@AliucordPlugin
class FallbackFont : Plugin() {
    private val slots = FontSlot.values()
    private val typefaces = arrayOfNulls<Typeface>(slots.size)
    private val styledTypefaces = Array(slots.size) { arrayOfNulls<Typeface>(4) }
    private val paints = Array(slots.size) { Paint() }
    private val systemPaint = Paint()

    // Codepoint -> slot ordinal, or NONE. Only for codepoints whose slot does not depend on their neighbours.
    private val slotCache = SparseIntArray()
    @Volatile private var anyFont = false

    /** The emoji font chained with the fallback emoji font; see [buildEmojiTypeface]. */
    @Volatile private var emojiTypeface: Typeface? = null

    // Characters each emoji font covers, only read on Android 9 and older; see emojiSlot.
    @Volatile private var emojiCoverage: CmapCoverage? = null
    @Volatile private var emojiFallbackCoverage: CmapCoverage? = null

    /** Called on the main thread after a font changes, so an open settings page can update. */
    var onFontChanged: (() -> Unit)? = null

    var emojiAsText: Boolean
        get() = settings.getBool(KEY_EMOJI_AS_TEXT, false)
        set(value) = settings.setBool(KEY_EMOJI_AS_TEXT, value)

    init {
        instance = this
        settingsTab = SettingsTab(FallbackFontSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            logger.warn("FallbackFont needs Android 6.0 or newer")
            return
        }
        for (slot in slots) setTypeface(slot, loadFont(context, slot))
        patchText()
        patchEmoji()
        patchEmojiPicker()
    }

    /**
     * Shows unicode emoji in the emoji picker and the message menu's quick-reaction row with the emoji font
     * too, so they match emoji shown as text.
     */
    private fun patchEmojiPicker() {
        patcher.patch(
            WidgetEmojiAdapter.EmojiViewHolder::class.java.getDeclaredMethod(
                "onConfigure",
                Int::class.javaPrimitiveType,
                MGRecyclerDataPayload::class.java,
            ),
            Hook { call ->
                val emoji = (call.args[1] as? WidgetEmojiAdapter.EmojiItem)?.emoji
                showAsText((call.thisObject as WidgetEmojiAdapter.EmojiViewHolder).itemView, emoji)
            },
        )
        patcher.patch(
            EmojiViewHolder::class.java.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, EmojiItem::class.java),
            Hook { call ->
                val emoji = (call.args[1] as? EmojiItem.EmojiData)?.emoji
                showAsText((call.thisObject as EmojiViewHolder).itemView, emoji)
            },
        )
    }

    private fun showAsText(view: View, emoji: Emoji?) {
        if (!emojiAsText) return
        val unicode = emoji as? ModelEmojiUnicode ?: return
        val drawee = view as? SimpleDraweeView ?: return
        // Clears Fresco's Twemoji request; Discord sets a new one when the cell is reused.
        drawee.controller = null
        drawee.setImageDrawable(EmojiTextDrawable(unicode.surrogates, emojiTypefaceFor(unicode.surrogates)))
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        for (slot in slots) setTypeface(slot, null)
    }

    // region Hooks

    private fun patchText() {
        patcher.patch(
            TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java, TextView.BufferType::class.java),
            PreHook { call ->
                val view = call.thisObject
                if (view is EditText) {
                    // Typed text never goes through setText, so inputs like the chat box get a watcher instead.
                    // TextView's constructor calls setText, so every EditText passes through here once.
                    if (watchedInputs.add(view)) view.addTextChangedListener(inputWatcher)
                    return@PreHook
                }
                if (!anyFont) return@PreHook
                val text = call.args[0] as? CharSequence ?: return@PreHook
                applyFonts(text)?.let { call.args[0] = it }
            },
        )
        // The chat box's autocomplete strips every CharacterStyle span after each edit to restyle mentions,
        // which includes ours, so reapply once it is done.
        val editTextField = InputAutocomplete::class.java.getDeclaredField("editText").apply { isAccessible = true }
        patcher.patch(
            InputAutocomplete::class.java.getDeclaredMethod("applyEditTextAction", InputEditTextAction::class.java),
            Hook { call ->
                if (!anyFont) return@Hook
                (editTextField.get(call.thisObject) as? EditText)?.editableText?.let(::applyFonts)
            },
        )
    }

    /** Draws unicode emoji as text instead of Discord's Twemoji images, so the emoji font applies to them. */
    private fun patchEmoji() {
        patcher.patch(
            EmojiNode::class.java.getDeclaredMethod(
                "render",
                SpannableStringBuilder::class.java,
                EmojiNode.RenderContext::class.java,
            ),
            PreHook { call ->
                if (!emojiAsText) return@PreHook
                val node = call.thisObject as EmojiNode<*>
                // Hidden spoilers rely on the image placeholder to stay covered.
                if (!node.isRevealed) return@PreHook
                val id = EmojiNode.`access$getEmojiIdAndType$p`(node) as? EmojiNode.EmojiIdAndType.Unicode
                    ?: return@PreHook
                val surrogates = StoreStream.getEmojis().unicodeEmojisNamesMap[id.name]?.surrogates ?: return@PreHook
                val builder = call.args[0] as SpannableStringBuilder
                val renderContext = call.args[1] as EmojiNode.RenderContext
                val start = builder.length
                builder.append(surrogates)
                val end = builder.length
                // Discord enlarges emoji-only messages; match its jumbo image size.
                if (node.isJumbo) {
                    builder.setSpan(AbsoluteSizeSpan(DimenUtils.dpToPx(JUMBO_SIZE_DP)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                builder.setSpan(
                    ClickableSpan(null, false, null) {
                        renderContext.onEmojiClicked(id)
                        kotlin.Unit.a
                    },
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                call.result = null
            },
        )
    }

    private val watchedInputs = Collections.newSetFromMap(WeakHashMap<EditText, Boolean>())

    private val inputWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            // Adding spans to the Editable fires span callbacks, not text callbacks, so this does not recurse.
            if (anyFont && s != null) applyFonts(s)
        }
    }

    // endregion

    // region Font files

    fun fontName(slot: FontSlot): String? =
        settings.getString(slot.nameKey, null)?.takeIf { fontFile(Utils.appContext, slot).isFile }

    private fun fontFile(context: Context, slot: FontSlot) = File(context.filesDir, "FallbackFont/${slot.file}")

    private fun loadFont(context: Context, slot: FontSlot): Typeface? {
        val file = fontFile(context, slot)
        if (!file.isFile) return null
        return try {
            Typeface.createFromFile(file)
        } catch (e: RuntimeException) {
            logger.error("Failed to load ${slot.title}", e)
            null
        }
    }

    private fun setTypeface(slot: FontSlot, value: Typeface?) {
        synchronized(slotCache) {
            val index = slot.ordinal
            typefaces[index] = value
            styledTypefaces[index].fill(null)
            paints[index].typeface = value
            if (slot == FontSlot.EMOJI || slot == FontSlot.EMOJI_FALLBACK) {
                emojiTypeface = buildEmojiTypeface()
                // Older Android versions pick between the two emoji fonts from their character tables.
                val coverage = if (value != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    CmapCoverage.read(fontFile(Utils.appContext, slot))
                } else {
                    null
                }
                if (slot == FontSlot.EMOJI) emojiCoverage = coverage else emojiFallbackCoverage = coverage
            }
            slotCache.clear()
            anyFont = typefaces.any { it != null }
        }
    }

    /**
     * The emoji font followed by the fallback emoji font. A custom font's hasGlyph also counts the system
     * fonts, so it can't tell which font has an emoji; a fallback chain lets Android's text shaping pick
     * the first font that has each emoji, sequences included.
     */
    private fun buildEmojiTypeface(): Typeface? {
        val primary = typefaces[FontSlot.EMOJI.ordinal]
        val fallback = typefaces[FontSlot.EMOJI_FALLBACK.ordinal]
        if (primary == null || fallback == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return primary ?: fallback
        return try {
            val context = Utils.appContext
            fun family(slot: FontSlot) =
                FontFamily.Builder(Font.Builder(fontFile(context, slot)).build()).build()
            Typeface.CustomFallbackBuilder(family(FontSlot.EMOJI))
                .addCustomFallback(family(FontSlot.EMOJI_FALLBACK))
                .setSystemFallback("sans-serif")
                .build()
        } catch (e: Exception) {
            logger.error("Failed to combine the emoji fonts", e)
            primary
        }
    }

    fun onFontPicked(slot: FontSlot, uri: Uri) {
        val context = Utils.appContext
        Utils.threadPool.execute {
            val error = importFont(context, slot, uri)
            Utils.mainThread.post {
                onFontChanged?.invoke()
                Utils.showToast(error ?: "${slot.title} applied")
            }
        }
    }

    /** Copies the picked font into private storage. Runs off the main thread; returns an error message or null. */
    private fun importFont(context: Context, slot: FontSlot, uri: Uri): String? {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Custom font"
        val file = fontFile(context, slot)
        val temp = File(file.parentFile, "${slot.file}.tmp")
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
            settings.setString(slot.nameKey, name)
            setTypeface(slot, loaded)
            return null
        } catch (e: Exception) {
            temp.delete()
            logger.error("Failed to import ${slot.title}", e)
            return "Unable to read the file"
        }
    }

    fun removeFont(context: Context, slot: FontSlot) {
        fontFile(context, slot).delete()
        settings.remove(slot.nameKey)
        setTypeface(slot, null)
    }

    // endregion

    // region Applying fonts

    /** Returns text with [FontSpan]s over every run that needs a custom font, or null when the text was edited in place or unchanged. */
    private fun applyFonts(text: CharSequence): CharSequence? {
        var spannable: Spannable? = null
        if (text is Spannable) {
            for (span in text.getSpans(0, text.length, FontSpan::class.java)) text.removeSpan(span)
        }
        var runStart = 0
        var runSlot = NONE
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val next = i + Character.charCount(cp)
            val nextCp = if (next < text.length) Character.codePointAt(text, next) else -1
            // Joiners, variation selectors and modifiers belong to the character before them.
            val slot = if (i > 0 && isJoiner(cp)) runSlot else slotFor(cp, nextCp)
            if (slot != runSlot) {
                if (runSlot != NONE) {
                    val target = spannable ?: (text as? Spannable ?: SpannableString(text)).also { spannable = it }
                    target.setSpan(FontSpan(this, runSlot), runStart, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                runStart = i
                runSlot = slot
            }
            i = next
        }
        if (runSlot != NONE) {
            val target = spannable ?: (text as? Spannable ?: SpannableString(text)).also { spannable = it }
            target.setSpan(FontSpan(this, runSlot), runStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return if (spannable === text) null else spannable
    }

    private fun has(slot: FontSlot) = typefaces[slot.ordinal] != null

    /**
     * The emoji slot for an emoji starting with [cp]. Android 10+ uses the combined chain. Older versions
     * can't chain fonts, so with both emoji fonts set this picks one from their cmaps; sequences follow
     * their first codepoint.
     */
    private fun emojiSlot(cp: Int): Int {
        val custom = customEmojiSlot(cp)
        if (custom != NONE) return custom
        // No emoji font has it: like the fallback fonts did before emoji fonts existed, draw emoji the
        // device can't display with a fallback font instead of an empty box.
        val str = String(Character.toChars(cp))
        if (systemPaint.hasGlyph(str)) return NONE
        for (slot in EMOJI_FALLBACK_ORDER) {
            if (has(slot) && paints[slot.ordinal].hasGlyph(str)) return slot.ordinal
        }
        return NONE
    }

    /** The emoji font slot that has [cp], or NONE when no emoji font is set or known to have it. */
    private fun customEmojiSlot(cp: Int): Int {
        if (emojiTypeface == null) return NONE
        // Android 10+ chains both emoji fonts and the device's, and can't tell which has an emoji.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return FontSlot.EMOJI.ordinal
        val primary = emojiCoverage
        if (has(FontSlot.EMOJI) && (primary == null || cp in primary)) return FontSlot.EMOJI.ordinal
        val fallback = emojiFallbackCoverage
        if (has(FontSlot.EMOJI_FALLBACK) && (fallback == null || cp in fallback)) return FontSlot.EMOJI_FALLBACK.ordinal
        return NONE
    }

    /** The typeface for [emoji], or null to use the device's emoji. */
    internal fun emojiTypefaceFor(emoji: String): Typeface? {
        if (emoji.isEmpty()) return null
        val cp = Character.codePointAt(emoji, 0)
        val slot = synchronized(slotCache) { emojiSlot(cp) }
        return if (slot == NONE) null else typefaceFor(slot, Typeface.NORMAL)
    }

    private fun slotFor(cp: Int, next: Int): Int {
        // Keycaps (1️⃣) and anything followed by U+FE0F ask for emoji presentation.
        if (next == 0xFE0F || (next == 0x20E3 && (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code))) {
            // emojiSlot reads the paints, which setTypeface changes under this lock.
            return synchronized(slotCache) { emojiSlot(cp) }
        }
        if (cp < 0x80) return if (has(FontSlot.TEXT)) FontSlot.TEXT.ordinal else NONE
        synchronized(slotCache) {
            val index = slotCache.indexOfKey(cp)
            if (index >= 0) return slotCache.valueAt(index)
            val result = computeSlot(cp)
            slotCache.put(cp, result)
            return result
        }
    }

    private fun computeSlot(cp: Int): Int {
        // Emoji never use the text font, which may contain flat black-and-white versions of them.
        if (isEmoji(cp)) return emojiSlot(cp)
        val textSlot = if (has(FontSlot.TEXT)) FontSlot.TEXT.ordinal else NONE
        when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt() -> return NONE
            Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> return textSlot
        }
        val str = String(Character.toChars(cp))
        // Paint.hasGlyph also searches the system fallback chain, so the device check must come first:
        // after it fails, a custom font reporting the glyph really has it.
        if (systemPaint.hasGlyph(str)) return textSlot
        for (slot in GLYPH_ORDER) {
            if (has(slot) && paints[slot.ordinal].hasGlyph(str)) return slot.ordinal
        }
        return NONE
    }

    internal fun typefaceFor(slot: Int, style: Int): Typeface? {
        // Emoji are never styled, and use the combined emoji chain.
        if (slot == FontSlot.EMOJI.ordinal) return emojiTypeface
        val base = typefaces[slot] ?: return null
        val index = style and Typeface.BOLD_ITALIC
        val cache = styledTypefaces[slot]
        return cache[index] ?: Typeface.create(base, index).also { cache[index] = it }
    }

    private class FontSpan(private val plugin: FallbackFont, private val slot: Int) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)

        override fun updateMeasureState(tp: TextPaint) = apply(tp)

        private fun apply(paint: TextPaint) {
            if (slot == FontSlot.EMOJI.ordinal || slot == FontSlot.EMOJI_FALLBACK.ordinal) {
                paint.typeface = plugin.typefaceFor(slot, Typeface.NORMAL) ?: return
                return
            }
            val style = paint.typeface?.style ?: Typeface.NORMAL
            val replacement = plugin.typefaceFor(slot, style) ?: return
            // Synthesize styles the font file does not provide, like TypefaceSpan does.
            val missing = style and replacement.style.inv()
            if (missing and Typeface.BOLD != 0) paint.isFakeBoldText = true
            if (missing and Typeface.ITALIC != 0) paint.textSkewX = -0.25f
            paint.typeface = replacement
        }
    }

    // endregion

    companion object {
        private const val KEY_EMOJI_AS_TEXT = "emojiAsText"
        private const val NONE = -1
        private const val JUMBO_SIZE_DP = 40

        /** Fonts tried, in order, for characters the device cannot display. */
        private val GLYPH_ORDER = arrayOf(FontSlot.TEXT, FontSlot.FALLBACK, FontSlot.FALLBACK_2)

        /** Fonts tried for emoji no emoji font has and the device can't display. Never the text font. */
        private val EMOJI_FALLBACK_ORDER = arrayOf(FontSlot.FALLBACK, FontSlot.FALLBACK_2)

        // BMP codepoints drawn as emoji by default (Unicode Emoji_Presentation), as inclusive ranges.
        private val BMP_EMOJI = intArrayOf(
            0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE,
            0x2614, 0x2615, 0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1,
            0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4,
            0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD,
            0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E,
            0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
            0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55,
        )

        private fun isEmoji(cp: Int): Boolean {
            if (cp in 0x1F000..0x1FAFF) return true
            var i = 0
            while (i < BMP_EMOJI.size) {
                if (cp >= BMP_EMOJI[i] && cp <= BMP_EMOJI[i + 1]) return true
                i += 2
            }
            return false
        }

        private fun isJoiner(cp: Int) =
            cp == 0x200D || cp == 0x20E3 || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF ||
                cp in 0xE0020..0xE007F || cp in 0xE0100..0xE01EF

        // Lets the picker fragment reach the plugin after the settings page that opened it is gone.
        @Volatile var instance: FallbackFont? = null
    }
}
