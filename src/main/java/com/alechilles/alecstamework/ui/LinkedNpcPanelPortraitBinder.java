package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import javax.annotation.Nullable;

/** Reuses registered item icons without unsupported Custom UI item metadata. */
final class LinkedNpcPanelPortraitBinder {
    private LinkedNpcPanelPortraitBinder() {
    }

    static void bind(UICommandBuilder commands, String card, LinkedNpcEntry entry) {
        bindIcon(commands, card + " #Portrait", entry.portraitIcon());
    }

    static void bindIcon(UICommandBuilder commands, String selector, @Nullable String icon) {
        ItemGridSlot slot = portraitSlot(icon);
        commands.set(selector + ".Visible", slot != null);
        commands.set(selector + ".Slots", slot == null ? List.<ItemGridSlot>of() : List.of(slot));
    }

    @Nullable
    private static ItemGridSlot portraitSlot(@Nullable String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        // Custom UI cannot deserialize CapturedEntity metadata. Only reference an existing
        // item whose normal icon is the selected portrait; never substitute the carrier icon.
        // This lookup runs when a card is bound or its appearance changes, not every refresh.
        for (var asset : Item.getAssetMap().getAssetMap().entrySet()) {
            if (!icon.equals(asset.getValue().getIcon())) {
                continue;
            }
            ItemGridSlot slot = new ItemGridSlot(new ItemStack(asset.getKey(), 1));
            slot.setActivatable(false);
            slot.setSkipItemQualityBackground(true);
            return slot;
        }
        return null;
    }
}
