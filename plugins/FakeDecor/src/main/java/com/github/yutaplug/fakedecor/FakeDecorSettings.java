package com.github.yutaplug.fakedecor;

import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.views.TextInput;
import com.aliucord.widgets.BottomSheet;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FakeDecorSettings extends BottomSheet {
    private static final int PICK_DECORATION = 4831;
    private final SettingsAPI settings;
    private final FakeDecor plugin;
    private EditText assetInput;
    private TextView authorizationStatus;

    public FakeDecorSettings(SettingsAPI settings, FakeDecor plugin) {
        this.settings = settings;
        this.plugin = plugin;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        TextView description = label("Choose a Decor preset or enter a decoration hash/asset. "
                + "Animated assets use the a_ prefix. The selection is stored per account.");
        addView(description);

        TextInput asset = new TextInput(requireContext(), "Decoration hash or asset",
                plugin.getSelectedAsset());
        assetInput = asset.getEditText();
        assetInput.setSingleLine(true);
        addView(asset);

        MaterialButton presets = button("Browse Decor presets");
        presets.setOnClickListener(v -> plugin.fetchPresets(this));
        addView(presets);

        MaterialButton apply = button("Apply locally");
        apply.setOnClickListener(v -> {
            plugin.setSelectedAsset(assetInput.getText().toString());
            Utils.showToast(assetInput.getText().toString().trim().isEmpty()
                    ? "Decoration removed" : "Decoration applied; reopen the current view");
        });
        addView(apply);

        authorizationStatus = label("");
        addView(authorizationStatus);
        refreshAuthState();

        MaterialButton authorize = button("Authorize with Discord");
        authorize.setOnClickListener(v -> plugin.authorizeDecor(requireContext(), this));
        addView(authorize);

        MaterialButton finishAuthorization = button("Finish authorization");
        finishAuthorization.setOnClickListener(v -> plugin.finishBrowserAuthorization(
                requireContext(), this));
        addView(finishAuthorization);

        MaterialButton disconnect = button("Disconnect Decor");
        disconnect.setOnClickListener(v -> {
            plugin.disconnectDecor();
            refreshAuthState();
        });
        addView(disconnect);

        MaterialButton sync = button("Sync from Decor");
        sync.setOnClickListener(v -> plugin.refreshOwnDecoration());
        addView(sync);

        MaterialButton upload = button("Sync selection to Decor");
        upload.setOnClickListener(v -> plugin.applyToDecorService());
        addView(upload);

        MaterialButton create = button("Upload custom decoration");
        create.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_DECORATION);
        });
        addView(create);

        TextView note = label("Tap Authorize with Discord, complete it in your system browser, "
                + "copy the returned token, then tap Finish authorization. Local decorations "
                + "and preset browsing do not require an account.");
        addView(note);
    }

    void refreshAuthState() {
        if (authorizationStatus == null) return;
        authorizationStatus.setText(plugin.isAuthorized()
                ? "Decor is authorized for this Discord account."
                : "Decor is not authorized.");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DECORATION && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            plugin.uploadDecoration(requireContext(), data.getData());
        }
    }

    private TextView label(String text) {
        TextView result = new TextView(requireContext());
        result.setText(text);
        result.setTextColor(Color.LTGRAY);
        result.setPadding(0, 12, 0, 12);
        return result;
    }

    private MaterialButton button(String text) {
        MaterialButton result = new MaterialButton(requireContext());
        result.setText(text);
        return result;
    }

    public void showPresets(List<?> presets) {
        List<String> labels = new ArrayList<>();
        List<String> assets = new ArrayList<>();
        for (Object rawPreset : presets) {
            if (!(rawPreset instanceof Map)) continue;
            Map<?, ?> preset = (Map<?, ?>) rawPreset;
            Object rawDecorations = preset.get("decorations");
            if (!(rawDecorations instanceof List)) continue;
            String presetName = String.valueOf(preset.get("name"));
            for (Object rawDecoration : (List<?>) rawDecorations) {
                if (!(rawDecoration instanceof Map)) continue;
                Map<?, ?> decoration = (Map<?, ?>) rawDecoration;
                Object hash = decoration.get("hash");
                if (hash == null) continue;
                boolean animated = Boolean.TRUE.equals(decoration.get("animated"));
                String asset = (animated ? "a_" : "") + hash;
                String alt = decoration.get("alt") == null ? asset : String.valueOf(decoration.get("alt"));
                labels.add(presetName + ": " + alt);
                assets.add(asset);
            }
        }
        if (labels.isEmpty()) {
            Utils.showToast("No Decor presets found");
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                requireContext(), android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(Color.WHITE);
                return row;
            }
        };

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Decor presets")
                .setAdapter(adapter, (dialogInterface, which) -> {
                    assetInput.setText(assets.get(which));
                    assetInput.setSelection(assetInput.length());
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView cancel = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.setTextColor(Color.WHITE);
        });
        dialog.show();
    }
}
