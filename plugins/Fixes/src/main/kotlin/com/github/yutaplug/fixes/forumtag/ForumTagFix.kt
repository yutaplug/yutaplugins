package com.github.yutaplug.fixes.forumtag

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import com.aliucord.wrappers.ChannelWrapper
import com.discord.api.permission.Permission
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.channels.list.WidgetChannelsListItemThreadActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemThreadDraftForm
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.ThreadDraftFormEntry
import com.github.yutaplug.fixes.Fix
import com.lytefast.flexinput.R
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.StringReader
import java.util.Properties

/** Ported from TsyQax's ForumTagFix: https://github.com/tsyqax/aliucord_plugins_tsq */
internal class ForumTagFix : Fix(
    "ForumTagFix",
    "Forum tags",
    "Fixes not being able to select tags when creating a forum post, and adds Change Tags to a post's long-press menu.",
    author = "TsyQax",
) {
    private val selectedTagIds: MutableList<Long> = mutableListOf()
    private var postName: String? = null
    private var postContent: String? = null

    override fun start(context: Context) {
        val changeItemId = View.generateViewId()
        val changeIcon = ContextCompat.getDrawable(Utils.appActivity, R.e.ic_edit_24dp)!!.mutate()
        Utils.tintToTheme(changeIcon)

        // Remember the post's title and body; the tags are sent as payload_json below.
        patcher.patch(
            RestAPI::class.java.getDeclaredMethod(
                "createThreadWithMessage",
                Long::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                List::class.java,
                List::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaObjectType,
                Array<MultipartBody.Part>::class.java,
            ),
            PreHook { param ->
                if (selectedTagIds.isEmpty()) return@PreHook
                postName = param.args[1] as? String
                postContent = param.args[2] as? String
            },
        )

        // MultipartBody.Builder.build(): add the selected tags to the forum post request.
        patcher.patch(
            MultipartBody.a::class.java.getDeclaredMethod("b"),
            PreHook { param ->
                if (selectedTagIds.isEmpty()) return@PreHook
                try {
                    val safeName = postName?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
                    val safeContent =
                        postContent?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.replace("\n", "\\n") ?: ""
                    val json = String.format(
                        "{\"name\":\"%s\",\"content\":\"%s\",\"applied_tags\":%s}",
                        safeName,
                        safeContent,
                        selectedTagIds.toString().replace(" ", ""),
                    )
                    val mediaType = MediaType.b("application/json; charset=utf-8")
                    val headers = Headers.j.c("Content-Disposition", "form-data; name=\"payload_json\"")
                    val part = MultipartBody.Part.a(headers, RequestBody.create(mediaType, json))
                    (param.thisObject as MultipartBody.a).a(part)
                    selectedTagIds.clear()
                } catch (error: Exception) {
                    logger.error("Could not add forum tags", error)
                }
            },
        )

        // "Change Tags" in a forum post's long-press actions.
        patcher.patch(
            WidgetChannelsListItemThreadActions::class.java.getDeclaredMethod(
                "configureUI",
                WidgetChannelsListItemThreadActions.Model::class.java,
            ),
            PreHook { param ->
                val actions = param.thisObject as WidgetChannelsListItemThreadActions
                val layout = (actions.view as? NestedScrollView)?.getChildAt(0) as? LinearLayout ?: return@PreHook
                val channelId = StoreStream.getChannelsSelected().id
                val permissions = StoreStream.getPermissions().permissionsByChannel[channelId]
                val thread = (param.args[0] as WidgetChannelsListItemThreadActions.Model).channel
                val threadWrapper = ChannelWrapper(thread)
                val parent = ChannelWrapper(StoreStream.getChannels().getChannel(channelId))
                if (parent.type != 15 && parent.type != 16) return@PreHook
                if (!PermissionUtils.can(Permission.MANAGE_THREADS, permissions)) return@PreHook
                if (layout.findViewById<View>(changeItemId) != null) return@PreHook

                val availableTags = parent.availableTags
                val appliedTags = threadWrapper.appliedTags
                val item = TextView(layout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = changeItemId
                    text = "Change Tags"
                    setCompoundDrawablesRelativeWithIntrinsicBounds(changeIcon, null, null, null)
                }
                val leaveId = Utils.getResId("channels_list_item_thread_actions_leave", "id")
                var index = -1
                for (i in 0 until layout.childCount) {
                    if (layout.getChildAt(i).id == leaveId) {
                        index = i - 2
                        break
                    }
                }
                layout.addView(item, if (index >= 0) index else minOf(7, layout.childCount))

                val sheet = TagPickerSheet(availableTags.toMutableList(), selectedTagIds, appliedTags.toMutableList()) {
                    val body = hashMapOf<String, Any?>("applied_tags" to ArrayList(selectedTagIds))
                    selectedTagIds.clear()
                    Utils.threadPool.execute {
                        try {
                            Http.Request.newDiscordRequest("/channels/${thread.k()}", "PATCH").executeWithJson(body)
                        } catch (error: Exception) {
                            Utils.showToast(errorMessage(error))
                            logger.error("Could not change tags", error)
                        }
                    }
                }
                item.setOnClickListener {
                    try {
                        sheet.show(actions.parentFragmentManager, "forum_tag_picker_sheet")
                    } catch (error: Exception) {
                        logger.error("Could not open the tag picker", error)
                    }
                }
            },
        )

        // "Select Tags" link while writing a new forum post.
        patcher.patch(
            WidgetChatListAdapterItemThreadDraftForm::class.java.getDeclaredMethod(
                "onConfigure",
                Int::class.javaPrimitiveType,
                ChatListEntry::class.java,
            ),
            Hook { param ->
                try {
                    val entry = param.args[1] as? ThreadDraftFormEntry ?: return@Hook
                    val parentChannel = entry.parentChannel ?: return@Hook
                    val root = (param.thisObject as WidgetChatListAdapterItemThreadDraftForm).itemView as? ViewGroup
                        ?: return@Hook
                    var indicator = root.findViewWithTag<TextView>(INDICATOR_TAG)
                    val availableTags = ChannelWrapper(parentChannel).availableTags
                    if (availableTags.isNullOrEmpty()) {
                        if (indicator != null) root.removeView(indicator)
                        return@Hook
                    }
                    selectedTagIds.clear()
                    if (indicator == null) {
                        indicator = TextView(root.context, null, 0, R.i.UiKit_Settings_Text).apply {
                            tag = INDICATOR_TAG
                            text = "Select Tags"
                            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                            setPadding(0, DimenUtils.dpToPx(10), 0, 0)
                            isClickable = true
                        }
                        root.addView(indicator)
                    }
                    indicator.setOnClickListener {
                        val manager = (root.context as? FragmentActivity)?.supportFragmentManager
                            ?: return@setOnClickListener
                        try {
                            TagPickerSheet(availableTags.toMutableList(), selectedTagIds, null) {}
                                .show(manager, "forum_tag_picker_sheet")
                        } catch (error: Exception) {
                            logger.error("Could not open the tag picker", error)
                        }
                    }
                } catch (error: Exception) {
                    logger.error("Could not add the tag picker", error)
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        selectedTagIds.clear()
    }

    private fun errorMessage(error: Exception): String {
        val raw = error.message ?: ""
        val message = if (raw.contains("\"message\": \"")) {
            raw.split("\"message\": \"")[1].split("\"")[0]
        } else {
            raw
        }
        // Decode \uXXXX escapes in Discord's error message.
        return try {
            Properties().apply { load(StringReader("m=$message")) }.getProperty("m") ?: raw
        } catch (_: Exception) {
            raw
        }
    }

    private companion object {
        const val INDICATOR_TAG = "forumTagFix_indicator"
    }
}
