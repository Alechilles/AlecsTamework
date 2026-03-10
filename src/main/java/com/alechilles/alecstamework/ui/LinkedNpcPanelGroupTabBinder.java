package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Locale;

/**
 * Binds the per-card group tab visual (name + color) for linked companions.
 */
final class LinkedNpcPanelGroupTabBinder {
    private static final String DEFAULT_GROUP_COLOR = "#4b657f";

    private LinkedNpcPanelGroupTabBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     String tabSelector,
                     String colorSwatchSelector,
                     LinkedNpcEntry entry,
                     boolean pendingUnlink) {
        if (commandBuilder == null || tabSelector == null || colorSwatchSelector == null || entry == null) {
            return;
        }
        boolean show = !pendingUnlink;
        commandBuilder.set(tabSelector + ".Visible", show);
        if (!show) {
            return;
        }
        String color = normalizeColor(entry.groupColorHex());
        commandBuilder.set(colorSwatchSelector + ".Background", color);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
