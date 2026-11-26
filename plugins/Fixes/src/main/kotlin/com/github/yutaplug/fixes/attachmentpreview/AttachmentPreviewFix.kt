package com.github.yutaplug.fixes.attachmentpreview

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.patcher.PreHook
import com.github.yutaplug.fixes.Fix
import com.lytefast.flexinput.adapters.AttachmentPreviewAdapter
import com.lytefast.flexinput.model.Media
import b.b.a.d.i as ThumbnailViewHolder
import b.f.g.e.t as CenterInside

/**
 * The attachment previews above the chat box are a RecyclerView, so a preview square is reused for other
 * attachments. On Android 10+, a gallery image's thumbnail is loaded in the background and set as the square's
 * *placeholder* image (`ThumbnailViewHolder.c`). When the square is then reused for a file such as a .zip, its
 * image request fails, so it falls back to that placeholder: the old picture. The old thumbnail's background job
 * isn't cancelled either, so it can still finish afterwards and draw the old picture over the new file.
 *
 * Before a square is bound to a file, this puts the file icon back as its placeholder (with Fresco's default
 * scale for it, center inside, which the thumbnail changed to center crop) and cancels the old thumbnail job.
 */
internal class AttachmentPreviewFix :
    Fix(
        "AttachmentPreviewFix",
        "Attachment previews",
        "Fixes the preview of a file you add to a message (like a .zip) sometimes showing an image you added before.",
    ) {
    override fun start(context: Context) {
        patcher.patch(
            AttachmentPreviewAdapter::class.java,
            "onBindViewHolder",
            arrayOf(RecyclerView.ViewHolder::class.java, Integer.TYPE),
            PreHook { param ->
                val adapter = param.thisObject as AttachmentPreviewAdapter<*>
                val holder = param.args[0] as? ThumbnailViewHolder ?: return@PreHook
                val position = param.args[1] as Int
                val item = try {
                    adapter.a.get(position)
                } catch (_: Throwable) {
                    return@PreHook
                }
                // Images from the gallery get their own thumbnail, which replaces the placeholder anyway.
                if (item is Media) return@PreHook
                val view = holder.a()
                fileIcon(view.context)?.let { view.hierarchy.q(it, CenterInside.l) }
                // Cancels the old thumbnail job, and recycles its bitmap, which is no longer shown.
                holder.b()
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    /** The file icon the preview square starts with (`app:placeholderImage="?attr/ic_flex_input_file"`). */
    private fun fileIcon(context: Context) = try {
        val value = TypedValue()
        val attr = Utils.getResId("ic_flex_input_file", "attr")
        if (attr != 0 && context.theme.resolveAttribute(attr, value, true) && value.resourceId != 0) {
            ContextCompat.getDrawable(context, value.resourceId)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }
}
