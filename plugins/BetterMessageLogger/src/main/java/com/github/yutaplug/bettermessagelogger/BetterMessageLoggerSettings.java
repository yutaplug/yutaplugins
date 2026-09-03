package com.github.yutaplug.bettermessagelogger;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.views.CheckedSetting;
import com.discord.utilities.color.ColorCompat;
import com.google.android.material.button.MaterialButton;
import androidx.core.content.ContextCompat;

import java.util.LinkedHashSet;
import java.util.Set;

public final class BetterMessageLoggerSettings extends BottomSheet {
    private SettingsAPI settings;

    public BetterMessageLoggerSettings(SettingsAPI settings) {
        this.settings = settings;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 24));

        addIntro(context, "Keep deleted messages and edit history available in chat. "
                + "Use the filters below to control what gets logged.");

        addSectionHeader(context, "Storage", true);

        CheckedSetting database = Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH,
                "Enable database", "Persist logs in Aliucord/BetterMessageLogger.db");
        database.setChecked(settings.getBool("database", false));
        database.setOnCheckedListener(value -> {
            settings.setBool("database", value);
            BetterMessageLogger plugin = BetterMessageLogger.getInstance();
            if (plugin != null) plugin.setDatabaseEnabled(value);
        });
        addView(database);

        addButton(context, "Clear saved logs", "ic_delete_24dp", () -> {
            BetterMessageLogger plugin = BetterMessageLogger.getInstance();
            if (plugin != null) plugin.clearDatabase();
        });
        addButton(context, "Export logs as TXT", "ic_copy_24dp", () -> {
            BetterMessageLogger plugin = BetterMessageLogger.getInstance();
            if (plugin != null) plugin.exportDatabase();
        });

        addSectionHeader(context, "Message filters", false);
        addToggle(context, "Ignore my messages", "Do not save messages sent by your account", "ignoreOwn");
        addToggle(context, "Ignore bot messages", "Do not save messages sent by bots", "ignoreBots");

        addSectionHeader(context, "Excluded users", false);
        addAction(context, "Ignored user IDs", "Add a user ID to ignore (" + count("ignoredUsers") + ")",
                () -> showIdDialog(context, "Ignored user ID", "ignoredUsers", "User ID"));

        addSectionHeader(context, "Channel scope", false);
        addAction(context, "Blacklist channel IDs", "Do not log these channels (" + count("blackChannels") + ")",
                () -> showIdDialog(context, "Blacklisted channel ID", "blackChannels", "Channel ID"));
        addAction(context, "Whitelist channel IDs", "Only log these channels when this list is non-empty ("
                + count("whiteChannels") + ")", () -> showIdDialog(context, "Whitelisted channel ID", "whiteChannels", "Channel ID"));

        addSectionHeader(context, "Server scope", false);
        addAction(context, "Blacklist server IDs", "Do not log these servers (" + count("blackServers") + ")",
                () -> showIdDialog(context, "Blacklisted server ID", "blackServers", "Server ID"));
        addAction(context, "Whitelist server IDs", "Only log these servers when this list is non-empty ("
                + count("whiteServers") + ")", () -> showIdDialog(context, "Whitelisted server ID", "whiteServers", "Server ID"));

        addSectionHeader(context, "Direct messages", false);
        addAction(context, "Blacklist DM channel IDs", "Do not log these DMs (" + count("blackDms") + ")",
                () -> showIdDialog(context, "Blacklisted DM channel ID", "blackDms", "DM channel ID"));
        addAction(context, "Whitelist DM channel IDs", "Only log these DMs when this list is non-empty ("
                + count("whiteDms") + ")", () -> showIdDialog(context, "Whitelisted DM channel ID", "whiteDms", "DM channel ID"));
    }

    private void addIntro(Context context, String text) {
        TextView intro = new TextView(context);
        intro.setText(text);
        intro.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setLineSpacing(0, 1.1f);
        intro.setPadding(0, 0, 0, dp(context, 4));
        getLinearLayout().addView(intro, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSectionHeader(Context context, String title, boolean first) {
        TextView header = new TextView(context);
        header.setText(title.toUpperCase(java.util.Locale.ROOT));
        header.setTextColor(themeColor(context, "colorBrand", Color.rgb(88, 101, 242)));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, first ? dp(context, 12) : dp(context, 24), 0, dp(context, 8));
        getLinearLayout().addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addToggle(Context context, String title, String subtitle, String key) {
        CheckedSetting setting = Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, title, subtitle);
        setting.setChecked(settings.getBool(key, false));
        setting.setOnCheckedListener(value -> {
            settings.setBool(key, value);
            BetterMessageLogger plugin = BetterMessageLogger.getInstance();
            if (plugin != null) plugin.settingsChanged();
        });
        addView(setting);
    }

    private void addAction(Context context, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 58));
        row.setPaddingRelative(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        row.setBackground(selectableBackground(context));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setPadding(0, dp(context, 2), 0, 0);
        row.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));

        row.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 4);
        getLinearLayout().addView(row, params);
    }

    private void addButton(Context context, String title, String iconName, Runnable action) {
        MaterialButton button = new MaterialButton(context);
        button.setText(title);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(context, 48));
        button.setContentDescription(title);
        int iconId = Utils.getResId(iconName, "drawable");
        if (iconId != 0) button.setIcon(ContextCompat.getDrawable(context, iconId));
        button.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 6);
        getLinearLayout().addView(button, params);
    }

    private android.graphics.drawable.Drawable selectableBackground(Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                && value.resourceId != 0) return context.getDrawable(value.resourceId);
        return null;
    }

    private int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        return id == 0 ? fallback : ColorCompat.getThemedColor(context, id);
    }

    private int count(String key) {
        return readIds(key).size();
    }

    private Set<String> readIds(String key) {
        Set<String> result = new LinkedHashSet<>();
        String raw = settings.getString(key, "");
        if (raw == null) return result;
        for (String value : raw.split(",")) {
            if (!value.trim().isEmpty()) result.add(value.trim());
        }
        return result;
    }

    private void showIdDialog(Context context, String title, String key, String hint) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 20), 0, dp(context, 20), dp(context, 8));

        TextView listTitle = new TextView(context);
        listTitle.setText("Added IDs");
        listTitle.setTextColor(Color.WHITE);
        listTitle.setTextSize(14);
        listTitle.setPadding(0, dp(context, 4), 0, dp(context, 8));
        content.addView(listTitle);

        ScrollView listScroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        content.addView(listScroll, new LinearLayout.LayoutParams(-1, dp(context, 150)));

        EditText input = new EditText(context);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setSingleLine(true);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));

        refreshIdList(context, list, key);
        AlertDialog idDialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add", null)
                .create();
        idDialog.setOnShowListener(ignored -> {
            styleDialog(idDialog, input);
            TextView add = idDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (add != null) add.setOnClickListener(button -> {
                String id = input.getText().toString().trim();
                try {
                    Long.parseLong(id);
                    Set<String> ids = readIds(key);
                    if (!ids.add(id)) {
                        Utils.showToast("That ID is already added");
                        return;
                    }
                    settings.setString(key, join(ids));
                    BetterMessageLogger plugin = BetterMessageLogger.getInstance();
                    if (plugin != null) plugin.settingsChanged();
                    input.setText("");
                    refreshIdList(context, list, key);
                    Utils.showToast("Added " + id);
                } catch (Throwable ignoredError) {
                    Utils.showToast("Enter a valid numeric ID");
                }
            });
        });
        idDialog.show();
    }

    private void refreshIdList(Context context, LinearLayout list, String key) {
        list.removeAllViews();
        Set<String> ids = readIds(key);
        if (ids.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No IDs added");
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(15);
            empty.setPadding(0, dp(context, 8), 0, dp(context, 8));
            list.addView(empty);
            return;
        }
        for (String id : ids) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView value = new TextView(context);
            value.setText(id);
            value.setTextColor(Color.WHITE);
            value.setTextSize(15);
            value.setPadding(0, dp(context, 7), 0, dp(context, 7));
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));

            TextView remove = new TextView(context);
            remove.setText("Remove");
            remove.setTextColor(Color.rgb(242, 63, 67));
            remove.setTextSize(14);
            remove.setGravity(android.view.Gravity.CENTER);
            remove.setPadding(dp(context, 12), dp(context, 7), 0, dp(context, 7));
            remove.setOnClickListener(ignored -> {
                Set<String> current = readIds(key);
                current.remove(id);
                settings.setString(key, join(current));
                BetterMessageLogger plugin = BetterMessageLogger.getInstance();
                if (plugin != null) plugin.settingsChanged();
                refreshIdList(context, list, key);
                Utils.showToast("Removed " + id);
            });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void styleDialog(AlertDialog dialog, EditText input) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.rgb(54, 57, 63)));
        }
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title == null) title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) title.setTextColor(Color.WHITE);
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(Color.WHITE);
        if (input != null) {
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(Color.LTGRAY);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) button.setTextColor(Color.WHITE);
        }
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private String join(Set<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() != 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }
}
