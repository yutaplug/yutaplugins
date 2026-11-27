package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.net.Uri
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreStream
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler
import com.discord.widgets.chat.list.model.WidgetChatListModelMessages.MessagesWithMetadata
import de.robv.android.xposed.XC_MethodHook
import java.io.File
import rx.Observable
import rx.functions.Func2
import rx.functions.Func3
import rx.subjects.BehaviorSubject
import com.discord.api.message.Message as ApiMessage

@AliucordPlugin
class BetterMessageLogger : Plugin() {
    // Bumped whenever the log changes, so the chat list is built again.
    private val version = BehaviorSubject.l0(0L)
    private var versionNumber = 0L

    @Volatile private var running = false

    internal lateinit var log: MessageLog
        private set

    override fun start(context: Context) {
        val report: (String, Throwable) -> Unit = { action, error -> logger.error("Could not $action", error) }
        log = MessageLog(settings, File(Constants.BASE_PATH, DATABASE_NAME), report, ::logChanged)
        running = true
        instance = this
        settingsTab = SettingsTab(BetterMessageLoggerSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
        try {
            log.decorations = ChatDecorations(settings, log::record, log::onShown, report).also { it.patch(patcher) }
            hookStore()
            hookChatList()
            hookSavedFiles()
            MessageActions(log, report).patch(patcher)
            ContextMenus(report).patch(patcher)
            log.start(File(Constants.BASE_PATH, MEDIA_FOLDER))
            ReplacedPlugins.removeLater(logger)
        } catch (error: Throwable) {
            stop(context)
            throw error
        }
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        if (::log.isInitialized) {
            log.decorations?.close()
            log.decorations = null
            log.stop()
        }
        if (instance === this) instance = null
    }

    private fun hook(owner: Class<*>, name: String, vararg types: Class<*>, hook: XC_MethodHook) {
        // Looked up directly so a missing method fails loudly at start instead of silently logging nothing.
        patcher.patch(owner.getDeclaredMethod(name, *types), hook)
    }

    private fun hookStore() {
        hook(
            StoreMessages::class.java,
            "handleMessageCreate",
            List::class.java,
            hook = Hook { frame -> guard("read new messages") { log.onCreated((frame.args[0] as List<*>).filterIsInstance<ApiMessage>()) } },
        )
        hook(
            StoreMessages::class.java,
            "handleMessagesLoaded",
            StoreMessagesLoader.ChannelChunk::class.java,
            hook = Hook { frame ->
                guard("read loaded messages") {
                    log.onLoaded((frame.args[0] as StoreMessagesLoader.ChannelChunk).messages.orEmpty())
                }
            },
        )
        // Before Discord applies the update, while the previous version is still in its store.
        hook(
            StoreMessages::class.java,
            "handleMessageUpdate",
            ApiMessage::class.java,
            hook = PreHook { frame ->
                guard("log an edit") { log.onUpdate(frame.thisObject as StoreMessages, frame.args[0] as ApiMessage) }
            },
        )
        hook(
            StoreMessages::class.java,
            "handleMessageDelete",
            ModelMessageDelete::class.java,
            hook = PreHook { frame ->
                if (fromHideMessages()) return@PreHook
                guard("log a deletion") {
                    log.onDelete(frame.thisObject as StoreMessages, frame.args[0] as ModelMessageDelete)
                }
            },
        )
    }

    private fun hookChatList() {
        // What the chat shows: hidden messages out, deleted ones back in. Discord's own message store stays as is,
        // since it paginates and jumps with it.
        hook(
            MessagesWithMetadata.Companion::class.java,
            "get",
            Channel::class.java,
            hook = Hook { frame ->
                @Suppress("UNCHECKED_CAST")
                val source = frame.result as? Observable<MessagesWithMetadata> ?: return@Hook
                val channelId = (frame.args[0] as Channel).k()
                frame.result = Observable.i(
                    source,
                    StoreStream.getMessages().observeIsDetached(channelId),
                    version,
                    Func3<MessagesWithMetadata, Boolean, Long, MessagesWithMetadata> { model, detached, _ ->
                        val messages = try {
                            log.arrange(channelId, model.messages, detached == true)
                        } catch (error: Exception) {
                            logger.error("Could not add deleted messages", error)
                            model.messages
                        }
                        if (messages === model.messages) {
                            model
                        } else {
                            model.copy(
                                messages,
                                model.messageState,
                                model.messageThreads,
                                model.threadCountsAndLatestMessages,
                                model.messageReplyState,
                                model.parentChannelMessageReplyState,
                            )
                        }
                    },
                )
            },
        )
        // The long-press sheet looks its message up by ID; deleted ones come from the log.
        hook(
            StoreMessages::class.java,
            "observeMessagesForChannel",
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            hook = Hook { frame ->
                @Suppress("UNCHECKED_CAST")
                val source = frame.result as? Observable<Message?> ?: return@Hook
                val channelId = frame.args[0] as Long
                val id = frame.args[1] as Long
                frame.result = Observable.j(
                    source,
                    version,
                    Func2<Message?, Long, Message?> { message, _ -> message ?: log.deletedMessage(channelId, id) },
                )
            },
        )
    }

    /** Saved media is a local file; Discord would otherwise treat it like a web link. */
    private fun hookSavedFiles() {
        hook(
            EmbedResourceUtils::class.java,
            "getPreviewUrls",
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            hook = Hook { frame -> log.media?.previewUrls(frame.args[0] as String)?.let { frame.result = it } },
        )
        hook(
            UriHandler::class.java,
            "handleOrUntrusted",
            Context::class.java,
            String::class.java,
            String::class.java,
            hook = PreHook { frame ->
                val file = log.media?.fileOf(frame.args[1] as? String) ?: return@PreHook
                LocalFiles.open(frame.args[0] as Context, file)
                frame.result = null
            },
        )
        hook(
            WidgetChatListAdapterEventsHandler::class.java,
            "onQuickDownloadClicked",
            Uri::class.java,
            String::class.java,
            hook = PreHook { frame ->
                val file = log.media?.fileOf((frame.args[0] as Uri).toString()) ?: return@PreHook
                LocalFiles.download(file, frame.args[1] as String)
                frame.result = true
            },
        )
    }

    private fun logChanged() {
        // Rx subjects aren't thread-safe, so every emission happens on the main thread.
        Utils.mainThread.post { if (running) version.onNext(++versionNumber) }
    }

    private inline fun guard(action: String, work: () -> Unit) {
        try {
            work()
        } catch (error: Exception) {
            logger.error("Could not $action", error)
        }
    }

    companion object {
        internal const val DATABASE_NAME = "BetterMessageLogger.db"
        internal const val EXPORT_NAME = "BetterMessageLogger.txt"
        internal const val MEDIA_FOLDER = "BetterMessageLoggerMedia"

        @Volatile internal var instance: BetterMessageLogger? = null
            private set

        /** The HideMessages plugin hides a message by "deleting" it locally; that isn't a real deletion. */
        private fun fromHideMessages(): Boolean {
            for (frame in Thread.currentThread().stackTrace) {
                // Not contains(ignoreCase = true): that walks an IntRange, which Discord's stdlib breaks.
                if (frame.className.toLowerCase(java.util.Locale.ROOT).contains("hidemessages")) return true
            }
            return false
        }
    }
}
