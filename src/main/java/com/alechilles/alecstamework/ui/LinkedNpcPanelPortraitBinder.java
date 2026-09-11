package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;

/** Reuses the capture image without creating an inventory item or another texture asset. */
final class LinkedNpcPanelPortraitBinder {
    private LinkedNpcPanelPortraitBinder() {
    }

    static void bind(UICommandBuilder commands, String card, LinkedNpcEntry entry) {
        String icon = entry.portraitIcon();
        commands.set(card + " #Portrait.Visible", !icon.isBlank());
        commands.set(card + " #Portrait.Slots", icon.isBlank() ? List.<ItemGridSlot>of()
                : List.of(new ItemGridSlot().setIcon(Value.of(new PatchStyle(Value.of(icon))))));
    }
}
