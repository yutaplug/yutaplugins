package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class WaveFormView extends android.view.View {
    private static final int WAVEFORM_COLOR = Color.rgb(185, 187, 190);
    private final List<Integer> waves = new ArrayList<>();

    public WaveFormView(Context context) {
        super(context);
    }

    public synchronized void addWave(int wave) {
        waves.add(0, wave);
        if (waves.size() > 300) {
            waves.remove(waves.size() - 1);
        }
    }

    public synchronized void reset() {
        waves.clear();
    }

    public synchronized String getWaveForm() {
        if (waves.isEmpty()) {
            return new String(Base64.encode(new byte[]{1}, Base64.NO_WRAP), StandardCharsets.UTF_8);
        }

        boolean quiet = true;
        for (int wave : waves) {
            if (wave > 128) {
                quiet = false;
                break;
            }
        }

        byte[] bytes = new byte[waves.size()];
        for (int i = 0; i < waves.size(); i++) {
            bytes[i] = (byte) (quiet ? waves.get(i) * 2 : waves.get(i));
        }
        return new String(Base64.encode(bytes, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        List<Integer> currentWaves;
        synchronized (this) {
            currentWaves = new ArrayList<>(waves);
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(WAVEFORM_COLOR);
        int width = getWidth();
        int height = getHeight();
        int center = height / 2;
        int density = (int) getResources().getDisplayMetrics().density;
        int step = Math.max(1, 5 * density);
        int barWidth = Math.max(1, 3 * density);

        for (int i = 0; i < currentWaves.size(); i++) {
            int left = width - step * (i + 1);
            if (left < 0) {
                break;
            }
            int lineHeight = Math.max(density, currentWaves.get(i) / 3 * density);
            canvas.drawRoundRect(
                    left,
                    center - lineHeight,
                    left + barWidth,
                    center + lineHeight,
                    barWidth,
                    barWidth,
                    paint
            );
        }
    }
}
