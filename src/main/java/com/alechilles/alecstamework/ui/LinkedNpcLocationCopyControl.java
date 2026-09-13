package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.UUID;

/** Page-owned toggle for selecting raw coordinates; it never accesses the clipboard. */
final class LinkedNpcLocationCopyControl {
    static final String PREFIX = "location-copy:";
    private UUID copying;

    void toggle(String command, LinkedNpcEntry[] entries, UICommandBuilder commands) {
        UUID id = CommandUiIdParser.parseNpcUuid(command, PREFIX);
        if (id == null) return;
        for (LinkedNpcEntry entry : entries) {
            if (id.equals(entry.npcUuid()) && !entry.loaded() && !entry.dead() && !entry.lost()
                    && !entry.location().coordinates().isBlank()) {
                copying = id.equals(copying) ? null : id;
                for (int i = 0; i < entries.length; i++) bind(commands, i, entries[i]);
                return;
            }
        }
    }

    void bind(UICommandBuilder commands, int index, LinkedNpcEntry entry) {
        String selector = "#TameworkLinkedPanelList[" + index + "] #InlineLocation";
        boolean hasCoordinates = !entry.location().coordinates().isBlank();
        boolean editing = hasCoordinates && entry.npcUuid().equals(copying);
        commands.set(selector + " #Coordinates.Visible", editing);
        commands.set(selector + " #CoordinateLabel.Visible", hasCoordinates && !editing);
        commands.set(selector + " #CopyHint.Visible", editing);
        if (editing) commands.set(selector + " #Coordinates.Value", entry.location().coordinates());
    }

    static String labeledCoordinates(String coordinates) {
        String[] axes = coordinates.split(",\\s*");
        return axes.length == 3 ? "X: " + axes[0].trim() + "   Y: " + axes[1].trim()
                + "   Z: " + axes[2].trim() : coordinates;
    }
}
