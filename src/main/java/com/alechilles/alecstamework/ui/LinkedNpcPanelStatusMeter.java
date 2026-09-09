package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Geometry and display helpers for the horizontal status meters on generic
 * linked-panel cards.
 */
final class LinkedNpcPanelStatusMeter {
    private static final int FILL_LEFT = 46;
    private static final int FILL_TOP = 16;
    private static final int FILL_MAX_WIDTH = 130;
    private static final int FILL_HEIGHT = 6;
    private static final int THRESHOLD_LEFT = 46;
    private static final int THRESHOLD_TOP = 14;
    private static final int THRESHOLD_MAX_OFFSET = 128;

    private LinkedNpcPanelStatusMeter() {
    }

    static Anchor buildFillAnchor(double ratio) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(FILL_LEFT));
        anchor.setTop(Value.of(FILL_TOP));
        anchor.setWidth(Value.of((int) Math.round(clamp(ratio) * FILL_MAX_WIDTH)));
        anchor.setHeight(Value.of(FILL_HEIGHT));
        return anchor;
    }

    static Anchor buildThresholdAnchor(double ratio) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(THRESHOLD_LEFT + (int) Math.round(clamp(ratio) * THRESHOLD_MAX_OFFSET)));
        anchor.setTop(Value.of(THRESHOLD_TOP));
        anchor.setWidth(Value.of(2));
        anchor.setHeight(Value.of(10));
        return anchor;
    }

    static String formatRemainingClock(long remainingMs) {
        long totalSeconds = remainingMs > 0L ? 1L + ((remainingMs - 1L) / 1000L) : 0L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
