package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Result payload for link/unlink attempts.
 */
final class LinkToggleResult {
    final boolean toggled;
    final boolean linked;
    final String npcName;
    final ItemStack updatedItem;

    LinkToggleResult(boolean toggled, boolean linked, String npcName, ItemStack updatedItem) {
        this.toggled = toggled;
        this.linked = linked;
        this.npcName = npcName;
        this.updatedItem = updatedItem;
    }

    static LinkToggleResult notToggled() {
        return new LinkToggleResult(false, false, null, null);
    }
}
