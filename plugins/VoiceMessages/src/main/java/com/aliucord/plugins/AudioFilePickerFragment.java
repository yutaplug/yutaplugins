package com.aliucord.plugins;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.Fragment;

public final class AudioFilePickerFragment extends Fragment {
    void open() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        try {
            startActivityForResult(intent, VoiceMessages.AUDIO_FILE_PICKER_REQUEST_CODE);
        } catch (RuntimeException e) {
            VoiceMessages plugin = VoiceMessages.getInstance();
            if (plugin != null) {
                plugin.onAudioFilePickerCancelled();
            }
            removeSelf();
            throw e;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VoiceMessages.AUDIO_FILE_PICKER_REQUEST_CODE) {
            return;
        }

        VoiceMessages plugin = VoiceMessages.getInstance();
        if (plugin != null) {
            Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) {
                plugin.onAudioFilePickerCancelled();
            } else {
                plugin.onAudioFilePicked(uri);
            }
        }
        removeSelf();
    }

    private void removeSelf() {
        if (!isAdded()) {
            return;
        }
        try {
            getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        } catch (IllegalStateException ignored) {
            // The activity may already be finishing after the picker result.
        }
    }
}
