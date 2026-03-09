package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Locale;

/**
 * Binds the per-card group tab visual (name + color) for linked companions.
 */
final class LinkedNpcPanelGroupTabBinder {
    private static final String DEFAULT_GROUP_COLOR = "#4b657f";
    private static final String LIGHT_TEXT_COLOR = "#eff4fa";
    private static final String DARK_TEXT_COLOR = "#0b1320";
    private static final String UNGROUPED_TEXT = "Group";

    private LinkedNpcPanelGroupTabBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     String tabSelector,
                     String labelSelector,
                     LinkedNpcEntry entry,
                     boolean pendingUnlink) {
        if (commandBuilder == null || tabSelector == null || labelSelector == null || entry == null) {
            return;
        }
        boolean show = entry.linked() && !pendingUnlink && !isBlank(entry.groupName());
        commandBuilder.set(tabSelector + ".Visible", show);
        if (!show) {
            return;
        }
        String color = normalizeColor(entry.groupColorHex());
        String text = normalizeLabel(entry.groupName());
        commandBuilder.set(tabSelector + ".Background", color);
        commandBuilder.set(labelSelector + ".Text", text);
        commandBuilder.set(labelSelector + ".TextColor", resolveTextColor(color));
    }

    private static String normalizeColor(String raw) {
        if (isBlank(raw)) {
            return DEFAULT_GROUP_COLOR;
        }
        String trimmed = raw.trim();
        if (!trimmed.matches("^#[0-9A-Fa-f]{6}$")) {
            return DEFAULT_GROUP_COLOR;
        }
        return "#" + trimmed.substring(1).toUpperCase(Locale.ROOT);
    }

    private static String normalizeLabel(String raw) {
        if (isBlank(raw)) {
            return UNGROUPED_TEXT;
        }
        return raw.trim();
    }

    private static String resolveTextColor(String hexColor) {
        int red = parseChannel(hexColor, 1);
        int green = parseChannel(hexColor, 3);
        int blue = parseChannel(hexColor, 5);
        int perceivedBrightness = ((red * 299) + (green * 587) + (blue * 114)) / 1000;
        return perceivedBrightness >= 150 ? DARK_TEXT_COLOR : LIGHT_TEXT_COLOR;
    }

    private static int parseChannel(String hex, int startInclusive) {
        try {
            return Integer.parseInt(hex.substring(startInclusive, startInclusive + 2), 16);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
