package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Result payload for link/unlink attempts.
 */
final class LinkToggleResult {
    final boolean toggled;
    final boolean linked;
    final boolean active;
    final boolean pending;
    final String npcName;
    final ItemStack updatedItem;

    LinkToggleResult(boolean toggled, boolean linked, boolean active, String npcName, ItemStack updatedItem) {
        this(toggled, linked, active, false, npcName, updatedItem);
    }

    private LinkToggleResult(boolean toggled,
                             boolean linked,
                             boolean active,
                             boolean pending,
                             String npcName,
                             ItemStack updatedItem) {
        this.toggled = toggled;
        this.linked = linked;
        this.active = active;
        this.pending = pending;
        this.npcName = npcName;
        this.updatedItem = updatedItem;
    }

    static LinkToggleResult notToggled() {
        return new LinkToggleResult(false, false, false, null, null);
    }

    static LinkToggleResult pending() {
        return new LinkToggleResult(false, false, false, true, null, null);
    }
}
