package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Locale;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Applies the shared group marker to inline assignment selectors.
 */
final class LinkedNpcPanelGroupTabBinder {
    private static final String DEFAULT_GROUP_COLOR = "#454e48";

    private LinkedNpcPanelGroupTabBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     String tabSelector,
                     LinkedNpcEntry entry) {
        if (commandBuilder == null || tabSelector == null || entry == null) {
            return;
        }
        String color = normalizeColor(entry.groupColorHex());
        commandBuilder.setObject(tabSelector + "Marker.Background", markerStyle(color, entry.groupId() == null || entry.groupId().isBlank()));
    }

    static PatchStyle markerStyle(String color, boolean hollow) {
        return new PatchStyle(Value.of("Tamework/PanelControls/GroupDiamond"
                + (hollow ? "Outline" : "") + ".png")).setColor(Value.of(color));
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
