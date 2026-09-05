package com.github.yutaplug.superreactions;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.core.widget.TextViewCompat;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.GsonUtils;
import com.discord.api.message.reaction.MessageReaction;
import com.discord.api.message.reaction.MessageReactionEmoji;
import com.discord.api.message.reaction.MessageReactionUpdate;
import com.discord.models.domain.emoji.Emoji;
import com.discord.models.member.GuildMember;
import com.discord.models.user.CoreUser;
import com.discord.models.user.User;
import com.discord.stores.StoreMessageReactions;
import com.discord.stores.StoreMessages;
import com.discord.stores.StoreStream;
import com.discord.utilities.user.UserUtils;
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType;
import com.discord.widgets.chat.input.emoji.EmojiPickerListener;
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator;
import com.discord.widgets.chat.list.actions.WidgetChatListActions;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemReactions;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler;
import com.discord.widgets.chat.list.entries.ChatListEntry;
import com.discord.widgets.chat.list.entries.ReactionsEntry;
import com.discord.widgets.chat.managereactions.ManageReactionsEmojisAdapter;
import com.discord.widgets.chat.managereactions.ManageReactionsModel;
import com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter;
import com.discord.widgets.chat.managereactions.WidgetManageReactions;
import com.discord.views.ReactionView;
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload;
import com.discord.api.premium.PremiumTier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
@AliucordPlugin
public class SuperReactions extends Plugin {
    private static final String CHANNEL_ID_ARGUMENT = "INTENT_EXTRA_MESSAGE_CHANNEL_ID";
    private static final String MESSAGE_ID_ARGUMENT = "INTENT_EXTRA_MESSAGE_ID";
    private static final String ADD_REACTION_LIST_ID = "dialog_chat_actions_add_reaction_emojis_list";
    private static final String ACTIONS_CONTAINER_ID = "dialog_chat_actions_container";
    private static final String MANAGE_REACTIONS_ID = "dialog_chat_actions_manage_reactions";
    private static final String MANAGE_REACTIONS_EMOJI_ARGUMENT = "com.discord.intent.extra.EXTRA_EMOJI_KEY";
    private static final long SUPER_REACTION_CACHE_TTL = 5 * 60 * 1000L;
    private static final long BURST_CHECK_TTL = 30 * 1000L;
    private static final long LOCAL_REMOVAL_FALLBACK_DELAY_MS = 2 * 1000L;
    private static final long LOCAL_REMOVAL_MARKER_TTL_MS = 10 * 1000L;
    private static final String OWNED_REACTIONS_PREFERENCES = "owned_super_reactions_";
    private static final int SUPER_REACTION_COLOR = Color.rgb(255, 196, 61);

    private final Map<WidgetChatListActions, TextView> superReactionButtons = new WeakHashMap<>();
    private final Map<WidgetChatListAdapterItemReactions, Long> visibleReactionItems = new WeakHashMap<>();
    private final Map<WidgetChatListAdapterItemReactions, ReactionsEntry> visibleReactionEntries = new WeakHashMap<>();
    private final Map<WidgetChatListAdapterItemReactions, Integer> visibleReactionPositions = new WeakHashMap<>();
    private final Map<ReactionView, Drawable> originalReactionBackgrounds = new WeakHashMap<>();
    private final Map<WidgetManageReactions, ManageReactionTarget> activeManageReactions = new WeakHashMap<>();
    private final Map<ManageReactionsEmojisAdapter, WidgetManageReactions>
            activeManageEmojiAdapters = new WeakHashMap<>();
    private final Map<WidgetManageReactions, Boolean> manageReactionWidgetTypes = new WeakHashMap<>();
    private final Map<Long, Long> reactionChannels = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Integer>> superReactionCounts = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Integer>> normalReactionCounts = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Integer>> superReactionColors = new ConcurrentHashMap<>();
    private final Map<Long, List<Boolean>> reactionDisplayTypes = new ConcurrentHashMap<>();
    private final Map<Long, IdentityHashMap<MessageReaction, Boolean>> expandedReactionTypes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> manageReactionTypes = new ConcurrentHashMap<>();
    private final IdentityHashMap<ManageReactionsEmojisAdapter.ReactionEmojiItem, Boolean>
            manageReactionItemTypes = new IdentityHashMap<>();
    private final Map<Long, Long> superReactionFetchTimes = new ConcurrentHashMap<>();
    private final Set<Long> superReactionFetches = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> burstCheckTimes = new ConcurrentHashMap<>();
    private final Set<String> burstChecks = ConcurrentHashMap.newKeySet();
    private final Set<String> knownNormalReactions = ConcurrentHashMap.newKeySet();
    private final Set<String> locallySentSuperReactions = ConcurrentHashMap.newKeySet();
    private final Set<String> ownedSuperReactions = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingSuperReactionRemovals = ConcurrentHashMap.newKeySet();
    private final Set<String> completedSuperReactionRemovals = ConcurrentHashMap.newKeySet();
    private final Map<String, List<User>> burstReactionUsers = new ConcurrentHashMap<>();
    private final Map<String, List<MGRecyclerDataPayload>> normalReactionItems = new ConcurrentHashMap<>();
    private final Set<String> burstUserFetches = ConcurrentHashMap.newKeySet();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences ownedReactionPreferences;
    private String ownedReactionPreferencesKey;

    @Override
    public void start(android.content.Context context) throws Throwable {
        ownedSuperReactions.clear();
        long currentUserId = getCurrentUserId();
        ownedReactionPreferences = context.getSharedPreferences("SuperReactions", android.content.Context.MODE_PRIVATE);
        ownedReactionPreferencesKey = OWNED_REACTIONS_PREFERENCES
                + (currentUserId == 0L ? "unknown" : currentUserId);
        Set<String> savedOwnedReactions = ownedReactionPreferences.getStringSet(
                ownedReactionPreferencesKey, null);
        if (savedOwnedReactions != null) ownedSuperReactions.addAll(savedOwnedReactions);

        patcher.patch(
                WidgetChatListActions.class,
                "onViewCreated",
                new Class<?>[]{View.class, Bundle.class},
                new Hook(frame -> {
                    WidgetChatListActions actions = (WidgetChatListActions) frame.thisObject;
                    View root = (View) frame.args[0];
                    addSuperReactionButton(actions, root);
                })
        );

        patcher.patch(
                WidgetChatListActions.class,
                "configureUI",
                new Class<?>[]{WidgetChatListActions.Model.class},
                new Hook(frame -> {
                    WidgetChatListActions actions = (WidgetChatListActions) frame.thisObject;
                    WidgetChatListActions.Model model = (WidgetChatListActions.Model) frame.args[0];
                    TextView button = superReactionButtons.get(actions);
                    if (button == null || model == null) return;

                    boolean canAddReactions = model.getManageMessageContext().getCanAddReactions();
                    boolean usableMessage = !model.getMessage().isLocal();
                    button.setVisibility(canAddReactions && usableMessage && hasNitro()
                            ? View.VISIBLE : View.GONE);
                })
        );

        patcher.patch(
                WidgetChatListAdapterItemReactions.class,
                "displayReactions",
                new Class<?>[]{Collection.class, long.class, boolean.class, boolean.class, boolean.class},
                new PreHook(frame -> {
                    long messageId = (Long) frame.args[1];
                    List<MessageReaction> expandedReactions = expandReactions(
                            (Collection<?>) frame.args[0], messageId);
                    frame.args[0] = expandedReactions;
                })
        );

        patcher.patch(
                WidgetManageReactions.Companion.class,
                "create",
                new Class<?>[]{long.class, long.class, android.content.Context.class, MessageReaction.class},
                new Hook(frame -> {
                    long channelId = (Long) frame.args[0];
                    long messageId = (Long) frame.args[1];
                    MessageReaction reaction = (MessageReaction) frame.args[3];
                    if (reaction == null || reaction.b() == null) return;

                    Boolean type = getExpandedReactionType(messageId, reaction);
                    if (type != null) {
                        manageReactionTypes.put(
                                manageReactionKey(channelId, messageId, reaction.b().c()), type);
                    }
                })
        );

        patcher.patch(
                ManageReactionsEmojisAdapter.ReactionEmojiItem.class,
                "getKey",
                new Class<?>[]{},
                new Hook(frame -> {
                    ManageReactionsEmojisAdapter.ReactionEmojiItem item =
                            (ManageReactionsEmojisAdapter.ReactionEmojiItem) frame.thisObject;
                    Boolean type = getManageReactionItemType(item);
                    if (type == null || item.getReaction() == null || item.getReaction().b() == null) {
                        return;
                    }
                    frame.setResult(item.getReaction().b().c()
                            + (type ? ":super" : ":normal"));
                })
        );

        patcher.patch(
                ManageReactionsEmojisAdapter.ReactionEmojiViewHolder.class,
                "onConfigure",
                new Class<?>[]{int.class, ManageReactionsEmojisAdapter.ReactionEmojiItem.class},
                new Hook(frame -> {
                    ManageReactionsEmojisAdapter.ReactionEmojiViewHolder holder =
                            (ManageReactionsEmojisAdapter.ReactionEmojiViewHolder) frame.thisObject;
                    ManageReactionsEmojisAdapter.ReactionEmojiItem item =
                            (ManageReactionsEmojisAdapter.ReactionEmojiItem) frame.args[1];
                    Boolean type = getManageReactionItemType(item);
                    if (type == null || item == null || item.getReaction() == null
                            || item.getReaction().b() == null) return;

                    ManageReactionsEmojisAdapter adapter =
                            ManageReactionsEmojisAdapter.ReactionEmojiViewHolder
                                    .access$getAdapter$p(holder);
                    WidgetManageReactions widget = getManageReactionWidget(adapter);
                    Function1<String, Unit> listener = adapter.getOnEmojiSelectedListener();
                    if (widget == null || listener == null) return;

                    Intent intent = widget.getMostRecentIntent();
                    long channelId = intent.getLongExtra(
                            "com.discord.intent.extra.EXTRA_CHANNEL_ID", 0L);
                    long messageId = intent.getLongExtra(
                            "com.discord.intent.extra.EXTRA_MESSAGE_ID", 0L);
                    String reactionKey = item.getReaction().b().c();
                    String selectionKey = manageReactionKey(channelId, messageId, reactionKey);
                    holder.itemView.setOnClickListener(ignored -> {
                        synchronized (manageReactionWidgetTypes) {
                            manageReactionWidgetTypes.put(widget, type);
                        }
                        manageReactionTypes.put(selectionKey, type);
                        selectManageReaction(widget, channelId, messageId,
                                item.getReaction().b(), type);
                        listener.invoke(reactionKey);
                    });
                })
        );

        patcher.patch(
                WidgetManageReactions.class,
                "onViewBound",
                new Class<?>[]{View.class},
                new Hook(frame -> registerManageReactionAdapter(
                        (WidgetManageReactions) frame.thisObject))
        );

        patcher.patch(
                WidgetChatListAdapterItemReactions.class,
                "onConfigure",
                new Class<?>[]{int.class, ChatListEntry.class},
                new Hook(frame -> {
                    WidgetChatListAdapterItemReactions item =
                            (WidgetChatListAdapterItemReactions) frame.thisObject;
                    ReactionsEntry entry = (ReactionsEntry) frame.args[1];
                    if (entry == null || entry.getMessage() == null) return;

                    long channelId = entry.getMessage().getChannelId();
                    long messageId = entry.getMessage().getId();
                    synchronized (visibleReactionItems) {
                        visibleReactionItems.put(item, messageId);
                        visibleReactionEntries.put(item, entry);
                        visibleReactionPositions.put(item, (Integer) frame.args[0]);
                    }
                    reactionChannels.put(messageId, channelId);
                    applySuperReactionStyles(item, messageId);
                    fetchSuperReactionMetadata(channelId, messageId);
                })
        );

        patcher.patch(
                ReactionView.class,
                "a",
                new Class<?>[]{MessageReaction.class, long.class, boolean.class},
                new Hook(frame -> {
                    MessageReaction reaction = (MessageReaction) frame.args[0];
                    long messageId = (Long) frame.args[1];
                    if (reaction == null || reaction.b() == null) return;

                    String key = reaction.b().c();
                    Integer burstCount = getSuperReactionCount(messageId, key);
                    Boolean expandedType = getExpandedReactionType(messageId, reaction);
                    boolean isSuperReaction = expandedType != null
                            ? expandedType : burstCount != null && burstCount > 0;
                    ReactionView reactionView = (ReactionView) frame.thisObject;
                    setReactionMeState(reactionView, messageId, reaction, isSuperReaction);
                    styleReactionView(reactionView, messageId, key,
                            isSuperReaction, burstCount);
                    Long channelId = reactionChannels.get(messageId);
                    if (burstCount == null && channelId != null) {
                        checkBurstReaction(channelId, messageId, reaction.b());
                    }
                    reactionView.post(() -> {
                        MessageReaction currentReaction = reactionView.getReaction();
                        String currentKey = currentReaction == null || currentReaction.b() == null
                                ? null : currentReaction.b().c();
                        Integer currentBurstCount = getSuperReactionCount(messageId, currentKey);
                        Boolean currentExpandedType = getExpandedReactionType(messageId, currentReaction);
                        boolean currentIsSuperReaction = currentExpandedType != null
                                ? currentExpandedType
                                : currentBurstCount != null && currentBurstCount > 0;
                        setReactionMeState(reactionView, messageId, currentReaction,
                                currentIsSuperReaction);
                        styleReactionView(reactionView, messageId, currentKey,
                                currentIsSuperReaction,
                                currentBurstCount);
                    });
                })
        );

        patcher.patch(
                WidgetChatListAdapterEventsHandler.UserReactionHandler.class,
                "toggleReaction",
                new Class<?>[]{long.class, long.class, long.class, MessageReaction.class},
                new PreHook(frame -> {
                    MessageReaction reaction = (MessageReaction) frame.args[3];
                    long channelId = (Long) frame.args[1];
                    long messageId = (Long) frame.args[2];
                    Boolean expandedType = getExpandedReactionType(messageId, reaction);
                    boolean isSuper = expandedType != null
                            ? expandedType : reaction != null && reaction.b() != null
                            && isSuperReaction(messageId, reaction.b().c());
                    boolean isKnownNormal = reaction != null && reaction.b() != null
                            && isKnownNormalReaction(messageId, reaction.b().c());
                    if (reaction != null && reaction.b() != null
                            && expandedType == null && !isSuper && !isKnownNormal) {
                        // Metadata is asynchronous on a cold channel. Resolve
                        // the typed reaction first so the normal endpoint cannot
                        // consume a tap intended for an existing burst pill.
                        frame.setResult(null);
                        resolveReactionAndToggle(channelId, messageId, reaction);
                        return;
                    }
                    if (reaction != null && reaction.b() != null && isSuper) {
                        // The old client only knows the normal `me` bit and would
                        // call the normal reaction endpoint. Handle both sides of
                        // the burst toggle here: remove our burst or add one to a
                        // burst reaction made by another user.
                        frame.setResult(null);
                        if (isOwnSuperReaction(messageId, reaction.b().c())) {
                            removeSuperReaction(channelId, messageId, reaction.b());
                        } else {
                            sendSuperReaction(channelId, messageId, reaction.b());
                        }
                    }
                })
        );

        patcher.patch(
                StoreMessageReactions.class,
                "deleteEmoji",
                new Class<?>[]{long.class, long.class, MessageReactionEmoji.class, long.class},
                new PreHook(frame -> {
                    long channelId = (Long) frame.args[0];
                    long messageId = (Long) frame.args[1];
                    MessageReactionEmoji emoji = (MessageReactionEmoji) frame.args[2];
                    long userId = (Long) frame.args[3];
                    String reactionKey = emoji == null ? null : emoji.c();
                    Boolean selectedType = manageReactionTypes.get(
                            manageReactionKey(channelId, messageId, reactionKey));
                    boolean isSuper = selectedType != null
                            ? selectedType : isSuperReaction(messageId, reactionKey);
                    if (isSuper && userId == getCurrentUserId() && isOwnSuperReaction(messageId,
                            reactionKey)) {
                        frame.setResult(null);
                        removeSuperReaction(channelId, messageId, emoji);
                    }
                })
        );

        patcher.patch(
                StoreMessages.class,
                "handleReactionUpdate",
                new Class<?>[]{List.class, boolean.class},
                new PreHook(frame -> {
                    if (Boolean.TRUE.equals(frame.args[1])) return;
                    Object updates = frame.args[0];
                    if (!(updates instanceof List<?>)) return;

                    long eventUserId = getCurrentUserId();
                    if (eventUserId == 0L) return;

                    List<Object> rewrittenUpdates = new ArrayList<>();
                    boolean changed = false;
                    for (Object update : (List<?>) updates) {
                        if (!(update instanceof MessageReactionUpdate)) {
                            rewrittenUpdates.add(update);
                            continue;
                        }

                        MessageReactionUpdate reactionUpdate = (MessageReactionUpdate) update;
                        String reactionKey = reactionUpdate.b() == null
                                ? null : reactionUpdate.b().c();
                        boolean isCurrentUser = reactionUpdate.d() == eventUserId;
                        if (!isCurrentUser || reactionKey == null) {
                            rewrittenUpdates.add(update);
                            continue;
                        }

                        // The old message store ignores a remove event when its
                        // reaction has me=false. That is exactly how a burst
                        // reaction sent by the current user is represented by
                        // this client. Feed only the message store a synthetic
                        // non-self event so it decrements the aggregate count;
                        // StoreMessageReactions still receives the real event
                        // and removes the correct user from the member list.
                        if (isCompletedSuperReactionRemoval(
                                reactionUpdate.c(), reactionKey)) {
                            changed = true;
                            continue;
                        }
                        if (!isOwnSuperReaction(reactionUpdate.c(), reactionKey)
                                && !isPendingSuperReactionRemoval(
                                reactionUpdate.c(), reactionKey)) {
                            rewrittenUpdates.add(update);
                            continue;
                        }

                        completeSuperReactionRemoval(reactionUpdate.c(), reactionKey);
                        long syntheticUserId = eventUserId == 1L ? 2L : 1L;
                        rewrittenUpdates.add(new MessageReactionUpdate(
                                syntheticUserId,
                                reactionUpdate.a(),
                                reactionUpdate.c(),
                                reactionUpdate.b()));
                        changed = true;
                    }

                    if (changed) frame.args[0] = rewrittenUpdates;
                })
        );

        patcher.patch(
                StoreMessages.class,
                "handleReactionUpdate",
                new Class<?>[]{List.class, boolean.class},
                new Hook(frame -> {
                    Object updates = frame.args[0];
                    if (!(updates instanceof List<?>)) return;

                    for (Object update : (List<?>) updates) {
                        if (update instanceof MessageReactionUpdate) {
                            MessageReactionUpdate reactionUpdate = (MessageReactionUpdate) update;
                            boolean isAdd = Boolean.TRUE.equals(frame.args[1]);
                            invalidateSuperReactionCache(
                                    reactionUpdate.c(), reactionUpdate.a(),
                                    hasLocalSuperReaction(reactionUpdate.c()));
                            if (isAdd && reactionUpdate.b() != null) {
                                // A reaction view is not guaranteed to be
                                // rebound for a gateway count update. Probe
                                // the typed endpoint directly so an official
                                // client's new burst is styled immediately.
                                reactionChannels.put(
                                        reactionUpdate.c(), reactionUpdate.a());
                                checkBurstReaction(
                                        reactionUpdate.a(), reactionUpdate.c(), reactionUpdate.b());
                            }
                        }
                    }
                })
        );

        patcher.patch(
                WidgetManageReactions.class,
                "configureUI",
                new Class<?>[]{ManageReactionsModel.class},
                new PreHook(frame -> {
                    WidgetManageReactions widget = (WidgetManageReactions) frame.thisObject;
                    ManageReactionsModel model = (ManageReactionsModel) frame.args[0];
                    registerManageReactions(widget, model);

                    if (model == null) return;
                    ManageReactionTarget target = getManageReactionTarget(widget);
                    if (target == null) return;

                    List<ManageReactionsEmojisAdapter.ReactionEmojiItem> reactionItems =
                            expandManageReactionItems(model.getReactionItems(), target);
                    List<? extends MGRecyclerDataPayload> userItems = model.getUserItems();
                    List<MGRecyclerDataPayload> normalItems = new ArrayList<>();
                    normalItems.addAll(userItems);
                    normalReactionItems.put(normalCacheKey(target), normalItems);
                    if (target.superReaction) {
                        List<User> users = burstReactionUsers.get(target.cacheKey());
                        userItems = users == null
                                ? new ArrayList<MGRecyclerDataPayload>()
                                : createManageReactionItems(target, users);
                    }
                    frame.args[0] = new ManageReactionsModel(reactionItems, userItems);
                })
        );
    }

    private void addSuperReactionButton(WidgetChatListActions actions, View root) {
        if (superReactionButtons.containsKey(actions)) return;

        int containerId = Utils.getResId(ACTIONS_CONTAINER_ID, "id");
        int listId = Utils.getResId(ADD_REACTION_LIST_ID, "id");
        int manageReactionsId = Utils.getResId(MANAGE_REACTIONS_ID, "id");
        View containerView = root.findViewById(containerId);
        View listView = root.findViewById(listId);
        View manageReactionsView = root.findViewById(manageReactionsId);
        if (!(containerView instanceof LinearLayout) || listView == null || !(manageReactionsView instanceof TextView)) {
            return;
        }

        TextView template = (TextView) manageReactionsView;
        TextView button = new TextView(root.getContext());
        button.setText("Super React");
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setTextColor(template.getTextColors());
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.getTextSize());
        button.setTypeface(template.getTypeface());
        button.setIncludeFontPadding(template.getIncludeFontPadding());
        button.setCompoundDrawablePadding(template.getCompoundDrawablePadding());
        button.setPadding(
                template.getPaddingLeft(),
                template.getPaddingTop(),
                template.getPaddingRight(),
                template.getPaddingBottom()
        );

        Drawable[] templateDrawables = template.getCompoundDrawablesRelative();
        Drawable icon = cloneDrawable(templateDrawables[0]);
        if (icon != null) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            TextViewCompat.setCompoundDrawableTintList(
                    button, TextViewCompat.getCompoundDrawableTintList(template));
        }
        Drawable background = cloneDrawable(template.getBackground());
        if (background != null) button.setBackground(background);

        button.setContentDescription("Super React");
        button.setVisibility(View.GONE);
        button.setOnClickListener(ignored -> openSuperReactionPicker(actions));

        LinearLayout container = (LinearLayout) containerView;
        ViewGroup.LayoutParams sourceParams = template.getLayoutParams();
        ViewGroup.LayoutParams params = sourceParams == null
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(sourceParams);
        // Keep the quick-reaction row at the top and place this action after
        // the client's normal message actions.
        container.addView(button, params);
        superReactionButtons.put(actions, button);
    }

    private Drawable cloneDrawable(Drawable drawable) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? drawable.mutate() : state.newDrawable().mutate();
    }

    private Http.Request newDiscordV10Request(String route, String method) throws java.io.IOException {
        // Use Aliucord's authenticated RN request builder. It supplies the
        // legacy client's API version and all headers Discord expects.
        Http.Request request = Http.Request.newDiscordRNRequest(route, method);
        com.discord.utilities.rest.RestAPI.AppHeadersProvider headers =
                com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE;
        if (headers.getFingerprint() != null) {
            request.setHeader("X-Fingerprint", headers.getFingerprint());
        }
        return request;
    }

    private Http.Request newDiscordV10Request(String route) throws java.io.IOException {
        return newDiscordV10Request(route, "GET");
    }

    private boolean hasNitro() {
        try {
            return UserUtils.INSTANCE.isPremium(StoreStream.Companion.getUsers().getMe());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void registerManageReactions(WidgetManageReactions widget, ManageReactionsModel model) {
        if (model == null) {
            synchronized (activeManageReactions) {
                activeManageReactions.remove(widget);
            }
            return;
        }

        Intent intent = widget.getMostRecentIntent();
        long channelId = intent.getLongExtra("com.discord.intent.extra.EXTRA_CHANNEL_ID", 0L);
        long messageId = intent.getLongExtra("com.discord.intent.extra.EXTRA_MESSAGE_ID", 0L);
        if (channelId == 0L || messageId == 0L) return;
        registerManageReactionAdapter(widget);

        MessageReactionEmoji emoji = null;
        String reactionKey = null;
        Boolean selectedType = null;
        for (ManageReactionsEmojisAdapter.ReactionEmojiItem reactionItem : model.getReactionItems()) {
            MessageReaction reaction = reactionItem.getReaction();
            if (reactionItem.isSelected() && reaction != null && reaction.b() != null) {
                emoji = reaction.b();
                reactionKey = emoji.c();
                selectedType = getManageReactionItemType(reactionItem);
                break;
            }
        }
        // Keep the intent as a fallback for the first model emission, before
        // the selected indicator has been updated by the old client.
        if (reactionKey == null) {
            reactionKey = intent.getStringExtra(MANAGE_REACTIONS_EMOJI_ARGUMENT);
            for (ManageReactionsEmojisAdapter.ReactionEmojiItem reactionItem : model.getReactionItems()) {
                MessageReaction reaction = reactionItem.getReaction();
                if (reaction != null && reaction.b() != null
                        && reactionKey != null && reactionKey.equals(reaction.b().c())) {
                    emoji = reaction.b();
                    break;
                }
            }
        }
        if (emoji == null) return;

        if (reactionKey == null) return;
        if (selectedType == null) {
            synchronized (manageReactionWidgetTypes) {
                selectedType = manageReactionWidgetTypes.get(widget);
            }
        }
        if (selectedType == null) {
            selectedType = manageReactionTypes.get(
                    manageReactionKey(channelId, messageId, reactionKey));
        }
        boolean targetIsSuper = selectedType != null
                ? selectedType : isSuperReaction(messageId, reactionKey);
        ManageReactionTarget target = new ManageReactionTarget(
                channelId, messageId, reactionKey, emoji, targetIsSuper);
        synchronized (activeManageReactions) {
            activeManageReactions.put(widget, target);
        }

        if (targetIsSuper) {
            // The legacy model cannot distinguish burst reactions. Query the
            // typed endpoint only when the burst tab is actually selected.
            fetchBurstReactionUsers(target);
            fetchSuperReactionMetadata(channelId, messageId);
        } else {
            checkBurstReaction(channelId, messageId, emoji);
            fetchSuperReactionMetadata(channelId, messageId);
        }
    }

    private String normalCacheKey(ManageReactionTarget target) {
        return target.messageId + ":" + target.reactionKey + ":normal";
    }

    private void selectManageReaction(WidgetManageReactions widget, long channelId,
                                      long messageId, MessageReactionEmoji emoji,
                                      boolean isSuperReaction) {
        if (widget == null || emoji == null) return;
        String reactionKey = emoji.c();
        manageReactionTypes.put(
                manageReactionKey(channelId, messageId, reactionKey), isSuperReaction);
        synchronized (manageReactionWidgetTypes) {
            manageReactionWidgetTypes.put(widget, isSuperReaction);
        }

        ManageReactionTarget target = new ManageReactionTarget(
                channelId, messageId, reactionKey, emoji, isSuperReaction);
        synchronized (activeManageReactions) {
            activeManageReactions.put(widget, target);
        }
        refreshManageReactionTabs(widget, target);

        if (isSuperReaction) {
            setManageReactionResults(widget, new ArrayList<MGRecyclerDataPayload>());
            fetchBurstReactionUsers(target);
        } else {
            List<MGRecyclerDataPayload> users = normalReactionItems.get(normalCacheKey(target));
            setManageReactionResults(widget, users == null
                    ? new ArrayList<MGRecyclerDataPayload>()
                    : new ArrayList<>(users));
        }
    }

    private String manageReactionKey(long channelId, long messageId, String reactionKey) {
        return channelId + ":" + messageId + ":" + reactionKey;
    }

    private void registerManageReactionAdapter(WidgetManageReactions widget) {
        try {
            java.lang.reflect.Field field = WidgetManageReactions.class
                    .getDeclaredField("emojisAdapter");
            field.setAccessible(true);
            Object adapter = field.get(widget);
            if (adapter instanceof ManageReactionsEmojisAdapter) {
                synchronized (activeManageEmojiAdapters) {
                    activeManageEmojiAdapters.put(
                            (ManageReactionsEmojisAdapter) adapter, widget);
                }
            }
        } catch (Throwable ignored) {
            // The adapter is initialized before the first model emission on
            // supported clients. Keep this hook optional for old variants.
        }
    }

    private WidgetManageReactions getManageReactionWidget(ManageReactionsEmojisAdapter adapter) {
        synchronized (activeManageEmojiAdapters) {
            WidgetManageReactions widget = activeManageEmojiAdapters.get(adapter);
            if (widget != null) return widget;
        }
        synchronized (activeManageReactions) {
            for (WidgetManageReactions widget : activeManageReactions.keySet()) {
                try {
                    java.lang.reflect.Field field = WidgetManageReactions.class
                            .getDeclaredField("emojisAdapter");
                    field.setAccessible(true);
                    if (field.get(widget) == adapter) return widget;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Boolean getManageReactionItemType(
            ManageReactionsEmojisAdapter.ReactionEmojiItem item) {
        if (item == null) return null;
        synchronized (manageReactionItemTypes) {
            return manageReactionItemTypes.get(item);
        }
    }

    private void trackManageReactionItem(
            ManageReactionsEmojisAdapter.ReactionEmojiItem item, boolean isSuperReaction) {
        synchronized (manageReactionItemTypes) {
            manageReactionItemTypes.put(item, isSuperReaction);
        }
    }

    private List<ManageReactionsEmojisAdapter.ReactionEmojiItem> expandManageReactionItems(
            List<ManageReactionsEmojisAdapter.ReactionEmojiItem> source,
            ManageReactionTarget target) {
        Map<String, Boolean> selectedTypes = new HashMap<>();
        for (ManageReactionsEmojisAdapter.ReactionEmojiItem item : source) {
            if (item == null || !item.isSelected() || item.getReaction() == null
                    || item.getReaction().b() == null) continue;
            String key = item.getReaction().b().c();
            Boolean type;
            if (target.reactionKey.equals(key)) {
                // The provider only reports the shared emoji key. The active
                // target is authoritative when switching between duplicate
                // normal and burst tabs without a provider emission.
                type = target.superReaction;
            } else {
                type = getManageReactionItemType(item);
                if (type == null) {
                    type = manageReactionTypes.get(
                            manageReactionKey(target.channelId, target.messageId, key));
                }
            }
            selectedTypes.put(key, type != null && type);
        }
        if (!selectedTypes.containsKey(target.reactionKey)) {
            selectedTypes.put(target.reactionKey, target.superReaction);
        }

        synchronized (manageReactionItemTypes) {
            manageReactionItemTypes.clear();
        }

        List<ManageReactionsEmojisAdapter.ReactionEmojiItem> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (ManageReactionsEmojisAdapter.ReactionEmojiItem item : source) {
            if (item == null || item.getReaction() == null || item.getReaction().b() == null) {
                continue;
            }
            MessageReaction reaction = item.getReaction();
            MessageReactionEmoji emoji = reaction.b();
            String key = emoji.c();
            if (!seenKeys.add(key)) continue;

            Integer burstCount = getSuperReactionCount(target.messageId, key);
            Integer normalCount = getNormalReactionCount(target.messageId, key);
            if (normalCount == null && burstCount != null && burstCount > 0
                    && reaction.a() > burstCount) {
                normalCount = reaction.a() - burstCount;
            }
            if (target.superReaction && target.reactionKey.equals(key)
                    && (burstCount == null || burstCount <= 0)) {
                // The typed users request can finish before message metadata.
                // Keep the selected burst tab visible until the real count arrives.
                burstCount = 1;
            }

            boolean selected = selectedTypes.containsKey(key);
            boolean selectedSuper = selected && Boolean.TRUE.equals(selectedTypes.get(key));
            if (burstCount != null && burstCount > 0
                    && normalCount != null && normalCount > 0) {
                ManageReactionsEmojisAdapter.ReactionEmojiItem burstItem =
                        new ManageReactionsEmojisAdapter.ReactionEmojiItem(
                                new MessageReaction(burstCount, emoji, false), selectedSuper);
                ManageReactionsEmojisAdapter.ReactionEmojiItem normalItem =
                        new ManageReactionsEmojisAdapter.ReactionEmojiItem(
                                new MessageReaction(normalCount, emoji, reaction.c()),
                                selected && !selectedSuper);
                trackManageReactionItem(burstItem, true);
                trackManageReactionItem(normalItem, false);
                result.add(burstItem);
                result.add(normalItem);
            } else if (burstCount != null && burstCount > 0) {
                ManageReactionsEmojisAdapter.ReactionEmojiItem burstItem =
                        new ManageReactionsEmojisAdapter.ReactionEmojiItem(
                                new MessageReaction(burstCount, emoji, false), selectedSuper);
                trackManageReactionItem(burstItem, true);
                result.add(burstItem);
            } else {
                trackManageReactionItem(item, false);
                result.add(item);
            }
        }
        return result;
    }

    private boolean isSuperReaction(long messageId, String reactionKey) {
        return getSuperReactionCount(messageId, reactionKey) != null;
    }

    private boolean isKnownNormalReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return false;
        return knownNormalReactions.contains(localReactionKey(messageId, reactionKey))
                || knownNormalReactions.contains(
                localReactionKey(messageId, normalizeReactionKey(reactionKey)))
                || knownNormalReactions.contains(
                localReactionKey(messageId, displayReactionKey(reactionKey)))
                || knownNormalReactions.contains(localReactionKey(
                messageId, normalizeReactionKey(displayReactionKey(reactionKey))));
    }

    private void markKnownNormalReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        knownNormalReactions.add(localReactionKey(messageId, reactionKey));
        knownNormalReactions.add(localReactionKey(messageId, normalizeReactionKey(reactionKey)));
        knownNormalReactions.add(localReactionKey(messageId, displayReactionKey(reactionKey)));
        knownNormalReactions.add(localReactionKey(
                messageId, normalizeReactionKey(displayReactionKey(reactionKey))));
    }

    private void removeKnownNormalReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        knownNormalReactions.remove(localReactionKey(messageId, reactionKey));
        knownNormalReactions.remove(localReactionKey(
                messageId, normalizeReactionKey(reactionKey)));
        knownNormalReactions.remove(localReactionKey(
                messageId, displayReactionKey(reactionKey)));
        knownNormalReactions.remove(localReactionKey(
                messageId, normalizeReactionKey(displayReactionKey(reactionKey))));
    }

    private void checkBurstReaction(long channelId, long messageId, MessageReactionEmoji emoji) {
        if (channelId == 0L || messageId == 0L || emoji == null) return;
        String reactionKey = emoji.c();
        String apiKey = getReactionApiKey(emoji);
        if (reactionKey == null || apiKey == null) return;

        String checkKey = messageId + ":" + apiKey;
        long now = System.currentTimeMillis();
        Long lastCheck = burstCheckTimes.get(checkKey);
        if (lastCheck != null && now - lastCheck < BURST_CHECK_TTL) return;
        if (!burstChecks.add(checkKey)) return;
        burstCheckTimes.put(checkKey, now);

        new Thread(() -> {
            try (Http.Request request = newDiscordV10Request(
                    "/channels/" + channelId + "/messages/" + messageId
                            + "/reactions/" + Uri.encode(apiKey) + "?limit=100&type=1")) {
                Http.Response response = request.execute();
                if (!response.ok()) {
                    throw new IllegalStateException("HTTP " + response.statusCode
                            + " " + response.statusMessage);
                }
                List<?> users = GsonUtils.fromJson(response.text(), List.class);
                if (users != null && !users.isEmpty()) {
                    long currentUserId = getCurrentUserId();
                    if (currentUserId != 0L) {
                        for (Object rawUser : users) {
                            if (rawUser instanceof Map<?, ?>) {
                                Long userId = longValue(((Map<?, ?>) rawUser).get("id"));
                                if (userId != null && currentUserId == userId) {
                                    markOwnedSuperReaction(messageId, reactionKey);
                                    break;
                                }
                            }
                        }
                    }
                    Map<String, Integer> counts = superReactionCounts.computeIfAbsent(
                            messageId, ignored -> new ConcurrentHashMap<>());
                    counts.put(reactionKey, 1);
                    counts.put(normalizeReactionKey(reactionKey), 1);
                    // The visible reaction may have been expanded as a normal
                    // reaction before the typed endpoint completed. Drop that
                    // per-view decision so the current burst count can win
                    // without waiting for the row to be recycled by scrolling.
                    clearExpandedReactionTypes(messageId);
                    mainHandler.post(() -> {
                        refreshSuperReactionStyles(messageId);
                        refreshManageReactionUsers(messageId);
                    });
                } else {
                    markKnownNormalReaction(messageId, reactionKey);
                }
            } catch (Throwable error) {
                burstCheckTimes.remove(checkKey);
            } finally {
                burstChecks.remove(checkKey);
            }
        }, "SuperReactionsBurstCheck").start();
    }

    private void resolveReactionAndToggle(long channelId, long messageId,
                                          MessageReaction reaction) {
        new Thread(() -> {
            MessageReactionEmoji emoji = reaction == null ? null : reaction.b();
            if (channelId == 0L || messageId == 0L || emoji == null) return;
            String reactionKey = emoji.c();
            String apiKey = getReactionApiKey(emoji);
            if (reactionKey == null || apiKey == null) return;

            List<User> burstUsers = null;
            try (Http.Request request = newDiscordV10Request(
                    "/channels/" + channelId + "/messages/" + messageId
                            + "/reactions/" + Uri.encode(apiKey) + "?limit=100&type=1")) {
                Http.Response response = request.execute();
                if (response.ok()) burstUsers = parseBurstReactionUsers(response.text());
            } catch (Throwable ignored) {
                // Fall back to the normal reaction endpoint below.
            }

            if (burstUsers != null && !burstUsers.isEmpty()) {
                ManageReactionTarget target = new ManageReactionTarget(
                        channelId, messageId, reactionKey, emoji, true);
                burstReactionUsers.put(target.cacheKey(), burstUsers);
                Map<String, Integer> counts = superReactionCounts.computeIfAbsent(
                        messageId, ignored -> new ConcurrentHashMap<>());
                counts.put(reactionKey, Math.max(1, burstUsers.size()));
                counts.put(normalizeReactionKey(reactionKey), Math.max(1, burstUsers.size()));
                for (User user : burstUsers) {
                    if (user.getId() == getCurrentUserId()) {
                        markOwnedSuperReaction(messageId, reactionKey);
                        break;
                    }
                }
                removeKnownNormalReaction(messageId, reactionKey);
                mainHandler.post(() -> refreshSuperReactionStyles(messageId));
                if (hasNitro() && isOwnSuperReaction(messageId, reactionKey)) {
                    removeSuperReaction(channelId, messageId, emoji);
                } else if (hasNitro()) {
                    sendSuperReaction(channelId, messageId, emoji);
                } else {
                    sendNormalReaction(channelId, messageId, emoji, reaction.c());
                }
                return;
            }

            markKnownNormalReaction(messageId, reactionKey);
            sendNormalReaction(channelId, messageId, emoji, reaction.c());
        }, "SuperReactionsResolve").start();
    }

    private void sendNormalReaction(long channelId, long messageId,
                                    MessageReactionEmoji emoji, boolean remove) {
        if (channelId == 0L || messageId == 0L || emoji == null) return;
        String apiKey = getReactionApiKey(emoji);
        if (apiKey == null || apiKey.isEmpty()) return;
        new Thread(() -> {
            try (Http.Request request = newDiscordV10Request(
                    "/channels/" + channelId + "/messages/" + messageId
                            + "/reactions/" + Uri.encode(apiKey) + "/@me",
                    remove ? "DELETE" : "PUT")) {
                Http.Response response = request.execute();
                if (!response.ok()) {
                    throw new IllegalStateException("HTTP " + response.statusCode
                            + " " + response.statusMessage);
                }
            } catch (Throwable error) {
                logger.error("Could not update normal Reaction", error);
            }
        }, "SuperReactionsNormal").start();
    }

    private void fetchBurstReactionUsers(ManageReactionTarget target) {
        String cacheKey = target.cacheKey();
        List<User> cachedUsers = burstReactionUsers.get(cacheKey);
        if (cachedUsers != null) {
            if (!cachedUsers.isEmpty()) updateManageReactionResults(target, cachedUsers);
            return;
        }
        if (!burstUserFetches.add(cacheKey)) return;

        new Thread(() -> {
            try (Http.Request request = newDiscordV10Request(
                    "/channels/" + target.channelId
                            + "/messages/" + target.messageId
                            + "/reactions/" + Uri.encode(getReactionApiKey(target.emoji))
                            + "?limit=100&type=1")) {
                Http.Response response = request.execute();
                if (!response.ok()) {
                    throw new IllegalStateException("HTTP " + response.statusCode
                            + " " + response.statusMessage);
                }

                List<User> users = parseBurstReactionUsers(response.text());
                long currentUserId = getCurrentUserId();
                for (User user : users) {
                    if (currentUserId != 0L && user.getId() == currentUserId) {
                        markOwnedSuperReaction(target.messageId, target.reactionKey);
                        break;
                    }
                }
                burstReactionUsers.put(cacheKey, users);
                if (!users.isEmpty()) {
                    mainHandler.post(() -> updateManageReactionResults(target, users));
                }
            } catch (Throwable error) {
                logger.error("Could not load Super Reaction users", error);
            } finally {
                burstUserFetches.remove(cacheKey);
            }
        }, "SuperReactionsUsers").start();
    }

    private String getReactionApiKey(MessageReactionEmoji emoji) {
        if (emoji.e() && emoji.b() != null) {
            return (emoji.d() == null ? "" : emoji.d()) + ":" + emoji.b();
        }
        return emoji.d() == null ? emoji.c() : emoji.d();
    }

    private List<User> parseBurstReactionUsers(String body) {
        List<User> result = new ArrayList<>();
        List<?> rawUsers = GsonUtils.fromJson(body, List.class);
        if (rawUsers == null) return result;

        for (Object rawUser : rawUsers) {
            if (!(rawUser instanceof Map<?, ?>)) continue;
            User user = createCoreUser((Map<?, ?>) rawUser);
            if (user != null) result.add(user);
        }
        return result;
    }

    private User createCoreUser(Map<?, ?> rawUser) {
        Long id = longValue(rawUser.get("id"));
        if (id == null) return null;

        String username = stringValue(rawUser.get("username"));
        if (username == null || username.isEmpty()) username = "Unknown user";
        return new CoreUser(
                id,
                username,
                stringValue(rawUser.get("avatar")),
                stringValue(rawUser.get("banner")),
                Boolean.TRUE.equals(rawUser.get("bot")),
                Boolean.TRUE.equals(rawUser.get("system")),
                intValue(rawUser.get("discriminator")),
                PremiumTier.NONE,
                intValue(rawUser.get("flags")),
                intValue(rawUser.get("public_flags")),
                stringValue(rawUser.get("bio")),
                stringValue(rawUser.get("banner_color"))
        );
    }

    private Long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void updateManageReactionResults(ManageReactionTarget target, List<User> users) {
        synchronized (activeManageReactions) {
            for (Map.Entry<WidgetManageReactions, ManageReactionTarget> entry : activeManageReactions.entrySet()) {
                if (!target.equals(entry.getValue())) continue;

                setManageReactionResults(entry.getKey(), createManageReactionItems(target, users));
            }
        }
    }

    private List<MGRecyclerDataPayload> createManageReactionItems(
            ManageReactionTarget target, List<User> users) {
        List<MGRecyclerDataPayload> items = new ArrayList<>();
        for (User user : users) {
            boolean canDelete = StoreStream.Companion.getUsers().getMe().getId() == user.getId();
            items.add(new ManageReactionsResultsAdapter.ReactionUserItem(
                    user,
                    target.channelId,
                    target.messageId,
                    target.emoji,
                    canDelete,
                    (GuildMember) null
            ));
        }
        return items;
    }

    private ManageReactionTarget getManageReactionTarget(WidgetManageReactions widget) {
        synchronized (activeManageReactions) {
            return activeManageReactions.get(widget);
        }
    }

    private void setManageReactionResults(WidgetManageReactions widget, List<MGRecyclerDataPayload> items) {
        try {
            java.lang.reflect.Field field = WidgetManageReactions.class.getDeclaredField("resultsAdapter");
            field.setAccessible(true);
            Object adapter = field.get(widget);
            if (adapter instanceof ManageReactionsResultsAdapter) {
                ((ManageReactionsResultsAdapter) adapter).setData(items);
            }
        } catch (Throwable error) {
            logger.error("Could not update Super Reaction users", error);
        }
    }

    private void fetchSuperReactionMetadata(long channelId, long messageId) {
        if (channelId == 0L || messageId == 0L) return;

        long now = System.currentTimeMillis();
        Long lastFetch = superReactionFetchTimes.get(messageId);
        if (lastFetch != null && now - lastFetch < SUPER_REACTION_CACHE_TTL) return;
        if (!superReactionFetches.add(messageId)) return;
        superReactionFetchTimes.put(messageId, now);

        new Thread(() -> {
            // The old client is rejected by Discord's single-message endpoint
            // even when its authenticated channel-message request is allowed.
            // Fetch the message through the normal list route instead; the
            // response contains the same count_details/me_burst fields.
            try (Http.Request request = newDiscordV10Request(
                    "/channels/" + channelId + "/messages?around=" + messageId + "&limit=1")) {
                Http.Response response = request.execute();
                if (!response.ok()) {
                    throw new IllegalStateException("HTTP " + response.statusCode
                            + " " + response.statusMessage);
                }

                String messageBody = extractMessageFromList(response.text(), messageId);
                if (messageBody == null) {
                    throw new IllegalStateException("Discord did not return the requested message");
                }
                Map<String, Integer> loadedCounts = loadSuperReactionCounts(
                        channelId, messageId, messageBody);
                superReactionCounts.compute(messageId, (ignored, currentCounts) -> {
                    Map<String, Integer> mergedCounts = new ConcurrentHashMap<>();
                    if (currentCounts != null) mergedCounts.putAll(currentCounts);
                    mergedCounts.putAll(loadedCounts);
                    return mergedCounts;
                });
                // A previous bind can have recorded this emoji as normal. The
                // metadata response is authoritative, so let the next style
                // pass derive the type from the refreshed counts.
                clearExpandedReactionTypes(messageId);
                mainHandler.post(() -> {
                    refreshSuperReactionStyles(messageId);
                    refreshManageReactionUsers(messageId);
                });
            } catch (Throwable error) {
                superReactionFetchTimes.remove(messageId);
                logger.error("Could not load Super Reaction metadata", error);
            } finally {
                superReactionFetches.remove(messageId);
            }
        }, "SuperReactionsMetadata").start();
    }

    private String extractMessageFromList(String body, long messageId) {
        Object decoded = GsonUtils.fromJson(body, Object.class);
        if (decoded instanceof Map<?, ?>) return body;
        if (!(decoded instanceof List<?>)) return null;

        for (Object rawMessage : (List<?>) decoded) {
            if (!(rawMessage instanceof Map<?, ?>)) continue;
            Object rawId = ((Map<?, ?>) rawMessage).get("id");
            if (rawId != null && String.valueOf(messageId).equals(String.valueOf(rawId))) {
                return GsonUtils.toJson(rawMessage);
            }
        }
        return null;
    }

    private Map<String, Integer> parseSuperReactionCounts(long messageId, String body) {
        Map<String, Integer> result = new HashMap<>();
        Map<?, ?> message = GsonUtils.fromJson(body, Map.class);
        if (message == null) return result;

        Object rawReactions = message.get("reactions");
        if (!(rawReactions instanceof List<?>)) return result;

        for (Object rawReaction : (List<?>) rawReactions) {
            if (!(rawReaction instanceof Map<?, ?>)) continue;
            Map<?, ?> reaction = (Map<?, ?>) rawReaction;
            Object rawEmoji = reaction.get("emoji");
            if (!(rawEmoji instanceof Map<?, ?>)) continue;

            String key = getReactionKey((Map<?, ?>) rawEmoji);
            if (key == null) continue;

            Integer burstColor = parseBurstColor(reaction.get("burst_colors"));
            if (burstColor != null) {
                Map<String, Integer> colors = superReactionColors.computeIfAbsent(
                        messageId, ignored -> new ConcurrentHashMap<>());
                colors.put(key, burstColor);
                colors.put(normalizeReactionKey(key), burstColor);
            }

            Object rawCountDetails = reaction.get("count_details");
            if (rawCountDetails instanceof Map<?, ?>
                    && ((Map<?, ?>) rawCountDetails).containsKey("normal")) {
                int normalCount = numberValue(((Map<?, ?>) rawCountDetails).get("normal"));
                Map<String, Integer> counts = normalReactionCounts.computeIfAbsent(
                        messageId, ignored -> new ConcurrentHashMap<>());
                counts.put(key, normalCount);
                counts.put(normalizeReactionKey(key), normalCount);
            }

            if (Boolean.TRUE.equals(reaction.get("me_burst"))) {
                markOwnedSuperReaction(messageId, key);
            } else if (reaction.containsKey("me_burst")) {
                removeOwnedSuperReaction(messageId, key);
                removeLocalSuperReaction(messageId, key);
            }

            int burstCount = 0;
            if (rawCountDetails instanceof Map<?, ?>) {
                burstCount = numberValue(((Map<?, ?>) rawCountDetails).get("burst"));
            }
            if (burstCount == 0) {
                burstCount = numberValue(reaction.get("burst_count"));
            }
            if (burstCount == 0 && Boolean.TRUE.equals(reaction.get("me_burst"))) {
                burstCount = 1;
            }
            if (burstCount > 0) {
                result.put(key, burstCount);
                result.put(normalizeReactionKey(key), burstCount);
            } else if (getNormalReactionCount(messageId, key) != null
                    && getNormalReactionCount(messageId, key) > 0) {
                markKnownNormalReaction(messageId, key);
            }
        }
        return result;
    }

    private List<MessageReaction> expandReactions(Collection<?> reactions, long messageId) {
        List<MessageReaction> expanded = new ArrayList<>();
        List<Boolean> displayTypes = new ArrayList<>();
        IdentityHashMap<MessageReaction, Boolean> objectTypes = new IdentityHashMap<>();

        for (Object rawReaction : reactions) {
            if (!(rawReaction instanceof MessageReaction)) continue;
            MessageReaction reaction = (MessageReaction) rawReaction;
            MessageReactionEmoji emoji = reaction.b();
            String key = emoji == null ? null : emoji.c();
            Integer burstCount = getSuperReactionCount(messageId, key);
            Integer normalCount = getNormalReactionCount(messageId, key);

            if (burstCount != null && burstCount > 0
                    && normalCount != null && normalCount > 0) {
                MessageReaction burstReaction = new MessageReaction(burstCount, emoji, false);
                MessageReaction normalReaction = new MessageReaction(normalCount, emoji, reaction.c());
                expanded.add(burstReaction);
                displayTypes.add(true);
                objectTypes.put(burstReaction, true);
                expanded.add(normalReaction);
                displayTypes.add(false);
                objectTypes.put(normalReaction, false);
            } else {
                expanded.add(reaction);
                boolean isSuper = burstCount != null && burstCount > 0;
                displayTypes.add(isSuper);
                objectTypes.put(reaction, isSuper);
            }
        }

        reactionDisplayTypes.put(messageId, displayTypes);
        expandedReactionTypes.put(messageId, objectTypes);
        return expanded;
    }

    private Integer parseBurstColor(Object rawColors) {
        if (!(rawColors instanceof List<?>)) return null;

        // Discord chooses the most vivid color from the first three palette
        // entries for the reaction pill. Keep that behavior so the Android
        // fallback uses the same emoji-specific hue as the web client.
        int bestColor = 0;
        float bestScore = -1f;
        int inspected = 0;
        for (Object rawColor : (List<?>) rawColors) {
            if (inspected++ >= 3) break;
            if (rawColor == null) continue;
            try {
                int color = Color.parseColor(String.valueOf(rawColor));
                float[] hsv = new float[3];
                Color.colorToHSV(color, hsv);
                float score = hsv[1] + hsv[2];
                if (score > bestScore) {
                    bestScore = score;
                    bestColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
                }
            } catch (Throwable ignored) {
                // Ignore malformed palette entries and use the next color.
            }
        }
        return bestScore < 0f ? null : bestColor;
    }

    private Map<String, Integer> loadSuperReactionCounts(long channelId, long messageId, String body) {
        Map<String, Integer> result = parseSuperReactionCounts(messageId, body);
        for (ReactionDescriptor reaction : parseReactionDescriptors(body)) {
            if (getSuperReactionCount(result, reaction.key) != null) continue;
            if (hasBurstUsers(channelId, messageId, reaction.apiKey)) {
                result.put(reaction.key, 1);
                result.put(normalizeReactionKey(reaction.key), 1);
            }
        }
        return result;
    }

    private List<ReactionDescriptor> parseReactionDescriptors(String body) {
        List<ReactionDescriptor> result = new ArrayList<>();
        Map<?, ?> message = GsonUtils.fromJson(body, Map.class);
        if (message == null) return result;

        Object rawReactions = message.get("reactions");
        if (!(rawReactions instanceof List<?>)) return result;

        for (Object rawReaction : (List<?>) rawReactions) {
            if (!(rawReaction instanceof Map<?, ?>)) continue;
            Object rawEmoji = ((Map<?, ?>) rawReaction).get("emoji");
            if (!(rawEmoji instanceof Map<?, ?>)) continue;

            Map<?, ?> emoji = (Map<?, ?>) rawEmoji;
            String key = getReactionKey(emoji);
            String apiKey = getReactionApiKey(emoji);
            if (key != null && apiKey != null) {
                result.add(new ReactionDescriptor(key, apiKey));
            }
        }
        return result;
    }

    private boolean hasBurstUsers(long channelId, long messageId, String reactionApiKey) {
        try (Http.Request request = newDiscordV10Request(
                "/channels/" + channelId + "/messages/" + messageId
                        + "/reactions/" + Uri.encode(reactionApiKey) + "?type=1&limit=1")) {
            Http.Response response = request.execute();
            if (!response.ok()) {
                return false;
            }
            List<?> users = GsonUtils.fromJson(response.text(), List.class);
            return users != null && !users.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Integer getSuperReactionCount(long messageId, String reactionKey) {
        if (reactionKey == null) return null;
        if (locallySentSuperReactions.contains(localReactionKey(messageId, reactionKey))
                || locallySentSuperReactions.contains(
                localReactionKey(messageId, normalizeReactionKey(reactionKey)))
                || locallySentSuperReactions.contains(
                localReactionKey(messageId, displayReactionKey(reactionKey)))
                || locallySentSuperReactions.contains(
                localReactionKey(messageId, normalizeReactionKey(displayReactionKey(reactionKey))))) {
            return 1;
        }
        return getSuperReactionCount(superReactionCounts.get(messageId), reactionKey);
    }

    private Integer getSuperReactionCount(Map<String, Integer> counts, String reactionKey) {
        if (counts == null || reactionKey == null) return null;
        Integer count = counts.get(reactionKey);
        return count == null ? counts.get(normalizeReactionKey(reactionKey)) : count;
    }

    private Integer getNormalReactionCount(long messageId, String reactionKey) {
        if (reactionKey == null) return null;
        return getNormalReactionCount(normalReactionCounts.get(messageId), reactionKey);
    }

    private Integer getNormalReactionCount(Map<String, Integer> counts, String reactionKey) {
        if (counts == null || reactionKey == null) return null;
        Integer count = counts.get(reactionKey);
        return count == null ? counts.get(normalizeReactionKey(reactionKey)) : count;
    }

    private Boolean getExpandedReactionType(long messageId, MessageReaction reaction) {
        if (reaction == null) return null;
        IdentityHashMap<MessageReaction, Boolean> types = expandedReactionTypes.get(messageId);
        return types == null ? null : types.get(reaction);
    }

    private Boolean getReactionViewType(long messageId, ReactionView reactionView) {
        if (reactionView == null || !(reactionView.getParent() instanceof ViewGroup)) return null;
        int index = ((ViewGroup) reactionView.getParent()).indexOfChild(reactionView);
        List<Boolean> types = reactionDisplayTypes.get(messageId);
        return types == null || index < 0 || index >= types.size() ? null : types.get(index);
    }

    private void clearExpandedReactionTypes(long messageId) {
        reactionDisplayTypes.remove(messageId);
        expandedReactionTypes.remove(messageId);
    }

    private int getSuperReactionColor(long messageId, String reactionKey) {
        if (reactionKey == null) return SUPER_REACTION_COLOR;
        Map<String, Integer> colors = superReactionColors.get(messageId);
        if (colors == null) return SUPER_REACTION_COLOR;
        Integer color = colors.get(reactionKey);
        if (color == null) color = colors.get(normalizeReactionKey(reactionKey));
        return color == null ? SUPER_REACTION_COLOR : color;
    }

    private void markOwnedSuperReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        ownedSuperReactions.add(localReactionKey(messageId, reactionKey));
        ownedSuperReactions.add(localReactionKey(messageId, normalizeReactionKey(reactionKey)));
        persistOwnedSuperReactions();
    }

    private void removeOwnedSuperReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        boolean removed = ownedSuperReactions.remove(localReactionKey(messageId, reactionKey));
        removed |= ownedSuperReactions.remove(localReactionKey(messageId, normalizeReactionKey(reactionKey)));
        if (removed) persistOwnedSuperReactions();
    }

    private boolean hasLocalSuperReaction(long messageId) {
        String prefix = messageId + ":";
        for (String key : locallySentSuperReactions) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private void persistOwnedSuperReactions() {
        if (ownedReactionPreferences == null || ownedReactionPreferencesKey == null) return;
        ownedReactionPreferences.edit()
                .putStringSet(ownedReactionPreferencesKey, new HashSet<>(ownedSuperReactions))
                .apply();
    }

    private boolean isOwnSuperReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return false;
        return ownedSuperReactions.contains(localReactionKey(messageId, reactionKey))
                || ownedSuperReactions.contains(localReactionKey(messageId, normalizeReactionKey(reactionKey)))
                || locallySentSuperReactions.contains(localReactionKey(messageId, reactionKey))
                || locallySentSuperReactions.contains(localReactionKey(messageId, normalizeReactionKey(reactionKey)));
    }

    private long getCurrentUserId() {
        try {
            return StoreStream.Companion.getUsers().getMe().getId();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private String localReactionKey(long messageId, String reactionKey) {
        return messageId + ":" + reactionKey;
    }

    private String displayReactionKey(String reactionKey) {
        int separator = reactionKey.lastIndexOf(':');
        if (separator > 0 && separator < reactionKey.length() - 1) {
            String id = reactionKey.substring(separator + 1);
            try {
                Long.parseLong(id);
                return id;
            } catch (NumberFormatException ignored) {
                // Unicode emoji can contain a colon; keep the complete key.
            }
        }
        return reactionKey;
    }

    private String normalizeReactionKey(String reactionKey) {
        return reactionKey == null ? null : reactionKey.replace("\uFE0F", "");
    }

    private String getReactionKey(Map<?, ?> emoji) {
        Object id = emoji.get("id");
        if (id != null) return String.valueOf(id);

        Object name = emoji.get("name");
        return name == null ? null : String.valueOf(name);
    }

    private String getReactionApiKey(Map<?, ?> emoji) {
        Object id = emoji.get("id");
        Object name = emoji.get("name");
        if (id != null && name != null) return name + ":" + id;
        if (id != null) return String.valueOf(id);
        return name == null ? null : String.valueOf(name);
    }

    private int numberValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private void invalidateSuperReactionCache(long messageId, long channelId, boolean keepLocalOwnership) {
        if (messageId == 0L) return;
        if (channelId != 0L) reactionChannels.put(messageId, channelId);
        superReactionCounts.remove(messageId);
        normalReactionCounts.remove(messageId);
        superReactionColors.remove(messageId);
        reactionDisplayTypes.remove(messageId);
        expandedReactionTypes.remove(messageId);
        superReactionFetchTimes.remove(messageId);
        String cachePrefix = messageId + ":";
        boolean removedOwnedReaction = false;
        if (!keepLocalOwnership) {
            for (String key : locallySentSuperReactions) {
                if (key.startsWith(cachePrefix)) locallySentSuperReactions.remove(key);
            }
            for (String key : ownedSuperReactions) {
                if (key.startsWith(cachePrefix)) {
                    removedOwnedReaction |= ownedSuperReactions.remove(key);
                }
            }
            if (removedOwnedReaction) persistOwnedSuperReactions();
        }
        for (String key : burstCheckTimes.keySet()) {
            if (key.startsWith(cachePrefix)) burstCheckTimes.remove(key);
        }
        for (String key : burstReactionUsers.keySet()) {
            if (key.startsWith(cachePrefix)) burstReactionUsers.remove(key);
        }
        for (String key : knownNormalReactions) {
            if (key.startsWith(cachePrefix)) knownNormalReactions.remove(key);
        }

        mainHandler.post(() -> {
            refreshSuperReactionStyles(messageId);
            Long knownChannelId = reactionChannels.get(messageId);
            if (knownChannelId != null) {
                fetchSuperReactionMetadata(knownChannelId, messageId);
            }
        });
    }

    private void refreshSuperReactionStyles(long messageId) {
        boolean hasMixedReaction = hasMixedReaction(messageId);
        synchronized (visibleReactionItems) {
            for (Map.Entry<WidgetChatListAdapterItemReactions, Long> entry : visibleReactionItems.entrySet()) {
                if (messageId == entry.getValue()) {
                    WidgetChatListAdapterItemReactions item = entry.getKey();
                    if (hasMixedReaction) {
                        ReactionsEntry reactionsEntry = visibleReactionEntries.get(item);
                        Integer position = visibleReactionPositions.get(item);
                        if (reactionsEntry != null && position != null) {
                            item.onConfigure(position, reactionsEntry);
                            continue;
                        }
                    }
                    applySuperReactionStyles(item, messageId);
                }
            }
        }
    }

    private boolean hasMixedReaction(long messageId) {
        Map<String, Integer> bursts = superReactionCounts.get(messageId);
        if (bursts == null) return false;
        for (Map.Entry<String, Integer> entry : bursts.entrySet()) {
            Integer burstCount = entry.getValue();
            if (burstCount != null && burstCount > 0
                    && getNormalReactionCount(messageId, entry.getKey()) != null
                    && getNormalReactionCount(messageId, entry.getKey()) > 0) {
                return true;
            }
        }
        return false;
    }

    private void refreshManageReactionUsers(long messageId) {
        synchronized (activeManageReactions) {
            for (Map.Entry<WidgetManageReactions, ManageReactionTarget> entry
                    : activeManageReactions.entrySet()) {
                ManageReactionTarget target = entry.getValue();
                if (target.messageId != messageId) continue;
                refreshManageReactionTabs(entry.getKey(), target);
                if (target.superReaction) fetchBurstReactionUsers(target);
            }
        }
    }

    private void refreshManageReactionTabs(
            WidgetManageReactions widget, ManageReactionTarget target) {
        try {
            java.lang.reflect.Field field = WidgetManageReactions.class
                    .getDeclaredField("emojisAdapter");
            field.setAccessible(true);
            Object value = field.get(widget);
            if (!(value instanceof ManageReactionsEmojisAdapter)) return;

            ManageReactionsEmojisAdapter adapter = (ManageReactionsEmojisAdapter) value;
            List<ManageReactionsEmojisAdapter.ReactionEmojiItem> current =
                    adapter.getInternalData();
            if (current == null || current.isEmpty()) return;
            adapter.setData(expandManageReactionItems(current, target));
        } catch (Throwable error) {
            logger.error("Could not update Super Reaction tabs", error);
        }
    }

    private void applySuperReactionStyles(WidgetChatListAdapterItemReactions item, long messageId) {
        List<ReactionView> reactionViews = new ArrayList<>();
        collectReactionViews(item.itemView, reactionViews);
        for (ReactionView reactionView : reactionViews) {
            MessageReaction reaction = reactionView.getReaction();
            String key = reaction == null || reaction.b() == null ? null : reaction.b().c();
            Integer burstCount = getSuperReactionCount(messageId, key);
            Boolean expandedType = getReactionViewType(messageId, reactionView);
            boolean isSuper = expandedType != null
                    ? expandedType : burstCount != null && burstCount > 0;
            setReactionMeState(reactionView, messageId, reaction, isSuper);
            styleReactionView(reactionView, messageId, key, isSuper, burstCount);
            Long channelId = reactionChannels.get(messageId);
            if (burstCount == null && channelId != null && reaction != null) {
                checkBurstReaction(channelId, messageId, reaction.b());
            }
        }
    }

    private void collectReactionViews(View view, List<ReactionView> output) {
        if (view instanceof ReactionView) {
            output.add((ReactionView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectReactionViews(group.getChildAt(i), output);
        }
    }

    private void styleReactionView(ReactionView reactionView, long messageId,
                                   String reactionKey, boolean isSuperReaction,
                                   Integer burstCount) {
        synchronized (originalReactionBackgrounds) {
            if (!originalReactionBackgrounds.containsKey(reactionView)) {
                originalReactionBackgrounds.put(reactionView, cloneDrawable(reactionView.getBackground()));
            }

            Drawable originalBackground = originalReactionBackgrounds.get(reactionView);
            if (!isSuperReaction) {
                reactionView.setBackground(cloneDrawable(originalBackground));
                reactionView.setContentDescription(null);
                return;
            }

            int reactionColor = getSuperReactionColor(messageId, reactionKey);
            int shineColor = blendColors(reactionColor, Color.WHITE, 0.55f);
            int reflectionColor = blendColors(reactionColor, Color.WHITE, 0.30f);
            int radius = dp(reactionView, 8);
            GradientDrawable colorWash = new GradientDrawable();
            colorWash.setColor(Color.argb(42, Color.red(reactionColor),
                    Color.green(reactionColor), Color.blue(reactionColor)));
            colorWash.setCornerRadius(radius);
            colorWash.setStroke(dp(reactionView, 2), Color.argb(235,
                    Color.red(reactionColor), Color.green(reactionColor),
                    Color.blue(reactionColor)));

            // Keep the normal dark pill underneath, then add a translucent emoji-colored
            // wash and a diagonal lightened-color reflection like Discord's burst pill.
            GradientDrawable shine = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.TRANSPARENT, Color.argb(90, Color.red(shineColor),
                            Color.green(shineColor), Color.blue(shineColor)),
                            Color.argb(90, Color.red(reflectionColor), Color.green(reflectionColor),
                                    Color.blue(reflectionColor)), Color.TRANSPARENT});
            shine.setCornerRadius(radius);
            Drawable baseBackground = cloneDrawable(originalBackground);
            if (baseBackground != null) {
                // StateListDrawable may retain the old client's blue selected
                // state even after the ReactionView state is cleared.
                baseBackground.setState(new int[0]);
                baseBackground.jumpToCurrentState();
            }
            Drawable[] layers = baseBackground == null
                    ? new Drawable[]{colorWash, shine}
                    : new Drawable[]{baseBackground, colorWash, shine};
            reactionView.setBackground(new LayerDrawable(layers));
            reactionView.setContentDescription(burstCount == null
                    ? "Super reaction"
                    : "Super reaction, " + burstCount + " total");
        }
    }

    private int blendColors(int color, int target, float amount) {
        float clampedAmount = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(color) + (Color.red(target) - Color.red(color)) * clampedAmount),
                Math.round(Color.green(color) + (Color.green(target) - Color.green(color)) * clampedAmount),
                Math.round(Color.blue(color) + (Color.blue(target) - Color.blue(color)) * clampedAmount)
        );
    }

    private void setReactionMeState(ReactionView reactionView, long messageId,
                                    MessageReaction reaction, boolean isSuperReaction) {
        boolean isMine = reaction != null
                && (reaction.c() || (isSuperReaction && reaction.b() != null
                && isOwnSuperReaction(messageId, reaction.b().c())));
        // The old selector paints an activated reaction blue. Super reactions
        // use the custom translucent background instead, so do not leave the
        // legacy selected/activated state enabled on their pill.
        boolean useNativeSelection = isMine && !isSuperReaction;
        reactionView.setActivated(useNativeSelection);
        reactionView.setSelected(useNativeSelection);
    }

    private static final class ManageReactionTarget {
        private final long channelId;
        private final long messageId;
        private final String reactionKey;
        private final MessageReactionEmoji emoji;
        private final boolean superReaction;

        private ManageReactionTarget(long channelId, long messageId, String reactionKey,
                                     MessageReactionEmoji emoji, boolean superReaction) {
            this.channelId = channelId;
            this.messageId = messageId;
            this.reactionKey = reactionKey;
            this.emoji = emoji;
            this.superReaction = superReaction;
        }

        private String cacheKey() {
            return messageId + ":" + reactionKey + ":" + (superReaction ? "super" : "normal");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ManageReactionTarget)) return false;
            ManageReactionTarget target = (ManageReactionTarget) other;
            return channelId == target.channelId
                    && messageId == target.messageId
                    && superReaction == target.superReaction
                    && reactionKey.equals(target.reactionKey);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(channelId);
            result = 31 * result + Long.hashCode(messageId);
            result = 31 * result + reactionKey.hashCode();
            return 31 * result + Boolean.hashCode(superReaction);
        }
    }

    private static final class ReactionDescriptor {
        private final String key;
        private final String apiKey;

        private ReactionDescriptor(String key, String apiKey) {
            this.key = key;
            this.apiKey = apiKey;
        }
    }

    private int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private void openSuperReactionPicker(WidgetChatListActions actions) {
        FragmentManager fragmentManager;
        try {
            fragmentManager = actions.getParentFragmentManager();
        } catch (Throwable error) {
            logger.error("Could not open the Super Reaction picker", error);
            return;
        }

        EmojiPickerNavigator.launchBottomSheet(
                fragmentManager,
                new EmojiPickerListener() {
                    @Override
                    public void onEmojiPicked(Emoji emoji) {
                        sendSuperReaction(actions, emoji);
                    }
                },
                EmojiPickerContextType.Chat.INSTANCE,
                null
        );
    }

    private void sendSuperReaction(WidgetChatListActions actions, Emoji emoji) {
        if (!hasNitro()) return;

        Bundle arguments = actions.getArguments();
        if (arguments == null || emoji == null) return;

        long channelId = arguments.getLong(CHANNEL_ID_ARGUMENT, 0L);
        long messageId = arguments.getLong(MESSAGE_ID_ARGUMENT, 0L);
        if (channelId == 0L || messageId == 0L) return;

        String reactionKey = emoji.getReactionKey();
        if (reactionKey == null || reactionKey.isEmpty()) return;

        actions.dismiss();
        sendSuperReaction(channelId, messageId, reactionKey);
    }

    private void sendSuperReaction(long channelId, long messageId,
                                   MessageReactionEmoji emoji) {
        if (emoji == null) return;
        String reactionKey = getReactionApiKey(emoji);
        if (reactionKey == null || reactionKey.isEmpty()) return;
        sendSuperReaction(channelId, messageId, reactionKey);
    }

    private void sendSuperReaction(long channelId, long messageId, String reactionKey) {
        new Thread(() -> {
            try {
                String[] sendTargets = {
                        "/@me?location=Message%20Inline%20Button&type=1",
                        "/1/@me?location=Message%20Inline%20Button&burst=true",
                        "/@me?burst=true"
                };
                boolean sent = false;
                for (String sendTarget : sendTargets) {
                    try (Http.Request request = newDiscordV10Request(
                            "/channels/" + channelId
                                    + "/messages/" + messageId
                                    + "/reactions/" + Uri.encode(reactionKey)
                                    + sendTarget,
                            "PUT")) {
                        Http.Response response = request.execute();
                        if (response.ok()) {
                            sent = true;
                            break;
                        }
                    }
                }
                if (!sent) {
                    throw new IllegalStateException("Discord did not add the Super Reaction");
                }
                locallySentSuperReactions.add(localReactionKey(messageId, reactionKey));
                locallySentSuperReactions.add(localReactionKey(messageId, displayReactionKey(reactionKey)));
                removeKnownNormalReaction(messageId, reactionKey);
                markOwnedSuperReaction(messageId, reactionKey);
                invalidateSuperReactionCache(messageId, channelId, true);
                mainHandler.post(() -> Utils.showToast("Super reaction sent"));
            } catch (Throwable error) {
                logger.error("Could not send Super Reaction", error);
                mainHandler.post(() -> Utils.showToast("Could not send Super Reaction"));
            }
        }, "SuperReactions").start();
    }

    private void removeSuperReaction(long channelId, long messageId, MessageReactionEmoji emoji) {
        if (channelId == 0L || messageId == 0L || emoji == null) return;
        String reactionKey = emoji.c();
        String apiKey = getReactionApiKey(emoji);
        if (reactionKey == null || apiKey == null) return;

        markPendingSuperReactionRemoval(messageId, reactionKey);

        new Thread(() -> {
            try {
                // Delete Own Reaction is the route Discord documents for both
                // normal and super reactions. Keep the private variants as
                // fallbacks for clients that still require an explicit type.
                String[] deleteTargets = {
                        // This is the route used by current Discord clients.
                        // The /1/ segment identifies a burst reaction.
                        "/1/@me?location=Message%20Inline%20Button&burst=true",
                        "/1/@me?burst=true",
                        "/@me?burst=true",
                        "/@me?type=1"
                };
                boolean removed = false;
                for (String deleteTarget : deleteTargets) {
                    if (!deleteSuperReactionRequest(
                            channelId, messageId, apiKey, deleteTarget)) continue;
                    // Discord answers the current burst-delete route with 204
                    // only after accepting the deletion. Do not perform a
                    // second GET here: that endpoint is 403 for this client.
                    if (deleteTarget.startsWith("/1/")
                            || waitForSuperReactionRemoval(channelId, messageId, reactionKey, apiKey)) {
                        removed = true;
                        break;
                    }
                }
                if (!removed) {
                    throw new IllegalStateException("Discord did not remove the Super Reaction");
                }
                removeLocalSuperReaction(messageId, reactionKey);
                invalidateSuperReactionCache(messageId, channelId, true);
                scheduleLocalSuperReactionRemovalFallback(channelId, messageId, emoji);
                mainHandler.post(() -> Utils.showToast("Super reaction removed"));
            } catch (Throwable error) {
                clearPendingSuperReactionRemoval(messageId, reactionKey);
                logger.error("Could not remove Super Reaction", error);
                mainHandler.post(() -> Utils.showToast("Could not remove Super Reaction"));
            }
        }, "SuperReactionsRemove").start();
    }

    private boolean deleteSuperReactionRequest(long channelId, long messageId,
                                                String apiKey, String target) {
        try (Http.Request request = newDiscordV10Request(
                "/channels/" + channelId
                        + "/messages/" + messageId
                        + "/reactions/" + Uri.encode(apiKey)
                        + target,
                "DELETE")) {
            Http.Response response = request.execute();
            if (!response.ok()) {
                logger.error("Super Reaction delete returned HTTP " + response.statusCode
                        + " " + response.statusMessage + " for " + target,
                        new IllegalStateException("HTTP request failed"));
            }
            return response.ok();
        } catch (Throwable error) {
            logger.error("Could not send Super Reaction delete request", error);
            return false;
        }
    }

    private boolean waitForSuperReactionRemoval(long channelId, long messageId,
                                                 String reactionKey, String apiKey) {
        for (int attempt = 0; attempt < 6; attempt++) {
            if (!hasOwnSuperReaction(channelId, messageId, reactionKey, apiKey)) return true;
            try {
                Thread.sleep(300L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !hasOwnSuperReaction(channelId, messageId, reactionKey, apiKey);
    }

    private boolean hasOwnSuperReaction(long channelId, long messageId,
                                        String reactionKey, String apiKey) {
        Boolean burstUserPresent = hasOwnBurstReactionUser(channelId, messageId, apiKey);
        if (burstUserPresent != null) return burstUserPresent;

        try (Http.Request request = newDiscordV10Request(
                "/channels/" + channelId + "/messages/" + messageId)) {
            Http.Response response = request.execute();
            if (!response.ok()) return true;
            Map<?, ?> message = GsonUtils.fromJson(response.text(), Map.class);
            Object rawReactions = message == null ? null : message.get("reactions");
            if (!(rawReactions instanceof List<?>)) return false;
            for (Object rawReaction : (List<?>) rawReactions) {
                if (!(rawReaction instanceof Map<?, ?>)) continue;
                Map<?, ?> reaction = (Map<?, ?>) rawReaction;
                Object rawEmoji = reaction.get("emoji");
                if (!(rawEmoji instanceof Map<?, ?>)) continue;
                Map<?, ?> emoji = (Map<?, ?>) rawEmoji;
                String currentKey = getReactionKey(emoji);
                String currentApiKey = getReactionApiKey(emoji);
                if ((apiKey.equals(currentApiKey) || reactionKey.equals(currentKey))
                        && Boolean.TRUE.equals(reaction.get("me_burst"))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable error) {
            // A failed verification must never be treated as a successful delete.
            logger.error("Could not verify Super Reaction removal", error);
            return true;
        }
    }

    private Boolean hasOwnBurstReactionUser(long channelId, long messageId, String apiKey) {
        try (Http.Request request = newDiscordV10Request(
                "/channels/" + channelId + "/messages/" + messageId
                        + "/reactions/" + Uri.encode(apiKey) + "?limit=100&type=1")) {
            Http.Response response = request.execute();
            if (!response.ok()) return null;

            long currentUserId = getCurrentUserId();
            if (currentUserId == 0L) return null;
            List<?> users = GsonUtils.fromJson(response.text(), List.class);
            if (users == null) return false;
            for (Object rawUser : users) {
                if (!(rawUser instanceof Map<?, ?>)) continue;
                Long userId = longValue(((Map<?, ?>) rawUser).get("id"));
                if (userId != null && userId == currentUserId) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String[] reactionStateKeys(long messageId, String reactionKey) {
        return new String[]{
                localReactionKey(messageId, reactionKey),
                localReactionKey(messageId, normalizeReactionKey(reactionKey)),
                localReactionKey(messageId, displayReactionKey(reactionKey)),
                localReactionKey(messageId,
                        normalizeReactionKey(displayReactionKey(reactionKey)))
        };
    }

    private boolean isPendingSuperReactionRemoval(long messageId, String reactionKey) {
        if (reactionKey == null) return false;
        for (String key : reactionStateKeys(messageId, reactionKey)) {
            if (pendingSuperReactionRemovals.contains(key)) return true;
        }
        return false;
    }

    private boolean isCompletedSuperReactionRemoval(long messageId, String reactionKey) {
        if (reactionKey == null) return false;
        for (String key : reactionStateKeys(messageId, reactionKey)) {
            if (completedSuperReactionRemovals.contains(key)) return true;
        }
        return false;
    }

    private void markPendingSuperReactionRemoval(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        for (String key : reactionStateKeys(messageId, reactionKey)) {
            pendingSuperReactionRemovals.add(key);
        }
    }

    private boolean clearPendingSuperReactionRemoval(long messageId, String reactionKey) {
        boolean removed = false;
        if (reactionKey == null) return false;
        for (String key : reactionStateKeys(messageId, reactionKey)) {
            removed |= pendingSuperReactionRemovals.remove(key);
        }
        return removed;
    }

    private void completeSuperReactionRemoval(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        clearPendingSuperReactionRemoval(messageId, reactionKey);
        for (String key : reactionStateKeys(messageId, reactionKey)) {
            completedSuperReactionRemovals.add(key);
        }
        mainHandler.postDelayed(
                () -> clearCompletedSuperReactionRemoval(messageId, reactionKey),
                LOCAL_REMOVAL_MARKER_TTL_MS);
    }

    private void clearCompletedSuperReactionRemoval(long messageId, String reactionKey) {
        if (reactionKey == null) return;
        for (String key : reactionStateKeys(messageId, reactionKey)) {
            completedSuperReactionRemovals.remove(key);
        }
    }

    private void scheduleLocalSuperReactionRemovalFallback(long channelId, long messageId,
                                                            MessageReactionEmoji emoji) {
        mainHandler.postDelayed(() -> {
            String reactionKey = emoji == null ? null : emoji.c();
            if (reactionKey == null
                    || !clearPendingSuperReactionRemoval(messageId, reactionKey)) return;

            completeSuperReactionRemoval(messageId, reactionKey);
            try {
                List<MessageReactionUpdate> update = new ArrayList<>();
                update.add(new MessageReactionUpdate(
                        getCurrentUserId() == 1L ? 2L : 1L,
                        channelId,
                        messageId,
                        emoji));
                // A DELETE can succeed without producing a gateway event for
                // an old client. Apply the same count decrement locally after
                // giving the gateway a short opportunity to do it first.
                StoreStream.Companion.getMessages().handleReactionUpdate(update, false);
            } catch (Throwable error) {
                logger.error("Could not update removed Super Reaction locally", error);
            }
        }, LOCAL_REMOVAL_FALLBACK_DELAY_MS);
    }

    private void removeLocalSuperReaction(long messageId, String reactionKey) {
        if (reactionKey == null) return;

        boolean changed = false;
        String[] keys = {
                reactionKey,
                normalizeReactionKey(reactionKey),
                displayReactionKey(reactionKey)
        };
        for (String key : keys) {
            if (key == null) continue;
            changed |= locallySentSuperReactions.remove(localReactionKey(messageId, key));
            changed |= ownedSuperReactions.remove(localReactionKey(messageId, key));
        }
        if (changed) persistOwnedSuperReactions();
    }

    @Override
    public void stop(android.content.Context context) {
        patcher.unpatchAll();
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (superReactionButtons) {
            superReactionButtons.clear();
        }
        synchronized (visibleReactionItems) {
            visibleReactionItems.clear();
            visibleReactionEntries.clear();
            visibleReactionPositions.clear();
        }
        synchronized (activeManageReactions) {
            activeManageReactions.clear();
        }
        synchronized (activeManageEmojiAdapters) {
            activeManageEmojiAdapters.clear();
        }
        synchronized (manageReactionWidgetTypes) {
            manageReactionWidgetTypes.clear();
        }
        manageReactionTypes.clear();
        synchronized (manageReactionItemTypes) {
            manageReactionItemTypes.clear();
        }
        synchronized (originalReactionBackgrounds) {
            originalReactionBackgrounds.clear();
        }
        reactionChannels.clear();
        superReactionCounts.clear();
        normalReactionCounts.clear();
        superReactionColors.clear();
        reactionDisplayTypes.clear();
        expandedReactionTypes.clear();
        superReactionFetchTimes.clear();
        superReactionFetches.clear();
        knownNormalReactions.clear();
        locallySentSuperReactions.clear();
        ownedSuperReactions.clear();
        pendingSuperReactionRemovals.clear();
        completedSuperReactionRemovals.clear();
        burstCheckTimes.clear();
        burstChecks.clear();
        burstReactionUsers.clear();
        burstUserFetches.clear();
        ownedReactionPreferences = null;
        ownedReactionPreferencesKey = null;
    }
}
