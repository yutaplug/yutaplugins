package com.github.yutaplug.stickerpaste

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.sticker.Sticker
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.DimenUtils
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.input.WidgetChatInput
import com.aliucord.utils.ReflectUtils
import com.discord.utilities.stickers.StickerUtils
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.input.sticker.StickerItem
import com.discord.widgets.chat.input.sticker.StickerPickerListener
import com.discord.widgets.chat.input.sticker.StickerPickerViewModel
import com.discord.widgets.chat.input.sticker.WidgetStickerPicker
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Member

@AliucordPlugin
class StickerPaste : Plugin() {
    // One pasted sticker per channel, like desktop's per-channel draft.
    private val pending = HashMap<Long, Sticker>()

    // Channel whose pasted sticker was added to the chat box send that is in progress.
    private var injected: Pair<Long, Sticker>? = null

    // Views are looked up on every refresh instead of being cached from hooks, so the plugin
    // also works when it starts after the chat screen has already been set up.
    private var chatInput: WeakReference<WidgetChatInput>? = null
    private var editing = false
    private var card: Card? = null
    private var forcedSendButton = false

    override fun start(context: Context) {
        // Hooks on methods of a class that ART initializes after they were installed can be
        // reset (static ones especially), which made the send hooks work only some launches.
        for (name in HOOKED_CLASSES) {
            try {
                Class.forName(name, true, MessageManager::class.java.classLoader)
            } catch (e: Throwable) {
                logger.warn("Could not initialize $name", e)
            }
        }
        deoptimizeSendCallers()

        // Picking a sticker: take over the tap itself, before anything tries to send. This is
        // the hook that kept firing on compiled Discord when the MessageManager ones didn't.
        patcher.patch(
            WidgetStickerPicker::class.java.getDeclaredMethod("onStickerItemSelected", StickerItem::class.java),
            PreHook { param ->
                val item = param.args[0] as StickerItem
                // Let Discord handle stickers it wouldn't send (Nitro upsell, no permission).
                if (item.sendability != StickerUtils.StickerSendability.SENDABLE) return@PreHook
                val sticker = item.sticker
                pending[selectedChannel()] = sticker
                markStickerUsed(sticker)
                // Same as a successful send: the chat input closes the expression tray.
                val listener = ReflectUtils.getField(param.thisObject, "stickerPickerListener") as StickerPickerListener?
                listener?.onStickerPicked(sticker)
                param.result = null
                refresh()
            },
        )

        // Sending from the chat box: add the pasted sticker. Done on every level of the send
        // path so it still works when one of these hooks doesn't fire.
        val chatBoxSend = Class.forName("com.discord.widgets.chat.input.ChatInputViewModel\$sendMessage\$sendMessage\$1")
        patcher.patch(
            chatBoxSend.getDeclaredMethod("invoke", Long::class.javaPrimitiveType),
            PreHook { param ->
                val channel = param.args[0] as Long
                val send = param.thisObject
                val content = ReflectUtils.getField(send, "\$messageContent") as MessageContent
                val attachments = ReflectUtils.getField(send, "\$attachmentsRequest") as MessageManager.AttachmentsRequest?
                val onResult = ReflectUtils.getField(send, "\$onValidationResult") as Function1<Boolean, Unit>
                val taken = takePending(channel)
                if (taken == null) {
                    // Nothing to attach: drop sends Discord would turn into an empty message.
                    if (isEmpty(content.textContent, attachments)) {
                        param.result = null
                        onResult.invoke(false)
                    }
                    return@PreHook
                }
                val (key, sticker) = taken
                val manager = ReflectUtils.getField(send, "\$messageManager") as MessageManager
                val validated = synchronousValidationSucceeded.newInstance(send) as Function1<*, *>
                // The same call Discord makes, with the sticker added.
                val sent = try {
                    manager.sendMessage(
                        content.textContent,
                        content.mentionedUsers,
                        attachments,
                        channel,
                        listOf(sticker),
                        true,
                        ReflectUtils.getField(send, "\$onMessageTooLong") as Function2<Int, Int, Unit>?,
                        ReflectUtils.getField(send, "\$onFilesTooLarge") as Function2<Int, Boolean, Unit>?,
                        validated as Function1<MessageManager.MessageSendResult, Unit>,
                    )
                } catch (e: Throwable) {
                    restorePending(key, sticker)
                    throw e
                }
                if (!sent) restorePending(key, sticker)
                // Skip Discord's own call. Set before the callback: if a hook throws, Xposed drops
                // the result and would run the original send a second time.
                param.result = null
                try {
                    onResult.invoke(sent)
                } catch (e: Throwable) {
                    logger.error("Send result callback failed", e)
                }
            },
        )
        val sendMessage = MessageManager::class.java.declaredMethods.first {
            it.name == "sendMessage" && it.parameterTypes.size == 9
        }
        patcher.patch(
            sendMessage,
            PreHook { param ->
                val stickers = param.args[4] as List<*>
                if (stickers.isNotEmpty() || !fromChatBox()) return@PreHook
                val channel = param.args[3] as Long? ?: selectedChannel()
                val taken = takePending(channel)
                if (taken == null) {
                    // Discord ignores its own "empty content" check and would send it anyway.
                    if (isEmpty(param.args[0] as String, param.args[2] as MessageManager.AttachmentsRequest?)) {
                        param.result = false
                    }
                    return@PreHook
                }
                param.args[4] = listOf(taken.second)
                injected = taken
            },
        )
        val sendMessageDefault = MessageManager::class.java.declaredMethods.first { it.name == "sendMessage\$default" }
        patcher.patch(
            sendMessageDefault,
            PreHook { param ->
                val mask = param.args[DEFAULT_MASK] as Int
                if (mask and MASK_STICKERS == 0 && (param.args[DEFAULT_STICKERS] as List<*>).isNotEmpty()) return@PreHook
                if (!fromChatBox()) return@PreHook
                val channel = (if (mask and MASK_CHANNEL != 0) null else param.args[DEFAULT_CHANNEL] as Long?) ?: selectedChannel()
                val taken = takePending(channel)
                if (taken == null) {
                    val text = if (mask and MASK_CONTENT != 0) "" else param.args[DEFAULT_CONTENT] as String
                    val attachments =
                        if (mask and MASK_ATTACHMENTS != 0) null else param.args[DEFAULT_ATTACHMENTS] as MessageManager.AttachmentsRequest?
                    if (isEmpty(text, attachments)) param.result = false
                    return@PreHook
                }
                param.args[DEFAULT_STICKERS] = listOf(taken.second)
                param.args[DEFAULT_MASK] = mask and MASK_STICKERS.inv()
                injected = taken
            },
        )
        for (method in arrayOf(sendMessage, sendMessageDefault)) {
            patcher.patch(
                method,
                Hook { param ->
                    val (channel, sticker) = injected ?: return@Hook
                    injected = null
                    if (param.result != true) restorePending(channel, sticker)
                },
            )
        }

        patcher.patch(
            WidgetChatInput::class.java,
            "configureUI",
            arrayOf(ChatInputViewModel.ViewState::class.java),
            Hook { param ->
                chatInput = WeakReference(param.thisObject as WidgetChatInput)
                editing = (param.args[0] as? ChatInputViewModel.ViewState.Loaded)?.isEditing ?: false
                refresh()
            },
        )

        // FlexInputFragment.configureUI only enables the send button for text or attachments.
        val configureFlexUI = Class.forName("com.lytefast.flexinput.fragment.FlexInputFragment\$d")
            .declaredMethods.first { it.name == "invoke" && it.parameterTypes.size == 1 }
        patcher.patch(
            configureFlexUI,
            Hook {
                // Discord just rewrote the send button from its own state.
                forcedSendButton = false
                updateSendButton()
            },
        )
    }

    // Runs the methods between a sticker tap / send tap and sendMessage$default interpreted, so
    // compiled callers can't skip the hook.
    private fun deoptimizeSendCallers() {
        val callers = mutableListOf<Member>()
        StickerPickerViewModel::class.java.declaredMethods.filterTo(callers) { it.name == "onStickerSelected" }
        // Also the hooked wrapper itself: its hook only fired on compiled Discord once deoptimized.
        MessageManager::class.java.declaredMethods.filterTo(callers) { it.name.startsWith("sendMessage") }
        ChatInputViewModel::class.java.declaredMethods.filterTo(callers) { it.name.startsWith("sendMessage") }
        for (name in SEND_CALLER_CLASSES) {
            val clazz = try {
                Class.forName(name)
            } catch (_: ClassNotFoundException) {
                logger.warn("Missing $name")
                continue
            }
            clazz.declaredMethods.filterTo(callers) {
                it.name.startsWith("invoke") || it.name == "onStickerItemSelected" || it.name == "onSend"
            }
        }
        for (caller in callers) {
            if (!XposedBridge.deoptimizeMethod(caller)) logger.warn("Could not deoptimize $caller")
        }
    }

    // A pasted sticker leaves the chat box as soon as a send takes it, so the card goes away even
    // if nothing reports back; it is put back only when the send is rejected.
    // Returns the channel the sticker was pasted in with the sticker. Creating a thread from the
    // chat box sends to the new thread, so it falls back to the channel the sticker was pasted in.
    private fun takePending(channel: Long): Pair<Long, Sticker>? {
        val key = if (pending.containsKey(channel)) channel else selectedChannel()
        val sticker = pending.remove(key) ?: return null
        // Turn the forced send button off right away, so a quick second tap can't send an empty message.
        try {
            updateSendButton()
        } catch (e: Throwable) {
            logger.error("StickerPaste send button update failed", e)
        }
        refreshLater()
        return key to sticker
    }

    private fun isEmpty(text: String, attachments: MessageManager.AttachmentsRequest?): Boolean {
        if (hasNonWhitespace(text)) return false
        val files = attachments?.attachments
        return files == null || files.isEmpty()
    }

    private fun restorePending(channel: Long, sticker: Sticker) {
        if (pending.containsKey(channel)) return
        pending[channel] = sticker
        refreshLater()
    }

    // UI updates from inside the send hooks run afterwards on the main thread, so a failing UI
    // update can never interrupt a hook before it has attached the sticker.
    private fun refreshLater() {
        Utils.mainThread.post {
            try {
                refresh()
            } catch (e: Throwable) {
                logger.error("StickerPaste refresh failed", e)
            }
        }
    }

    // Keeps the picker's "recently used" list updated like a real send would. StoreStream's
    // sticker store getter isn't in the compile-time Discord stubs, so it's reached reflectively.
    private fun markStickerUsed(sticker: Sticker) {
        try {
            val store = StoreStream.Companion::class.java.getMethod("getStickers").invoke(StoreStream.Companion) ?: return
            store.javaClass.getMethod("onStickerUsed", Sticker::class.java).invoke(store, sticker)
        } catch (e: Throwable) {
            logger.warn("Could not mark sticker as used", e)
        }
    }

    // Whether the chat box is sending, read from the call stack (inlined callers still show up in it).
    // Sticker suggestions also go through ChatInputViewModel, but they already carry a sticker.
    private fun fromChatBox(): Boolean {
        for (frame in Thread.currentThread().stackTrace) {
            if (frame.className.startsWith(CHAT_INPUT_VIEW_MODEL)) return true
        }
        return false
    }

    private fun selectedChannel() = StoreStream.getChannelsSelected().id

    private fun currentSticker(): Sticker? = if (editing) null else pending[selectedChannel()]

    private fun activity(): Activity? = chatInput?.get()?.activity ?: Utils.appActivity

    private fun <T : View> find(name: String): T? {
        val activity = activity() ?: return null
        return activity.findViewById(Utils.getResId(name, "id"))
    }

    private fun refresh() {
        updateCard(currentSticker())
        updateSendButton()
    }

    private fun updateCard(sticker: Sticker?) {
        if (sticker == null) {
            card?.hide()
            return
        }
        // Discord's own sticker suggestions card, floating above the chat box.
        val suggestions = find<View>("stickers_suggestions") ?: return
        val host = suggestions.parent as? ConstraintLayout ?: return
        var current = card
        if (current == null || current.root.parent !== host) {
            current?.remove()
            current = Card(host, suggestions).also { card = it }
        }
        current.show(sticker)
    }

    private fun updateSendButton() {
        val container = find<FrameLayout>("send_btn_container") ?: return
        val blocked = find<View>("cannot_send_text")
        val canSend = blocked == null || blocked.visibility != View.VISIBLE
        val force = canSend && currentSticker() != null
        if (!force && !forcedSendButton) return

        val enabled: Boolean
        val visible: Boolean
        if (force) {
            enabled = true
            visible = true
        } else {
            // Same rules FlexInputFragment.configureUI uses for text and attachments.
            val text = find<EditText>("text_input")?.text?.toString().orEmpty()
            val hasAttachments = (find<RecyclerView>("attachment_preview_list")?.adapter?.itemCount ?: 0) > 0
            enabled = canSend && (hasNonWhitespace(text) || hasAttachments)
            visible = text.isNotEmpty() || hasAttachments
        }
        forcedSendButton = force

        find<ImageView>("send_btn_image")?.isEnabled = enabled
        container.isEnabled = enabled
        container.visibility = if (visible) View.VISIBLE else View.GONE
        // Buttons other plugins show for an empty chat box (like VoiceMessages' mic) sit in the
        // same corner; draw and route touches to the send button above them.
        if (force) container.bringToFront()
        val main = find<LinearLayout>("main_input_container") ?: return
        val params = main.layoutParams as? RelativeLayout.LayoutParams ?: return
        // Leave the margin alone when another plugin has re-anchored the input box.
        if (params.rules[RelativeLayout.LEFT_OF] != container.id) return
        params.rightMargin = if (visible) 0 else DimenUtils.dpToPixels(8)
        main.layoutParams = params
    }

    private fun hasNonWhitespace(text: String): Boolean {
        for (c in text) if (!Character.isWhitespace(c)) return true
        return false
    }

    // A second copy of widget_chat_input_sticker_suggestions, stacked on top of Discord's.
    private inner class Card(host: ConstraintLayout, suggestions: View) {
        val root: View = LayoutInflater.from(host.context)
            .inflate(Utils.getResId("widget_chat_input_sticker_suggestions", "layout"), host, false)
        private val preview = root.findViewById<StickerView>(Utils.getResId("chat_input_suggested_sticker_1", "id"))
        private var shownId: Long? = null

        init {
            root.id = View.generateViewId()
            for (i in 2..4) {
                root.findViewById<View>(Utils.getResId("chat_input_suggested_sticker_$i", "id"))?.visibility = View.GONE
            }
            root.findViewById<View>(Utils.getResId("chat_input_suggested_sticker_cancel", "id"))?.setOnClickListener {
                pending.remove(selectedChannel())
                refresh()
            }
            // Same placement as Discord's card; when that one is showing, this one sits above it.
            val params = ConstraintLayout.LayoutParams(suggestions.layoutParams as ConstraintLayout.LayoutParams)
            params.bottomToTop = suggestions.id
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            host.addView(root, params)
        }

        fun show(sticker: Sticker) {
            if (shownId != sticker.id) {
                shownId = sticker.id
                preview.d(sticker, null)
            }
            preview.contentDescription = sticker.h()
            root.visibility = View.VISIBLE
        }

        fun hide() {
            root.visibility = View.GONE
        }

        fun remove() {
            (root.parent as? ViewGroup)?.removeView(root)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pending.clear()
        refresh()
        card?.remove()
        card = null
        chatInput = null
        injected = null
    }

    private companion object {
        const val CHAT_INPUT_VIEW_MODEL = "com.discord.widgets.chat.input.ChatInputViewModel"

        val HOOKED_CLASSES = arrayOf(
            "com.discord.widgets.chat.MessageManager",
            "com.discord.widgets.chat.input.sticker.WidgetStickerPicker",
            "com.discord.widgets.chat.input.ChatInputViewModel\$sendMessage\$sendMessage\$1",
        )

        // Discord's result callback for a chat box send, built for our replacement call.
        val synchronousValidationSucceeded: Constructor<*> = Class.forName(
            "com.discord.widgets.chat.input.ChatInputViewModel\$sendMessage\$sendMessage\$1\$synchronousValidationSucceeded\$1",
        ).declaredConstructors[0].apply { isAccessible = true }

        // MessageManager.sendMessage$default(manager, content, mentions, attachments, channelId,
        // stickers, ..., defaultsMask, marker): a set mask bit means "use the default".
        const val DEFAULT_CONTENT = 1
        const val DEFAULT_ATTACHMENTS = 3
        const val DEFAULT_CHANNEL = 4
        const val DEFAULT_STICKERS = 5
        const val DEFAULT_MASK = 10
        const val MASK_CONTENT = 1
        const val MASK_ATTACHMENTS = 4
        const val MASK_CHANNEL = 8
        const val MASK_STICKERS = 16

        // Everything between a sticker tap / send tap and MessageManager.sendMessage.
        val SEND_CALLER_CLASSES = arrayOf(
            "com.discord.widgets.chat.input.sticker.WidgetStickerPicker",
            "com.discord.widgets.chat.input.sticker.WidgetStickerPicker\$setUpStickerRecycler\$1",
            "com.discord.widgets.chat.input.ChatInputViewModel\$sendMessage\$1",
            "com.discord.widgets.chat.input.ChatInputViewModel\$sendMessage\$sendMessage\$1",
            "com.discord.widgets.chat.input.ChatInputViewModel\$sendMessage\$messageResendCompressedHandler\$1",
            "com.discord.widgets.chat.input.WidgetChatInput\$configureSendListeners\$2",
            "com.discord.widgets.chat.input.WidgetChatInput\$configureSendListeners\$3",
            "com.discord.widgets.chat.input.WidgetChatInput\$configureSendListeners\$7",
        )
    }
}
