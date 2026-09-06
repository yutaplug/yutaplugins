package com.github.ushie;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.*;
import com.aliucord.utils.DimenUtils;
import com.discord.databinding.WidgetIncomingShareBinding;
import com.discord.utilities.SnowflakeUtils;
import com.discord.utilities.captcha.CaptchaHelper;
import com.discord.utilities.intent.IntentUtils;
import com.discord.utilities.time.Clock;
import com.discord.widgets.chat.list.ViewEmbedGameInvite;
import com.discord.widgets.chat.list.actions.WidgetChatListActions;
import com.discord.widgets.share.WidgetIncomingShare;
import com.discord.widgets.user.search.ViewGlobalSearchItem;
import com.discord.widgets.user.search.WidgetGlobalSearchAdapter;
import com.discord.widgets.user.search.WidgetGlobalSearchModel;
import com.google.android.material.appbar.AppBarLayout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@SuppressLint({"MissingPermission", "NewApi"})
@AliucordPlugin(requiresRestart = true)
public class ForwardMessages extends Plugin {
    private static final String FAVORITES = "forward_message_favorites";
    private static final String FAVORITE_STAR = "forward_message_favorite_star";
    private final Map<Long, String> favorites = new LinkedHashMap<>();
    private final Map<Long, String> favoriteServers = new LinkedHashMap<>();
    private final Map<Long, Integer> originalPositions = new LinkedHashMap<>();
    private SharedPreferences preferences;
    private LinearLayout activeForwardLayout;
    private WidgetIncomingShare activeShare;
    private RecyclerView activeResultsRecyclerView;
    private Field selectedReceiverPublisherField;
    private long selectedFavorite;

    @Override
    public void start(Context context) throws Throwable {
        preferences = context.getSharedPreferences("ForwardMessages", Context.MODE_PRIVATE);
        loadFavorites();
        var forwardId = View.generateViewId();

        Drawable replyIcon = ContextCompat.getDrawable(Utils.appActivity, com.lytefast.flexinput.R.e.ic_reply_24dp).mutate();
        replyIcon.setAutoMirrored(true);
        Utils.tintToTheme(replyIcon);

        MirroredDrawable forwardIcon = new MirroredDrawable(replyIcon);

        Method bindingReflection = WidgetIncomingShare.class.getDeclaredMethod("getBinding");
        bindingReflection.setAccessible(true);
        Field modelCommentField = WidgetIncomingShare.Model.class.getDeclaredField("comment");
        modelCommentField.setAccessible(true);
        selectedReceiverPublisherField =
            WidgetIncomingShare.class.getDeclaredField("selectedReceiverPublisher");
        selectedReceiverPublisherField.setAccessible(true);

        patcher.patch(WidgetChatListActions.class.getDeclaredMethod("configureUI", WidgetChatListActions.Model.class),
            new PreHook(param -> {
                var actions = (WidgetChatListActions) param.thisObject;
                var scrollView = (NestedScrollView) actions.getView();
                var lay = (LinearLayout) scrollView.getChildAt(0);

                if (lay.findViewById(forwardId) == null) {
                    TextView tw = new TextView(lay.getContext(), null, 0,
                        com.lytefast.flexinput.R.i.UiKit_Settings_Item_Icon);
                    tw.setId(forwardId);
                    tw.setText("Forward");
                    tw.setCompoundDrawablesRelativeWithIntrinsicBounds(forwardIcon, null, null, null);
                    int childrenCount = lay.getChildCount();
                    boolean foundIndex = false;
                    for (int i = 0; i < childrenCount; i++) {
                        View view = lay.getChildAt(i);
                        if (view.getId() == Utils.getResId("dialog_chat_actions_reply", "id")) {
                            foundIndex = true;
                            lay.addView(tw, i + 1);
                            break;
                        }
                    }
                    if (!foundIndex) lay.addView(tw, 5);
                    tw.setOnClickListener((v) -> {
                        WidgetChatListActions.Model model = (WidgetChatListActions.Model) param.args[0];
                        long messageId = model.getMessage().getId();
                        String messageContent = model.getMessage().getContent();
                        long channelId = model.getChannel().k();

                        Intent putExtra = new Intent()
                            .putExtra("io.gh.reisxd.aliuplugins.MESSAGE_CONTENT", messageContent)
                            .putExtra("io.gh.reisxd.aliuplugins.MESSAGE_ID", messageId)
                            .putExtra("io.gh.reisxd.aliuplugins.CHANNEL_ID", channelId);
                        Utils.mainThread.post(() -> {
                            Utils.openPage(Utils.getAppActivity(), WidgetIncomingShare.class, putExtra);
                            actions.dismiss();
                        });
                    });
                }
            }));

        patcher.patch(WidgetIncomingShare.class.getDeclaredMethod("initialize", WidgetIncomingShare.ContentModel.class),
            new PreHook(param -> {
                WidgetIncomingShare share = (WidgetIncomingShare) param.thisObject;
                Intent intent = share.getMostRecentIntent();
                long messageId = intent.getLongExtra("io.gh.reisxd.aliuplugins.MESSAGE_ID", 0);
                long channelId = intent.getLongExtra("io.gh.reisxd.aliuplugins.CHANNEL_ID", 0);
                String messageContent = intent.getStringExtra("io.gh.reisxd.aliuplugins.MESSAGE_CONTENT");

                if (messageId == 0 || channelId == 0)
                    return;

                activeShare = share;
                WidgetIncomingShareBinding binding;
                try {
                    binding = (WidgetIncomingShareBinding) bindingReflection.invoke(share);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                AppBarLayout appBar = (AppBarLayout) binding.a.getChildAt(0);
                Toolbar toolbar = (Toolbar) appBar.getChildAt(0);
                toolbar.setTitle("Forward");
                LinearLayout layout = (LinearLayout) binding.j.getChildAt(0);
                activeForwardLayout = layout;
                activeResultsRecyclerView = binding.h;

                TextView shareToText = (TextView) layout.getChildAt(4);
                shareToText.setText("Forward To");

                TextView messagePreviewText = (TextView) layout.getChildAt(0);
                messagePreviewText.setText("Optional Message");

                TextView previewText = new TextView(layout.getContext(), null, 0,
                    com.lytefast.flexinput.R.i.UiKit_TextAppearance);
                TextView messagePreviewCustom = new TextView(layout.getContext(), null, 0,
                    com.lytefast.flexinput.R.i.UiKit_Search_Header);
                messagePreviewCustom.setText("Message Preview");
                previewText.setText(messageContent);
                previewText.setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(2), 0, 0);

                layout.addView(messagePreviewCustom, 0);
                layout.addView(previewText, 1);

                installFavoriteLongPress(binding.h);
            }));

        patcher.patch(WidgetIncomingShare.class.getDeclaredMethod("onSendClicked", Context.class,
            WidgetGlobalSearchModel.ItemDataPayload.class, ViewEmbedGameInvite.Model.class,
            WidgetIncomingShare.ContentModel.class, boolean.class, int.class, boolean.class,
            CaptchaHelper.CaptchaPayload.class), new PreHook(param -> {
            WidgetIncomingShare share = (WidgetIncomingShare) param.thisObject;
            WidgetGlobalSearchModel.ItemDataPayload itemDataPayload = (WidgetGlobalSearchModel.ItemDataPayload) param.args[1];
            Intent intent = share.getMostRecentIntent();

            WidgetIncomingShareBinding binding;
            try {
                binding = (WidgetIncomingShareBinding) bindingReflection.invoke(share);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            EditText textInput = binding.d.getEditText();

            long messageId = intent.getLongExtra("io.gh.reisxd.aliuplugins.MESSAGE_ID", 0);
            long channelId = intent.getLongExtra("io.gh.reisxd.aliuplugins.CHANNEL_ID", 0);
            if (messageId != 0 && channelId != 0) {
                long channel = selectedFavorite;
                if (channel == 0 && itemDataPayload != null)
                    channel = itemDataPayload.getChannel().k();
                if (channel == 0)
                    return;
                final long selectedChannel = channel;
                String commentMessage = textInput.getText().toString();
                Utils.threadPool.submit(() -> {
                    try {
                        Http.Response res = Http.Request
                            .newDiscordRNRequest(
                                String.format("/channels/%d/messages", selectedChannel), "POST")
                            .executeWithJson(new Message(
                                new MessageReference(1, messageId, channelId, null, false), ""));
                        if (!res.ok())
                            Toast.makeText(context, "Forwarding failed: " + res.statusCode,
                                Toast.LENGTH_SHORT).show();
                        else if (!commentMessage.isEmpty()) {
                            Http.Request.newDiscordRNRequest(
                                    String.format("/channels/%d/messages", selectedChannel), "POST")
                                .executeWithJson(new Message(null, commentMessage));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                Utils.mainThread.post(() -> share.startActivity(IntentUtils.RouteBuilders.selectChannel(selectedChannel, 0, null)
                    .setPackage(Utils.getAppContext().getPackageName())));

                selectedFavorite = 0;
                param.setResult(null);
            }
        }));

        patcher.patch(WidgetGlobalSearchAdapter.class.getMethod("setData", List.class),
            new PreHook(param -> {
                if (activeResultsRecyclerView != null &&
                    param.thisObject == activeResultsRecyclerView.getAdapter() &&
                    param.args[0] instanceof List) {
                    rememberOriginalPositions((List<?>) param.args[0]);
                    param.args[0] = reorderFavorites((List<?>) param.args[0]);
                }
            }));

        patcher.patch(WidgetGlobalSearchAdapter.SearchViewHolder.class.getDeclaredMethod("onConfigure",
            int.class, WidgetGlobalSearchModel.ItemDataPayload.class), new Hook(param -> {
            WidgetGlobalSearchAdapter.SearchViewHolder holder =
                (WidgetGlobalSearchAdapter.SearchViewHolder) param.thisObject;
            WidgetGlobalSearchModel.ItemDataPayload payload =
                (WidgetGlobalSearchModel.ItemDataPayload) param.args[1];
            ViewGlobalSearchItem row = holder.getViewGlobalSearchItem();
            View star = row.findViewWithTag(FAVORITE_STAR);
            TextView server = row.findViewById(Utils.getResId("item_group_tv", "id"));
            long channelId = getChannelId(payload);
            if (favorites.containsKey(channelId)) {
                if (star == null) {
                    ImageButton starButton = new ImageButton(row.getContext());
                    starButton.setTag(FAVORITE_STAR);
                    starButton.setId(View.generateViewId());
                    starButton.setImageResource(com.lytefast.flexinput.R.e.abc_ic_star_black_16dp);
                    starButton.setImageTintList(android.content.res.ColorStateList.valueOf(0xffb5bac1));
                    starButton.setBackground(null);
                    starButton.setPadding(0, 0, 0, 0);
                    star = starButton;
                    ConstraintLayout.LayoutParams starParams =
                        new ConstraintLayout.LayoutParams(DimenUtils.dpToPx(20),
                            DimenUtils.dpToPx(20));
                    starParams.endToStart = Utils.getResId("item_mentions_tv", "id");
                    starParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                    starParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                    starParams.setMarginEnd(DimenUtils.dpToPx(4));
                    row.addView(star, starParams);
                }
                star.setVisibility(View.VISIBLE);
                if (server != null) {
                    ConstraintLayout.LayoutParams serverParams =
                        (ConstraintLayout.LayoutParams) server.getLayoutParams();
                    serverParams.endToStart = star.getId();
                    serverParams.rightToLeft = star.getId();
                    serverParams.setMarginEnd(DimenUtils.dpToPx(4));
                    server.setLayoutParams(serverParams);
                }
                long favoriteId = channelId;
                star.setOnClickListener(v -> {
                    favorites.remove(favoriteId);
                    favoriteServers.remove(favoriteId);
                    saveFavorites();
                    reorderCurrentResults();
                    refreshResultRows();
                });
            } else if (star != null) {
                star.setVisibility(View.GONE);
                if (server != null) {
                    ConstraintLayout.LayoutParams serverParams =
                        (ConstraintLayout.LayoutParams) server.getLayoutParams();
                    int mentionsId = Utils.getResId("item_mentions_tv", "id");
                    serverParams.endToStart = mentionsId;
                    serverParams.rightToLeft = mentionsId;
                    serverParams.setMarginEnd(DimenUtils.dpToPx(8));
                    server.setLayoutParams(serverParams);
                }
            }
        }));

        patcher.patch(WidgetIncomingShare.class.getDeclaredMethod("configureUi", WidgetIncomingShare.Model.class,
            Clock.class), new PreHook(param -> {
            WidgetIncomingShare.Model model = (WidgetIncomingShare.Model) param.args[0];
            WidgetIncomingShare share = (WidgetIncomingShare) param.thisObject;
            Intent intent = share.getMostRecentIntent();
            long messageId = intent.getLongExtra("io.gh.reisxd.aliuplugins.MESSAGE_ID", 0);
            try {
                if (messageId != 0) {
                    modelCommentField.set(model, "...");
                    WidgetIncomingShareBinding binding =
                        (WidgetIncomingShareBinding) bindingReflection.invoke(share);
                    if (activeShare != share || activeResultsRecyclerView != binding.h)
                        originalPositions.clear();
                    activeShare = share;
                    activeResultsRecyclerView = binding.h;
                    installFavoriteLongPress(binding.h);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }

    private void loadFavorites() {
        String serialized = preferences.getString(FAVORITES, "");
        if (serialized.isEmpty()) return;
        for (String entry : serialized.split("\\n", -1)) {
            String[] fields = entry.split("\\|", -1);
            if (fields.length < 2) continue;
            try {
                long id = Long.parseLong(fields[0]);
                favorites.put(id, fields[1]);
                if (fields.length > 2) favoriteServers.put(id, fields[2]);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void saveFavorites() {
        StringBuilder serialized = new StringBuilder();
        for (Map.Entry<Long, String> entry : favorites.entrySet()) {
            if (serialized.length() != 0) serialized.append('\n');
            serialized.append(entry.getKey()).append('|')
                .append(entry.getValue().replace("\\n", " ").replace("|", " ")).append('|')
                .append(favoriteServers.getOrDefault(entry.getKey(), "")
                    .replace("\\n", " ").replace("|", " "));
        }
        preferences.edit().putString(FAVORITES, serialized.toString()).apply();
    }

    private void addFavoritesCategory(LinearLayout layout) {
        View old = layout.findViewWithTag(FAVORITES);
        if (old != null) layout.removeView(old);

        LinearLayout category = new LinearLayout(layout.getContext());
        category.setOrientation(LinearLayout.VERTICAL);
        category.setTag(FAVORITES);
        category.setPadding(0, DimenUtils.dpToPx(8), 0, DimenUtils.dpToPx(4));

        TextView header = new TextView(layout.getContext(), null, 0,
            com.lytefast.flexinput.R.i.UiKit_Search_Header);
        header.setText("Favorited");
        category.addView(header);

        if (favorites.isEmpty()) {
            TextView empty = new TextView(layout.getContext(), null, 0,
                com.lytefast.flexinput.R.i.UiKit_TextAppearance);
            empty.setText("Hold a channel or DM to add it here");
            empty.setPadding(0, DimenUtils.dpToPx(4), 0, DimenUtils.dpToPx(8));
            category.addView(empty);
        } else {
            for (Map.Entry<Long, String> favorite : favorites.entrySet()) {
                LinearLayout item = new LinearLayout(layout.getContext());
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinimumHeight(0);
                item.setPadding(DimenUtils.dpToPx(12), 0,
                    DimenUtils.dpToPx(20), 0);

                TextView channelIcon = new TextView(layout.getContext(), null, 0,
                    com.lytefast.flexinput.R.i.UiKit_TextAppearance);
                channelIcon.setText("#");
                channelIcon.setTextSize(22);
                channelIcon.setTextColor(0xff99aab5);
                channelIcon.setGravity(Gravity.CENTER);
                channelIcon.setIncludeFontPadding(false);
                item.addView(channelIcon, new LinearLayout.LayoutParams(
                    DimenUtils.dpToPx(26), DimenUtils.dpToPx(26)));

                TextView channelName = new TextView(layout.getContext(), null, 0,
                    com.lytefast.flexinput.R.i.UiKit_TextAppearance);
                channelName.setText(favorite.getValue());
                channelName.setTextSize(18);
                channelName.setTextColor(0xfff2f3f5);
                channelName.setGravity(Gravity.CENTER_VERTICAL);
                channelName.setIncludeFontPadding(false);
                channelName.setMinHeight(0);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                nameParams.setMarginStart(DimenUtils.dpToPx(8));
                item.addView(channelName, nameParams);

                TextView serverName = new TextView(layout.getContext(), null, 0,
                    com.lytefast.flexinput.R.i.UiKit_TextAppearance);
                serverName.setText(favoriteServers.getOrDefault(favorite.getKey(), ""));
                serverName.setTextSize(16);
                serverName.setTextColor(0xffb5bac1);
                serverName.setGravity(Gravity.CENTER_VERTICAL);
                serverName.setIncludeFontPadding(false);
                serverName.setMinHeight(0);
                item.addView(serverName, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                item.setOnClickListener(v -> {
                    selectFavorite(favorite.getKey());
                    Toast.makeText(v.getContext(), "Selected " + favorite.getValue(), Toast.LENGTH_SHORT).show();
                });
                item.setOnLongClickListener(v -> {
                    favorites.remove(favorite.getKey());
                    favoriteServers.remove(favorite.getKey());
                    saveFavorites();
                    addFavoritesCategory(layout);
                    return true;
                });
                category.addView(item, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, DimenUtils.dpToPx(40)));
            }
        }
        layout.addView(category, Math.min(2, layout.getChildCount()));
    }

    private void installFavoriteLongPress(RecyclerView recyclerView) {
        if (recyclerView == null) return;
        recyclerView.post(() -> {
            if (recyclerView.getTag(FAVORITES.hashCode()) != null) return;
            recyclerView.setTag(FAVORITES.hashCode(), true);
            final GestureDetector[] detector = new GestureDetector[1];
            detector[0] = new GestureDetector(recyclerView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public void onLongPress(MotionEvent event) {
                        View child = recyclerView.findChildViewUnder(event.getX(), event.getY());
                        if (child == null) return;
                        int position = recyclerView.getChildAdapterPosition(child);
                        Object payload = getAdapterItem(recyclerView, position);
                        if (isNativeProfileLongPress(recyclerView, child, payload,
                            event.getX(), event.getY())) return;
                        favoritePayload(recyclerView.getContext(), payload);
                    }
                });
            recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent event) {
                    detector[0].onTouchEvent(event);
                    return false;
                }

                @Override public void onTouchEvent(RecyclerView rv, MotionEvent event) {
                    detector[0].onTouchEvent(event);
                }
            });
            recyclerView.postDelayed(() -> refreshFavoriteServers(recyclerView), 300);
        });
    }

    private boolean isNativeProfileLongPress(RecyclerView recyclerView, View row,
                                             Object payload, float x, float y) {
        if (payload == null || !getGuildName(payload).isEmpty()) return false;
        View name = row.findViewById(Utils.getResId("item_name_tv", "id"));
        if (name == null || name.getVisibility() != View.VISIBLE) return false;
        Rect hit = new Rect();
        name.getHitRect(hit);
        hit.offset(row.getLeft(), row.getTop());
        return hit.contains((int) x, (int) y);
    }

    private void refreshFavoriteServers(RecyclerView recyclerView) {
        if (recyclerView.getAdapter() == null) return;
        boolean changed = false;
        for (int i = 0; i < recyclerView.getAdapter().getItemCount(); i++) {
            Object payload = getAdapterItem(recyclerView, i);
            long channelId = getChannelId(payload);
            if (favorites.containsKey(channelId)) {
                String server = getGuildName(payload);
                if (!server.equals(favoriteServers.get(channelId))) {
                    favoriteServers.put(channelId, server);
                    changed = true;
                }
            }
        }
        if (changed) saveFavorites();

    }

    private Object getFavoritePayload(long channelId) {
        if (activeResultsRecyclerView == null || activeResultsRecyclerView.getAdapter() == null) return null;
        for (int i = 0; i < activeResultsRecyclerView.getAdapter().getItemCount(); i++) {
            Object payload = getAdapterItem(activeResultsRecyclerView, i);
            if (getChannelId(payload) == channelId) return payload;
        }
        return null;
    }

    private void selectFavorite(long channelId) {
        selectedFavorite = channelId;
        if (activeShare == null || activeResultsRecyclerView == null ||
            selectedReceiverPublisherField == null || activeResultsRecyclerView.getAdapter() == null) return;

        for (int i = 0; i < activeResultsRecyclerView.getAdapter().getItemCount(); i++) {
            Object payload = getAdapterItem(activeResultsRecyclerView, i);
            if (getChannelId(payload) != channelId) continue;
            try {
                Object publisher = selectedReceiverPublisherField.get(activeShare);
                publisher.getClass().getMethod("onNext", Object.class).invoke(publisher, payload);
            } catch (Exception ignored) { }
            return;
        }
    }

    private void rememberOriginalPositions(List<?> source) {
        for (int i = 0; i < source.size(); i++) {
            Object value = source.get(i);
            if (!(value instanceof WidgetGlobalSearchModel.ItemDataPayload)) continue;
            long channelId = getChannelId(value);
            if (channelId != 0 && !originalPositions.containsKey(channelId))
                originalPositions.put(channelId, i);
        }
    }

    private List<WidgetGlobalSearchModel.ItemDataPayload> restoreOriginalOrder(
        List<WidgetGlobalSearchModel.ItemDataPayload> source) {
        ArrayList<Integer> channelIndexes = new ArrayList<>();
        ArrayList<WidgetGlobalSearchModel.ItemDataPayload> channelItems = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            WidgetGlobalSearchModel.ItemDataPayload payload = source.get(i);
            if (getChannelId(payload) != 0) {
                channelIndexes.add(i);
                channelItems.add(payload);
            }
        }
        channelItems.sort(new Comparator<WidgetGlobalSearchModel.ItemDataPayload>() {
            @Override
            public int compare(WidgetGlobalSearchModel.ItemDataPayload left,
                               WidgetGlobalSearchModel.ItemDataPayload right) {
                int leftPosition = originalPositions.getOrDefault(getChannelId(left), Integer.MAX_VALUE);
                int rightPosition = originalPositions.getOrDefault(getChannelId(right), Integer.MAX_VALUE);
                return Integer.compare(leftPosition, rightPosition);
            }
        });
        ArrayList<WidgetGlobalSearchModel.ItemDataPayload> restored = new ArrayList<>(source);
        for (int i = 0; i < channelIndexes.size(); i++)
            restored.set(channelIndexes.get(i), channelItems.get(i));
        return restored;
    }

    private List<WidgetGlobalSearchModel.ItemDataPayload> reorderFavorites(
        List<?> source) {
        ArrayList<WidgetGlobalSearchModel.ItemDataPayload> remaining = new ArrayList<>();
        Map<Long, WidgetGlobalSearchModel.ItemDataPayload> favoriteItems = new LinkedHashMap<>();

        for (Object value : source) {
            if (!(value instanceof WidgetGlobalSearchModel.ItemDataPayload)) continue;
            WidgetGlobalSearchModel.ItemDataPayload payload =
                (WidgetGlobalSearchModel.ItemDataPayload) value;
            long channelId = getChannelId(payload);
            if (favorites.containsKey(channelId)) {
                favoriteItems.put(channelId, payload);
            } else {
                remaining.add(payload);
            }
        }

        remaining = new ArrayList<>(restoreOriginalOrder(remaining));
        if (favoriteItems.isEmpty()) return remaining;

        int headerCount = 0;
        int insertion = -1;
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i) instanceof WidgetGlobalSearchModel.ItemHeader &&
                ++headerCount == 2) {
                insertion = i + 1;
                break;
            }
        }
        if (insertion < 0) insertion = Math.min(1, remaining.size());

        ArrayList<WidgetGlobalSearchModel.ItemDataPayload> reordered =
            new ArrayList<>(remaining);
        int offset = 0;
        for (Long channelId : favorites.keySet()) {
            WidgetGlobalSearchModel.ItemDataPayload payload = favoriteItems.get(channelId);
            if (payload != null) reordered.add(insertion + offset++, payload);
        }
        return reordered;
    }

    private void refreshResultRows() {
        if (activeResultsRecyclerView != null && activeResultsRecyclerView.getAdapter() != null)
            activeResultsRecyclerView.getAdapter().notifyDataSetChanged();
    }

    private void reorderCurrentResults() {
        if (activeResultsRecyclerView == null || activeResultsRecyclerView.getAdapter() == null)
            return;
        try {
            Object adapter = activeResultsRecyclerView.getAdapter();
            Method getInternalData = adapter.getClass().getMethod("getInternalData");
            Object data = getInternalData.invoke(adapter);
            if (data instanceof List) {
                Method setData = adapter.getClass().getMethod("setData", List.class);
                setData.invoke(adapter, reorderFavorites((List<?>) data));
            }
        } catch (Exception ignored) { }
    }

    private boolean favoritePayload(Context context, Object payload) {
        long channelId = getChannelId(payload);
        if (channelId == 0) return false;
        String name = getChannelName(payload);
        if (name.isEmpty()) name = "Channel " + channelId;
        if (favorites.containsKey(channelId)) {
            favorites.remove(channelId);
            favoriteServers.remove(channelId);
            saveFavorites();
            reorderCurrentResults();
            refreshResultRows();
            Toast.makeText(context, name + " removed from Favorites", Toast.LENGTH_SHORT).show();
            return true;
        }
        favorites.put(channelId, name);
        favoriteServers.put(channelId, getGuildName(payload));
        saveFavorites();
        reorderCurrentResults();
        refreshResultRows();
        Toast.makeText(context, name + " added to Favorites", Toast.LENGTH_SHORT).show();
        return true;
    }

    private Object getAdapterItem(RecyclerView recyclerView, int position) {
        if (position == RecyclerView.NO_POSITION || recyclerView.getAdapter() == null) return null;
        Object adapter = recyclerView.getAdapter();
        for (String methodName : new String[] { "getItem", "getData", "getItemAt" }) {
            try {
                Method method = adapter.getClass().getMethod(methodName, int.class);
                return method.invoke(adapter, position);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private long getChannelId(Object payload) {
        if (payload == null) return 0;
        try {
            Object channel = payload.getClass().getMethod("getChannel").invoke(payload);
            Object id = channel.getClass().getMethod("k").invoke(channel);
            return id instanceof Number ? ((Number) id).longValue() : 0;
        } catch (Exception ignored) { return 0; }
    }

    private String getChannelName(Object payload) {
        if (payload == null) return "";
        try {
            Object channel = payload.getClass().getMethod("getChannel").invoke(payload);
            for (String methodName : new String[] { "getName", "p" }) {
                try {
                    Object name = channel.getClass().getMethod(methodName).invoke(channel);
                    if (name != null && !name.toString().isEmpty()) return name.toString();
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String getGuildName(Object payload) {
        if (payload == null) return "";
        try {
            Object guild = payload.getClass().getMethod("getGuild").invoke(payload);
            if (guild == null) return "";
            Object name = guild.getClass().getMethod("getName").invoke(guild);
            return name == null ? "" : name.toString();
        } catch (Exception ignored) { return ""; }
    }

    public class MessageReference {
        public int type;
        public long message_id;
        public long channel_id;
        public Long guild_id;
        public boolean fail_if_not_exists;

        public MessageReference(int type, long message_id, long channel_id, Long guild_id, boolean fail_if_not_exists) {
            this.type = type;
            this.message_id = message_id;
            this.channel_id = channel_id;
            this.guild_id = guild_id;
            this.fail_if_not_exists = fail_if_not_exists;
        }
    }

    public int nextBits(Random rng, int bits) {
        if (bits < 0 || bits > 32)
            throw new IllegalArgumentException("bits must be 0..32");
        if (bits == 0)
            return 0;
        if (bits == 32)
            return rng.nextInt();
        int mask = (1 << bits) - 1;
        return rng.nextInt() & mask;
    }

    public int nextBits(int bits) {
        return nextBits(ThreadLocalRandom.current(), bits);
    }

    public class Message {
        public String content = "";
        public int flags = 0;
        public boolean tts = false;
        public String nonce = String.valueOf((SnowflakeUtils.fromTimestamp(System.currentTimeMillis()) + nextBits(23)));
        public String mobile_network_type = "unknown";
        public int signal_strength = 0;
        public MessageReference message_reference;

        public Message(MessageReference reference, String content) {
            this.message_reference = reference;
            this.content = content;
            Context context = Utils.getAppContext();
            ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        mobile_network_type = "wifi";
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        mobile_network_type = "cellular";
                    }

                    if (Build.VERSION.SDK_INT >= 28) {
                        SignalStrength ss = telephonyManager.getSignalStrength();
                        signal_strength = (ss != null) ? ss.getLevel() : 0;
                    }
                }
            }
        }
    }
}