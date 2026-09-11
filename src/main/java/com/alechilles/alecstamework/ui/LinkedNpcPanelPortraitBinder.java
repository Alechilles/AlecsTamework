package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.util.List;

/** Reuses the item-icon renderer without copying capture images into the custom UI atlas. */
final class LinkedNpcPanelPortraitBinder {
    private LinkedNpcPanelPortraitBinder() {
    }

    static void bind(UICommandBuilder commands, String card, LinkedNpcEntry entry) {
        String icon = entry.portraitIcon();
        commands.set(card + " #Portrait.Visible", !icon.isBlank());
        commands.set(card + " #Portrait.Slots", icon.isBlank() ? List.<ItemGridSlot>of()
                : List.of(portraitSlot(icon)));
    }

    private static ItemGridSlot portraitSlot(String icon) {
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setIconPath(icon);
        metadata.setFullItemIcon(icon);
        // Any registered item can carry FullItemIcon. This display-only stack never enters
        // an inventory; the native renderer uses the animal image instead of the carrier.
        ItemStack carrier = new ItemStack("Soil_Dirt", 1)
                .withMetadata(CapturedNPCMetadata.KEYED_CODEC, metadata);
        ItemGridSlot slot = new ItemGridSlot(carrier);
        slot.setActivatable(false);
        slot.setSkipItemQualityBackground(true);
        return slot;
    }
}
