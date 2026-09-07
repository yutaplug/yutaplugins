package com.github.yutaplug.bettermessagelogger;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.TextViewCompat;

import com.aliucord.Constants;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.discord.api.utcdatetime.UtcDateTime;
import com.discord.models.domain.ModelMessageDelete;
import com.discord.stores.StoreMessages;
import com.discord.stores.StoreMessagesLoader;
import com.discord.widgets.chat.list.actions.WidgetChatListActions;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.MessageEntry;
import com.discord.widgets.chat.list.model.WidgetChatListModelMessages;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.drawable.DrawableCompat;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.discord.api.premium.PremiumTier;

import rx.Observable;
import rx.functions.Func2;
import rx.subjects.BehaviorSubject;

@SuppressWarnings("unused")
@AliucordPlugin
public class BetterMessageLogger extends Plugin {
    private static final String DB_NAME = "BetterMessageLogger.db";
    private static final String TXT_NAME = "BetterMessageLogger.txt";
    private static final String DELETED_LABEL = " (deleted)";
    static final String DELETED_LABEL_COLOR = "deletedLabelColor";
    static final String DEFAULT_DELETED_LABEL_COLOR = "#FFFF0000";
    private static final int MAX_CACHED_MESSAGES = 512;
    private static final int MAX_TRANSIENT_RECORDS = 512;
    private static BetterMessageLogger instance;

    private final Map<Long, MessageRecord> records = new ConcurrentHashMap<>();
    private final MessageCache liveMessages = new MessageCache(MAX_CACHED_MESSAGES);
    private final MessageCache boundMessages = new MessageCache(MAX_CACHED_MESSAGES);
    private final Map<WidgetChatListAdapterItemMessage, Long> boundMessageItems =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Set<Long> deletedMessageIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> recentlyDeletedMessageIds = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> transientRecordIds =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final Map<Long, LoadedRange> loadedRanges = new ConcurrentHashMap<>();
    private final BehaviorSubject<Long> revision = BehaviorSubject.l0(0L);
    private final AtomicLong revisionNumber = new AtomicLong();
    private MessageLoggerDatabase database;
    private volatile boolean databaseEnabled;
    private Context context;

    static BetterMessageLogger getInstance() {
        return instance;
    }

    @Override
    public void start(Context context) throws Throwable {
        this.context = context.getApplicationContext();
        instance = this;
        settingsTab = new SettingsTab(BetterMessageLoggerSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings);
        databaseEnabled = settings.getBool("database", false);
        if (databaseEnabled) openDatabase();

        patcher.patch(StoreMessages.class, "handleMessageCreate", new Class<?>[]{List.class}, new Hook(frame -> {
            Object value = frame.args[0];
            if (value instanceof List<?>) {
                for (Object item : (List<?>) value) {
                    if (item instanceof com.discord.api.message.Message) {
                        handleCreatedApiMessage((com.discord.api.message.Message) item);
                    }
                }
            }
        }));

        patcher.patch(StoreMessages.class, "handleMessageUpdate",
                new Class<?>[]{com.discord.api.message.Message.class}, new Hook(frame -> {
            if (frame.args[0] instanceof com.discord.api.message.Message) {
                handleUpdatedApiMessage((com.discord.api.message.Message) frame.args[0]);
            }
        }));

        patcher.patch(StoreMessages.class, "handleMessageDelete", new Class<?>[]{ModelMessageDelete.class}, new PreHook(frame -> {
            if (frame.thisObject instanceof StoreMessages && frame.args[0] instanceof ModelMessageDelete) {
                captureMessageBeforeDelete((StoreMessages) frame.thisObject, (ModelMessageDelete) frame.args[0]);
            }
        }));

        patcher.patch(StoreMessages.class, "handleMessageDelete", new Class<?>[]{ModelMessageDelete.class}, new Hook(frame -> {
            if (frame.args[0] instanceof ModelMessageDelete) handleDeletedMessages((ModelMessageDelete) frame.args[0]);
        }));

        patcher.patch(StoreMessages.class, "handleMessagesLoaded", new Class<?>[]{StoreMessagesLoader.ChannelChunk.class}, new PreHook(frame -> {
            if (frame.args[0] instanceof StoreMessagesLoader.ChannelChunk) {
                resetLoadedRange((StoreMessagesLoader.ChannelChunk) frame.args[0]);
            }
        }));

        patcher.patch(StoreMessages.class, "handleMessagesLoaded", new Class<?>[]{StoreMessagesLoader.ChannelChunk.class}, new Hook(frame -> {
            if (frame.args[0] instanceof StoreMessagesLoader.ChannelChunk) {
                StoreMessagesLoader.ChannelChunk chunk = (StoreMessagesLoader.ChannelChunk) frame.args[0];
                List<com.discord.models.message.Message> loaded = chunk.getMessages();
                if (loaded != null) for (com.discord.models.message.Message message : loaded) remember(message);
            }
        }));

        // The message action sheet uses the two-argument lookup. A deleted message is
        // absent from Discord's live store by then, so give it back from our log for
        // context-menu actions without changing the live pagination observable.
        patcher.patch(StoreMessages.class, "observeMessagesForChannel",
                new Class<?>[]{long.class, long.class}, new Hook(frame -> {
            if (!(frame.getResult() instanceof Observable<?>)) return;
            long channelId = (Long) frame.args[0];
            long messageId = (Long) frame.args[1];
            @SuppressWarnings("unchecked")
            Observable<com.discord.models.message.Message> messages =
                    (Observable<com.discord.models.message.Message>) (Observable<?>) frame.getResult();
            frame.setResult(Observable.j(messages, revision,
                    new Func2<com.discord.models.message.Message, Long, com.discord.models.message.Message>() {
                        @Override
                        public com.discord.models.message.Message call(
                                com.discord.models.message.Message current, Long ignoredRevision) {
                            if (current != null) return current;
                            MessageRecord record = records.get(messageId);
                            if (record == null || record.channelId != channelId
                                    || (!record.deleted && !deletedMessageIds.contains(messageId))
                                    || !shouldKeep(record)) return null;
                            return record.toMessage();
                        }
                    }));
        }));

        // Keep StoreMessages.observeMessagesForChannel untouched. Discord's message loader
        // uses that observable to calculate older/newer pagination and jump boundaries.
        // Restored database rows belong in the display model, not in those live boundaries.
        patcher.patch(WidgetChatListModelMessages.MessagesWithMetadata.Companion.class, "get",
                new Class<?>[]{com.discord.api.channel.Channel.class}, new Hook(frame -> {
            if (!(frame.args[0] instanceof com.discord.api.channel.Channel)
                    || !(frame.getResult() instanceof Observable<?>)) return;
            com.discord.api.channel.Channel channel = (com.discord.api.channel.Channel) frame.args[0];
            @SuppressWarnings("unchecked")
            Observable<WidgetChatListModelMessages.MessagesWithMetadata> metadata =
                    (Observable<WidgetChatListModelMessages.MessagesWithMetadata>) (Observable<?>) frame.getResult();
            long channelId = channel.k();
            frame.setResult(Observable.j(metadata, revision, new Func2<WidgetChatListModelMessages.MessagesWithMetadata, Long,
                    WidgetChatListModelMessages.MessagesWithMetadata>() {
                @Override
                public WidgetChatListModelMessages.MessagesWithMetadata call(
                        WidgetChatListModelMessages.MessagesWithMetadata current, Long ignoredRevision) {
                    return withDeletedMessageMetadata(channelId, current);
                }
            }));
        }));

        patcher.patch(WidgetChatListAdapterItemMessage.class, "onConfigure",
                new Class<?>[]{int.class, com.discord.widgets.chat.list.entries.ChatListEntry.class}, new Hook(frame -> {
                    if (!(frame.args[1] instanceof MessageEntry)) return;
                    MessageEntry entry = (MessageEntry) frame.args[1];
                    WidgetChatListAdapterItemMessage item = (WidgetChatListAdapterItemMessage) frame.thisObject;
                    long messageId = entry.getMessage().getId();
                    boundMessageItems.put(item, messageId);
                    boundMessages.put(messageId, entry.getMessage());
                    scheduleDeletedLabel(item, messageId);
                }));

        patcher.patch(WidgetChatListActions.class, "configureUI",
                new Class<?>[]{WidgetChatListActions.Model.class}, new Hook(frame -> {
                    if (!(frame.args[0] instanceof WidgetChatListActions.Model)) return;
                    WidgetChatListActions.Model model = (WidgetChatListActions.Model) frame.args[0];
                    com.discord.models.message.Message message = model.getMessage();
                    if (message == null) return;
                    MessageRecord record = records.get(message.getId());
                    if (record == null || !shouldKeep(record)) return;
                    WidgetChatListActions sheet = (WidgetChatListActions) frame.thisObject;
                    if (!record.edits.isEmpty()) addHistoryAction(sheet, record);
                    if (record.deleted || !record.edits.isEmpty()) addDeleteAction(sheet, record);
        }));
    }

    private void captureMessageBeforeDelete(StoreMessages store, ModelMessageDelete event) {
        List<Long> ids = event.getMessageIds();
        if (ids == null) return;
        for (Long id : ids) {
            if (id == null) continue;
            com.discord.models.message.Message live = null;
            try {
                live = store.getMessage(event.getChannelId(), id);
            } catch (Throwable ignored) {
                // The store can be unavailable while Discord is switching channels.
            }
            if (live == null) live = liveMessages.get(id);
            if (live == null) live = boundMessages.get(id);
            if (live == null) continue;

            recentlyDeletedMessageIds.add(id);
            remember(live);
            MessageRecord record = records.get(id);
            if (record != null) record.runtime = live;
        }
    }
    private void resetLoadedRange(StoreMessagesLoader.ChannelChunk chunk) {
        if (!chunk.isInitial() && !chunk.isJump()) return;
        long channelId = chunk.getChannelId();
        loadedRanges.remove(channelId);
        for (Long id : new ArrayList<>(recentlyDeletedMessageIds)) {
            MessageRecord record = records.get(id);
            if (record == null || record.channelId == channelId) recentlyDeletedMessageIds.remove(id);
        }
    }

    private void handleCreatedApiMessage(com.discord.api.message.Message message) {
        com.discord.models.message.Message model;
        try {
            model = new com.discord.models.message.Message(message);
        } catch (Throwable error) {
            logger.error("Could not read a created message", error);
            return;
        }
        remember(model);
    }

    private void handleUpdatedApiMessage(com.discord.api.message.Message message) {
        long id = message.o();
        MessageRecord record = records.get(id);
        com.discord.models.message.Message old = liveMessages.get(id);
        if (record == null && old != null) record = createRecord(old);
        if (record == null) {
            try {
                record = createRecord(new com.discord.models.message.Message(message));
            } catch (Throwable ignored) {
                return;
            }
        }
        String newContent = message.i();
        if (newContent != null && !newContent.equals(record.content)) {
            record.addEdit(record.content, editTime(message));
            record.content = newContent;
        }
        if (old != null) {
            try {
                com.discord.models.message.Message merged = old.merge(message);
                liveMessages.put(id, merged);
                record.runtime = record.deleted || deletedMessageIds.contains(id) ? merged : null;
            } catch (Throwable ignored) {
                // Keep the last complete model if Discord sends an unusually small update payload.
            }
        }
        if (!shouldKeep(record)) {
            removeRecord(id);
            return;
        }
        storeRecord(record);
        persist(record);
        bumpRevision();
    }
    private void handleDeletedMessages(ModelMessageDelete event) {
        List<Long> ids = event.getMessageIds();
        if (ids == null) return;
        for (Long id : ids) {
            if (id == null) continue;
            deletedMessageIds.add(id);
            recentlyDeletedMessageIds.add(id);
            MessageRecord record = records.get(id);
            com.discord.models.message.Message live = null;
            if (record == null || record.runtime == null) {
                live = liveMessages.get(id);
                if (live == null) live = boundMessages.get(id);
                if (record == null && live != null) record = createRecord(live);
            }
            if (record != null && shouldKeep(record)) {
                record.deleted = true;
                record.deletedTimestamp = System.currentTimeMillis();
                if (live != null) record.runtime = live;
                storeRecord(record);
                persist(record);
            }
        }
        refreshVisibleDeletedTags();
        bumpRevision();
    }
    private void remember(com.discord.models.message.Message message) {
        if (message == null) return;
        long messageId = message.getId();
        liveMessages.put(messageId, message);
        MessageRecord record = records.get(messageId);
        if (record == null) record = createRecord(message);
        else {
            record.runtime = record.deleted || deletedMessageIds.contains(messageId) ? message : null;
            if (message.getContent() != null) record.content = message.getContent();
            if (message.getEditedTimestamp() != null) record.editedTimestamp = message.getEditedTimestamp().g();
        }
        if (deletedMessageIds.contains(messageId)) {
            record.deleted = true;
            record.runtime = message;
            if (record.deletedTimestamp == null) record.deletedTimestamp = System.currentTimeMillis();
        }
        if (!shouldKeep(record)) {
            removeRecord(messageId);
            return;
        }
        storeRecord(record);
        persist(record);
    }
    private MessageRecord createRecord(com.discord.models.message.Message message) {
        com.discord.api.user.User author = message.getAuthor();
        String avatar = null;
        if (author != null && author.a() != null) avatar = author.a().a();
        Long guildId = resolveGuildId(message.getChannelId(), message.getGuildId());
        return new MessageRecord(message.getId(), message.getChannelId(), guildId,
                author == null ? 0L : author.getId(), author == null ? "Unknown user" : author.getUsername(), avatar,
                author != null && Boolean.TRUE.equals(author.e()), message.getContent() == null ? "" : message.getContent(),
                message.getTimestamp() == null ? System.currentTimeMillis() : message.getTimestamp().g(),
                message.getEditedTimestamp() == null ? null : message.getEditedTimestamp().g(), false, null,
                "", null);
    }

    private List<com.discord.models.message.Message> withDeletedMessages(long channelId,
                                                                          List<com.discord.models.message.Message> current) {
        List<com.discord.models.message.Message> result = new ArrayList<>();
        if (current != null) result.addAll(current);

        // Only restore rows inside the live range Discord has loaded for this model.
        // This keeps deleted messages attached to their own batches: rows from older or
        // newer batches should not be inserted before those live messages arrive.
        long oldestLoadedId = Long.MAX_VALUE;
        long newestLoadedId = Long.MIN_VALUE;
        Set<Long> present = new HashSet<>();
        for (com.discord.models.message.Message message : result) {
            present.add(message.getId());
            if (!message.isLocal()) {
                oldestLoadedId = Math.min(oldestLoadedId, message.getId());
                newestLoadedId = Math.max(newestLoadedId, message.getId());
            }
        }
        LoadedRange loadedRange = loadedRanges.get(channelId);
        if (oldestLoadedId != Long.MAX_VALUE) {
            if (loadedRange == null) {
                LoadedRange created = new LoadedRange();
                LoadedRange existing = loadedRanges.putIfAbsent(channelId, created);
                loadedRange = existing == null ? created : existing;
            }
            loadedRange.include(oldestLoadedId, newestLoadedId);
        }

        long restoredOldestId = loadedRange == null ? Long.MAX_VALUE : loadedRange.oldest();
        long restoredNewestId = loadedRange == null ? Long.MIN_VALUE : loadedRange.newest();

        for (MessageRecord record : records.values()) {
            boolean recentlyDeleted = recentlyDeletedMessageIds.contains(record.id);
            if (record.channelId != channelId || (!record.deleted && !deletedMessageIds.contains(record.id))
                    || (!recentlyDeleted && (record.id < restoredOldestId || record.id > restoredNewestId))
                    || !shouldKeep(record) || present.contains(record.id)) continue;
            result.add(record.toMessage());
        }
        result.sort(Comparator.comparingLong(com.discord.models.message.Message::getId));
        return result;
    }

    private WidgetChatListModelMessages.MessagesWithMetadata withDeletedMessageMetadata(
            long channelId, WidgetChatListModelMessages.MessagesWithMetadata current) {
        if (current == null) return null;
        List<com.discord.models.message.Message> merged = withDeletedMessages(channelId, current.getMessages());
        return current.copy(merged, current.getMessageState(), current.getMessageThreads(),
                current.getThreadCountsAndLatestMessages(), current.getMessageReplyState(),
                current.getParentChannelMessageReplyState());
    }

    private boolean shouldKeep(MessageRecord record) {
        if (settings.getBool("ignoreOwn", false)) {
            try {
                if (record.authorId == StoreStreamAccess.meId()) return false;
            } catch (Throwable ignored) {
                // The user store can be unavailable during logout; keep the record until it is available.
            }
        }
        if (settings.getBool("ignoreBots", false) && record.bot) return false;
        if (readIds("ignoredUsers").contains(String.valueOf(record.authorId))) return false;

        Long guildId = effectiveGuildId(record);
        boolean dm = guildId == null || guildId == 0L;
        if (dm) {
            if (contains("blackDms", record.channelId) || (!readIds("whiteDms").isEmpty()
                    && !contains("whiteDms", record.channelId))) return false;
        } else {
            if (contains("blackChannels", record.channelId) || (!readIds("whiteChannels").isEmpty()
                    && !contains("whiteChannels", record.channelId))) return false;
            if (contains("blackServers", guildId) || (!readIds("whiteServers").isEmpty()
                    && !contains("whiteServers", guildId))) return false;
        }
        return true;
    }

    private Long effectiveGuildId(MessageRecord record) {
        return resolveGuildId(record.channelId, record.guildId);
    }

    private Long resolveGuildId(long channelId, Long guildId) {
        if (guildId != null && guildId != 0L) return guildId;
        try {
            com.discord.api.channel.Channel channel = com.discord.stores.StoreStream.getChannels().getChannel(channelId);
            if (channel != null && channel.i() != 0L) return channel.i();
        } catch (Throwable ignored) {
            // Local messages can be observed while the channel store is still initializing.
        }
        return guildId;
    }

    private boolean contains(String key, long id) {
        return readIds(key).contains(String.valueOf(id));
    }

    private Set<String> readIds(String key) {
        Set<String> result = new HashSet<>();
        String raw = settings.getString(key, "");
        if (raw != null) for (String value : raw.split(",")) if (!value.trim().isEmpty()) result.add(value.trim());
        return result;
    }

    private boolean isDeleted(long id) {
        MessageRecord record = records.get(id);
        return deletedMessageIds.contains(id) && record != null && shouldKeep(record);
    }

    private void removeRecord(long id) {
        records.remove(id);
        transientRecordIds.remove(id);
        liveMessages.remove(id);
        deletedMessageIds.remove(id);
        recentlyDeletedMessageIds.remove(id);
        if (databaseEnabled && database != null) database.removeAsync(id);
    }

    private void storeRecord(MessageRecord record) {
        records.put(record.id, record);
        if (record.deleted || !record.edits.isEmpty()) {
            transientRecordIds.remove(record.id);
            return;
        }
        transientRecordIds.remove(record.id);
        transientRecordIds.addLast(record.id);
        while (transientRecordIds.size() > MAX_TRANSIENT_RECORDS) {
            Long oldestId = transientRecordIds.pollFirst();
            if (oldestId == null) break;
            MessageRecord oldest = records.get(oldestId);
            if (oldest != null && !oldest.deleted && oldest.edits.isEmpty()) records.remove(oldestId, oldest);
        }
    }
    private void persist(MessageRecord record) {
        if (databaseEnabled && database != null) database.upsertAsync(record);
    }

    private void openDatabase() {
        database = new MessageLoggerDatabase(new File(Constants.BASE_PATH, DB_NAME));
        database.open();
        database.loadAllAsync(loaded -> {
            for (MessageRecord record : loaded) {
                if (!shouldKeep(record)) {
                    database.removeAsync(record.id);
                    records.remove(record.id);
                    transientRecordIds.remove(record.id);
                    deletedMessageIds.remove(record.id);
                    continue;
                }
                if (record.deleted) deletedMessageIds.add(record.id);
                MessageRecord existing = records.get(record.id);
                if (existing == null) {
                    storeRecord(record);
                } else {
                    if (record.deleted) {
                        existing.deleted = true;
                        existing.deletedTimestamp = record.deletedTimestamp;
                    }
                    if (existing.edits.isEmpty()) existing.edits = record.edits;
                    if (existing.editedTimestamp == null) existing.editedTimestamp = record.editedTimestamp;
                    storeRecord(existing);
                }
            }
            bumpRevision();
        });
    }
    void setDatabaseEnabled(boolean enabled) {
        if (databaseEnabled == enabled) return;
        databaseEnabled = enabled;
        if (enabled) {
            openDatabase();
            for (MessageRecord record : records.values()) persist(record);
        } else if (database != null) {
            database.flushAndClose();
            database = null;
        }
        bumpRevision();
    }

    void settingsChanged() {
        for (MessageRecord record : new ArrayList<>(records.values())) {
            if (!shouldKeep(record)) removeRecord(record.id);
            else persist(record);
        }
        bumpRevision();
    }

    void clearDatabase() {
        records.clear();
        liveMessages.clear();
        deletedMessageIds.clear();
        recentlyDeletedMessageIds.clear();
        loadedRanges.clear();
        if (database != null) database.clearAsync();
        bumpRevision();
        Utils.showToast("BetterMessageLogger database cleared");
    }

    void exportDatabase() {
        if (database == null || !databaseEnabled) {
            Utils.showToast("Enable the database first");
            return;
        }
        File output = new File(Constants.BASE_PATH, TXT_NAME);
        database.exportAsync(output, () -> Utils.showToast("Exported to " + output.getName()));
    }

    private void bumpRevision() {
        revision.onNext(revisionNumber.incrementAndGet());
    }

    private void refreshVisibleDeletedTags() {
        List<Map.Entry<WidgetChatListAdapterItemMessage, Long>> bound;
        synchronized (boundMessageItems) {
            bound = new ArrayList<>(boundMessageItems.entrySet());
        }
        for (Map.Entry<WidgetChatListAdapterItemMessage, Long> entry : bound) {
            scheduleDeletedLabel(entry.getKey(), entry.getValue());
        }
    }
    private void scheduleDeletedLabel(WidgetChatListAdapterItemMessage item, long messageId) {
        item.itemView.post(() -> {
            // A RecyclerView item can be rebound before this callback runs.
            Long boundId = boundMessageItems.get(item);
            if (boundId == null || boundId != messageId) return;
            applyDeletedLabel(item.itemView, isDeleted(messageId));
        });
    }

    private void applyDeletedLabel(View root, boolean deleted) {
        int textId = Utils.getResId("chat_list_adapter_item_text", "id");
        View view = textId == 0 ? null : root.findViewById(textId);
        if (!(view instanceof TextView)) return;
        TextView textView = (TextView) view;

        // TextView.getText() is a copied SpannableString on Discord 126021. The real
        // DraweeSpanStringBuilder, which owns the emoji image spans, is kept separately
        // by SimpleDraweeSpanTextView, so mutate that builder instead of the copy.
        com.facebook.drawee.span.DraweeSpanStringBuilder nativeBuilder = getNativeTextBuilder(textView);
        if (nativeBuilder != null) {
            applyDeletedLabel(textView, nativeBuilder, deleted);
            return;
        }

        // Never replace a SimpleDraweeSpanTextView with a plain spannable: doing so drops
        // the builder that keeps Discord's emoji drawables attached to the view.
        if (textView instanceof com.discord.utilities.view.text.SimpleDraweeSpanTextView) return;

        CharSequence current = textView.getText();
        if (current == null) current = "";
        applyDeletedLabel(textView, current instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) current : new SpannableStringBuilder(current), deleted);
    }

    private void applyDeletedLabel(TextView textView, SpannableStringBuilder builder, boolean deleted) {

        DeletedLabelSpan[] oldLabels = builder.getSpans(0, builder.length(), DeletedLabelSpan.class);

        if (!deleted && oldLabels.length == 0) return;
        int labelColor = deletedLabelColor();
        if (deleted && oldLabels.length == 1) {
            int start = builder.getSpanStart(oldLabels[0]);
            int end = builder.getSpanEnd(oldLabels[0]);
            if (start == builder.length() - DELETED_LABEL.length() && end == builder.length()
                    && DELETED_LABEL.contentEquals(builder.subSequence(start, end))
                    && oldLabels[0].color == labelColor) return;
        }

        for (DeletedLabelSpan oldLabel : oldLabels) {
            int start = builder.getSpanStart(oldLabel);
            int end = builder.getSpanEnd(oldLabel);
            if (start >= 0 && end >= start) builder.delete(start, end);
            builder.removeSpan(oldLabel);
        }
        if (deleted) {
            int start = builder.length();
            builder.append(DELETED_LABEL);
            builder.setSpan(new DeletedLabelSpan(deletedLabelColor()), start, builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (builder instanceof com.facebook.drawee.span.DraweeSpanStringBuilder
                && textView instanceof com.discord.utilities.view.text.SimpleDraweeSpanTextView) {
            // Removing a stale label from a recycled row must not rebind the text view.
            // PluginDownloader stores its URL long-press callback in the rendered link
            // state, and rebinding here can make the link context menu lose that callback.
            if (!deleted) {
                textView.requestLayout();
                textView.invalidate();
                return;
            }
            int autoLinkMask = textView.getAutoLinkMask();
            textView.setAutoLinkMask(0);
            try {
                ((com.discord.utilities.view.text.SimpleDraweeSpanTextView) textView)
                        .setDraweeSpanStringBuilder((com.facebook.drawee.span.DraweeSpanStringBuilder) builder);
            } finally {
                textView.setAutoLinkMask(autoLinkMask);
            }
        } else {
            textView.setText(builder, TextView.BufferType.SPANNABLE);
        }
    }

    private com.facebook.drawee.span.DraweeSpanStringBuilder getNativeTextBuilder(TextView textView) {
        if (!(textView instanceof com.discord.utilities.view.text.SimpleDraweeSpanTextView)) return null;
        try {
            Field field = com.discord.utilities.view.text.SimpleDraweeSpanTextView.class
                    .getDeclaredField("mDraweeStringBuilder");
            field.setAccessible(true);
            Object value = field.get(textView);
            return value instanceof com.facebook.drawee.span.DraweeSpanStringBuilder
                    ? (com.facebook.drawee.span.DraweeSpanStringBuilder) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void addHistoryAction(WidgetChatListActions sheet, MessageRecord record) {
        try {
            ViewGroup rows = getActionRows(sheet);
            if (rows == null) return;
            if (rows.findViewWithTag("BetterMessageLogger.EditHistory") != null) return;
            TextView action = createNativeAction(sheet, "BetterMessageLogger.EditHistory", "View Edit History",
                    "ic_history_white_24dp", ignored -> showHistory(sheet, record));
            rows.addView(action, actionInsertionIndex(rows));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException error) {
            logger.error("Could not add edit history action", error);
        }
    }

    private void addDeleteAction(WidgetChatListActions sheet, MessageRecord record) {
        try {
            ViewGroup rows = getActionRows(sheet);
            if (rows == null || rows.findViewWithTag("BetterMessageLogger.DeleteLogged") != null) return;
            TextView action = createNativeAction(sheet, "BetterMessageLogger.DeleteLogged", "Delete Logged Message",
                    "ic_delete_24dp", ignored -> {
                deleteLoggedMessage(record.id);
                sheet.dismiss();
            });
            rows.addView(action, actionInsertionIndex(rows));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException error) {
            logger.error("Could not add logged message delete action", error);
        }
    }

    private int actionInsertionIndex(ViewGroup rows) {
        // The 126021 layout puts the reaction emoji RecyclerView first. Insert after
        // that exact child instead of relying on a hard-coded index from another build.
        int reactionId = Utils.getResId("dialog_chat_actions_add_reaction_emojis_list", "id");
        View reactions = reactionId == 0 ? null : rows.findViewById(reactionId);
        int reactionIndex = reactions == null ? -1 : rows.indexOfChild(reactions);
        return reactionIndex < 0 ? rows.getChildCount() : reactionIndex + 1;
    }

    private TextView createNativeAction(WidgetChatListActions sheet, String tag, String text, String iconName,
                                         View.OnClickListener listener)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Context actionContext = sheet.requireContext();
        ViewGroup rows = getActionRows(sheet);
        int styleId = Utils.getResId("UiKit_Settings_Item_Icon", "style");
        TextView action = styleId == 0 ? new TextView(actionContext)
                : new TextView(actionContext, null, 0, styleId);
        TextView template = rows == null ? null : rows.findViewById(Utils.getResId("dialog_chat_actions_edit", "id"));
        if (template != null) copyNativeActionAppearance(template, action);
        action.setTag(tag);
        action.setText(text);
        int iconId = Utils.getResId(iconName, "drawable");
        int tintAttribute = Utils.getResId("colorInteractiveNormal", "attr");
        int tint = tintAttribute == 0 ? Color.WHITE : ColorCompat.getThemedColor(actionContext, tintAttribute);
        Drawable icon = DrawableCompat.getDrawable(actionContext, iconId, tint);
        DrawableCompat.setCompoundDrawablesCompat(action, icon, null, null, null);
        if (template != null) {
            TextViewCompat.setCompoundDrawableTintList(action, TextViewCompat.getCompoundDrawableTintList(template));
        }
        action.setOnClickListener(listener);
        return action;
    }

    private void copyNativeActionAppearance(TextView template, TextView target) {
        target.setTextColor(template.getTextColors());
        target.setHintTextColor(template.getHintTextColors());
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, template.getTextSize());
        target.setTypeface(template.getTypeface());
        target.setGravity(template.getGravity());
        target.setIncludeFontPadding(template.getIncludeFontPadding());
        target.setCompoundDrawablePadding(template.getCompoundDrawablePadding());
        target.setPaddingRelative(template.getPaddingStart(), template.getPaddingTop(),
                template.getPaddingEnd(), template.getPaddingBottom());
        target.setMinHeight(template.getMinimumHeight());
        target.setMaxLines(template.getMaxLines());
        target.setEllipsize(template.getEllipsize());
        Drawable background = template.getBackground();
        if (background != null && background.getConstantState() != null) {
            target.setBackground(background.getConstantState().newDrawable(target.getResources()).mutate());
        }
        ViewGroup.LayoutParams params = template.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            target.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) params));
        } else if (params != null) {
            target.setLayoutParams(new ViewGroup.LayoutParams(params));
        }
    }

    private int deletedLabelColor() {
        try {
            return Color.parseColor(settings.getString(DELETED_LABEL_COLOR, DEFAULT_DELETED_LABEL_COLOR));
        } catch (Throwable ignored) {
            return Color.RED;
        }
    }

    void refreshDeletedLabels() {
        refreshVisibleDeletedTags();
    }

    private static final class DeletedLabelSpan extends CharacterStyle {
        private final int color;

        DeletedLabelSpan(int color) {
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setColor(color);
            paint.setTextSize(paint.getTextSize() * 0.75f);
        }
    }

    private ViewGroup getActionRows(WidgetChatListActions sheet)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method getter = WidgetChatListActions.class.getDeclaredMethod("getBinding");
        getter.setAccessible(true);
        Object binding = getter.invoke(sheet);
        Method rootGetter = binding.getClass().getMethod("getRoot");
        View root = (View) rootGetter.invoke(binding);
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup container = (ViewGroup) root;
        if (container.getChildCount() == 0 || !(container.getChildAt(0) instanceof ViewGroup)) return null;
        return (ViewGroup) container.getChildAt(0);
    }

    private void deleteLoggedMessage(long id) {
        removeRecord(id);
        refreshVisibleDeletedTags();
        bumpRevision();
        Utils.showToast("Logged message deleted");
    }
    private void showHistory(WidgetChatListActions sheet, MessageRecord record) {
        StringBuilder text = new StringBuilder();
        for (String edit : record.editEntries()) text.append(edit).append("\n\n");
        AlertDialog dialog = new AlertDialog.Builder(sheet.requireContext())
                .setTitle("Edit History")
                .setMessage(text.toString().trim())
                .setPositiveButton("Close", null)
                .create();
        dialog.setOnShowListener(ignored -> styleDialog(dialog));
        dialog.show();
    }

    private void styleDialog(AlertDialog dialog) {
        TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) title.setTextColor(Color.WHITE);
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(Color.WHITE);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) button.setTextColor(Color.WHITE);
        }
    }

    private static long editTime(com.discord.api.message.Message message) {
        UtcDateTime timestamp = message.j();
        return timestamp == null ? System.currentTimeMillis() : timestamp.g();
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        records.clear();
        transientRecordIds.clear();
        liveMessages.clear();
        boundMessages.clear();
        synchronized (boundMessageItems) {
            boundMessageItems.clear();
        }
        deletedMessageIds.clear();
        recentlyDeletedMessageIds.clear();
        loadedRanges.clear();
        if (database != null) database.stop();
        database = null;
        instance = null;
    }
    private static final class MessageCache {
        private final Map<Long, com.discord.models.message.Message> messages;

        MessageCache(final int maximumSize) {
            messages = new LinkedHashMap<Long, com.discord.models.message.Message>(maximumSize + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, com.discord.models.message.Message> eldest) {
                    return size() > maximumSize;
                }
            };
        }

        synchronized com.discord.models.message.Message get(long id) {
            return messages.get(id);
        }

        synchronized void put(long id, com.discord.models.message.Message message) {
            messages.put(id, message);
        }

        synchronized void remove(long id) {
            messages.remove(id);
        }

        synchronized void clear() {
            messages.clear();
        }
    }
    static final class MessageRecord {
        final long id;
        final long channelId;
        Long guildId;
        final long authorId;
        final String authorName;
        final String authorAvatar;
        final boolean bot;
        String content;
        final long timestamp;
        Long editedTimestamp;
        boolean deleted;
        Long deletedTimestamp;
        String edits;
        transient com.discord.models.message.Message runtime;

        MessageRecord(long id, long channelId, Long guildId, long authorId, String authorName, String authorAvatar, boolean bot,
                      String content, long timestamp, Long editedTimestamp, boolean deleted, Long deletedTimestamp,
                      String edits, com.discord.models.message.Message runtime) {
            this.id = id;
            this.channelId = channelId;
            this.guildId = guildId;
            this.authorId = authorId;
            this.authorName = authorName == null || authorName.isEmpty() ? "Unknown user" : authorName;
            this.authorAvatar = authorAvatar;
            this.bot = bot;
            this.content = content == null ? "" : content;
            this.timestamp = timestamp;
            this.editedTimestamp = editedTimestamp;
            this.deleted = deleted;
            this.deletedTimestamp = deletedTimestamp;
            this.edits = edits == null ? "" : edits;
            this.runtime = runtime;
        }

        void addEdit(String oldContent, long time) {
            if (!edits.isEmpty()) edits += "\u001e";
            edits += time + "\u001f" + oldContent.replace("\u001e", " ").replace("\u001f", " ");
            editedTimestamp = time;
        }

        List<String> editEntries() {
            List<String> result = new ArrayList<>();
            for (String entry : edits.split("\u001e")) {
                if (entry.isEmpty()) continue;
                String[] parts = entry.split("\u001f", 2);
                if (parts.length != 2) continue;
                try {
                    result.add(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                            .format(new Date(Long.parseLong(parts[0]))) + "\n" + parts[1]);
                } catch (NumberFormatException ignored) {
                    result.add(parts[1]);
                }
            }
            return result;
        }

        MessageRecord copyWithoutRuntime() {
            return new MessageRecord(id, channelId, guildId, authorId, authorName, authorAvatar, bot, content, timestamp,
                    editedTimestamp, deleted, deletedTimestamp, edits, null);
        }

        com.discord.models.message.Message toMessage() {
            if (runtime != null) return runtime;
            com.discord.api.user.User user = new com.discord.api.user.User(
                    authorId, authorName,
                    authorAvatar == null ? null : new com.discord.nullserializable.NullSerializable.b<>(authorAvatar),
                    null, "0000", null, null, bot, false,
                    null, null, null, null, null, null, null, null, PremiumTier.NONE, null, null, null, null, 0);
            return new com.discord.models.message.Message(
                    id, channelId, guildId, user, content,
                    new UtcDateTime(timestamp),
                    editedTimestamp == null ? null : new UtcDateTime(editedTimestamp),
                    false, false,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    null, false, null, Integer.valueOf(0),
                    null, null, null, null, null,
                    Collections.emptyList(), Collections.emptyList(),
                    null, null, null, Collections.emptyList(), null,
                    false, null, false, null, null, null, null, null, null);
        }
    }

    private static final class LoadedRange {
        private long oldest = Long.MAX_VALUE;
        private long newest = Long.MIN_VALUE;

        synchronized void include(long oldestId, long newestId) {
            oldest = Math.min(oldest, oldestId);
            newest = Math.max(newest, newestId);
        }

        synchronized long oldest() {
            return oldest;
        }

        synchronized long newest() {
            return newest;
        }
    }

    private static final class StoreStreamAccess {
        static long meId() {
            return com.discord.stores.StoreStream.getUsers().getMe().getId();
        }
    }
}
