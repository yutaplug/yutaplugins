package com.github.yutaplug.readall;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;

import java.util.LinkedHashSet;
import java.util.Set;

/** Settings for channels and servers that should be left unread. */
public final class ReadAllSettings extends BottomSheet {
    private final SettingsAPI settings;

    public ReadAllSettings(SettingsAPI settings) {
        this.settings = settings;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 24));

        addText(context, "Use /read to clear unread notifications. Server and DM exceptions are preserved.", 14,
                Color.LTGRAY);
        addHeader(context, "Exceptions");
        addAction(context, "Server exceptions", "Keep notifications from selected servers ("
                + readIds(ReadAll.EXCLUDED_SERVERS).size() + ")", () ->
                showExceptionDialog("Server exceptions", ReadAll.EXCLUDED_SERVERS, "Server ID"));
        addAction(context, "DM exceptions", "Keep notifications from selected DMs ("
                + readIds(ReadAll.EXCLUDED_DMS).size() + ")", () ->
                showExceptionDialog("DM exceptions", ReadAll.EXCLUDED_DMS, "DM channel ID"));

        addHeader(context, "Actions");
        addAction(context, "Clear all exceptions", "Remove every saved server and DM exception", () -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Clear all exceptions?")
                    .setMessage("Notifications will be cleared for every server and DM the next time you use /read.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (dialog, which) -> {
                        settings.setString(ReadAll.EXCLUDED_SERVERS, "");
                        settings.setString(ReadAll.EXCLUDED_DMS, "");
                        Utils.showToast("All exceptions cleared");
                    })
                    .show();
        });
    }

    private void showExceptionDialog(String title, String key, String hint) {
        Context context = requireContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 20), 0, dp(context, 20), dp(context, 8));

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(context, 180)));

        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = new Button(context);
        add.setText("Add");
        inputRow.addView(add, new LinearLayout.LayoutParams(-2, -2));
        content.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("Close", null)
                .create();

        Runnable refresh = () -> refreshList(context, list, key, dialog);
        add.setOnClickListener(ignored -> {
            String id = input.getText().toString().trim();
            try {
                Long.parseLong(id);
                Set<String> ids = readIds(key);
                if (id.isEmpty() || !ids.add(id)) {
                    Utils.showToast(id.isEmpty() ? "Enter a numeric ID" : "That ID is already added");
                    return;
                }
                saveIds(key, ids);
                input.setText("");
                refresh.run();
                Utils.showToast("Added " + id);
            } catch (Throwable error) {
                Utils.showToast("Enter a numeric ID");
            }
        });
        dialog.setOnShowListener(ignored -> refresh.run());
        dialog.show();
    }

    private void refreshList(Context context, LinearLayout list, String key, AlertDialog dialog) {
        list.removeAllViews();
        Set<String> ids = readIds(key);
        if (ids.isEmpty()) {
            addTextTo(list, "No IDs added", 14, Color.LTGRAY);
            return;
        }
        for (String id : ids) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView value = new TextView(context);
            value.setText(id);
            value.setTextColor(Color.WHITE);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            value.setPadding(0, dp(context, 7), 0, dp(context, 7));
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));

            Button remove = new Button(context);
            remove.setText("Remove");
            remove.setOnClickListener(ignored -> {
                Set<String> current = readIds(key);
                current.remove(id);
                saveIds(key, current);
                refreshList(context, list, key, dialog);
                Utils.showToast("Removed " + id);
            });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private Set<String> readIds(String key) {
        Set<String> result = new LinkedHashSet<>();
        String raw = settings.getString(key, "");
        if (raw == null) return result;
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private void saveIds(String key, Set<String> ids) {
        StringBuilder result = new StringBuilder();
        for (String id : ids) {
            if (result.length() != 0) result.append(',');
            result.append(id);
        }
        settings.setString(key, result.toString());
    }

    private void addAction(Context context, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(Color.LTGRAY);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
        row.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 4);
        getLinearLayout().addView(row, params);
    }

    private void addHeader(Context context, String title) {
        TextView header = new TextView(context);
        header.setText(title.toUpperCase(java.util.Locale.ROOT));
        header.setTextColor(Color.rgb(88, 101, 242));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setPadding(0, dp(context, 22), 0, dp(context, 8));
        getLinearLayout().addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addText(Context context, String text, int size, int color) {
        addTextTo(getLinearLayout(), text, size, color);
    }

    private void addTextTo(LinearLayout parent, String text, int size, int color) {
        TextView value = new TextView(parent.getContext());
        value.setText(text);
        value.setTextColor(color);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        value.setPadding(0, dp(parent.getContext(), 4), 0, dp(parent.getContext(), 4));
        parent.addView(value, new LinearLayout.LayoutParams(-1, -2));
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
