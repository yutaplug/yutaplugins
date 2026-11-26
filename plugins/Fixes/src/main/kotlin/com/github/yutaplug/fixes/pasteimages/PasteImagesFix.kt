package com.github.yutaplug.fixes.pasteimages

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.TextView
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.aliucord.patcher.PreHook
import com.github.yutaplug.fixes.Fix
import com.lytefast.flexinput.widget.FlexEditText

/**
 * Discord 126.21's chat box only accepts images that a keyboard sends with `commitContent` (GIF
 * tabs). A normal paste (long-press Paste, or a keyboard clipboard bar that pastes through
 * `performContextMenuAction`) goes through TextView's text-only paste, which drops images. This
 * hands clipboard images to the chat box's own keyboard-image handler instead, so they are attached
 * the same way.
 */
internal class PasteImagesFix :
    Fix(
        "PasteImagesFix",
        "Paste images",
        "Lets you paste images from the clipboard into the chat box as attachments.",
    ) {
    override fun start(context: Context) {
        patcher.patch(
            TextView::class.java,
            "onTextContextMenuItem",
            arrayOf(Int::class.javaPrimitiveType!!),
            PreHook { param ->
                val editText = param.thisObject as? FlexEditText ?: return@PreHook
                if (param.args[0] as Int != android.R.id.paste) return@PreHook
                try {
                    if (attachClipboardImages(editText)) param.result = true
                } catch (e: Throwable) {
                    logger.error("Failed to paste clipboard image", e)
                }
            },
        )
    }

    /** Returns whether any image was attached; otherwise the normal text paste runs. */
    private fun attachClipboardImages(editText: FlexEditText): Boolean {
        val context = editText.context
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = clipboard.primaryClip ?: return false
        val description = clip.description
        val resolver = context.contentResolver

        val images = ArrayList<Pair<Uri, String>>()
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val mime = (if (uri.scheme == "content") resolver.getType(uri) else null)
                ?: (0 until description.mimeTypeCount).map { description.getMimeType(it) }
                    .firstOrNull { it.startsWith("image/") }
                ?: continue
            if (mime.startsWith("image/")) images.add(uri to mime)
        }
        if (images.isEmpty()) return false

        val handler = editText.inputContentHandler
        for ((uri, mime) in images) {
            handler.invoke(InputContentInfoCompat(uri, ClipDescription(description.label, arrayOf(mime)), null))
        }
        return true
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
