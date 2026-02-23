package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Selected-command mutation result for command-item metadata updates.
 */
final class CommandSelectionResult {
    final ItemStack stack;
    final CommandEntry command;
    final boolean changed;

    CommandSelectionResult(ItemStack stack, CommandEntry command, boolean changed) {
        this.stack = stack;
        this.command = command;
        this.changed = changed;
    }

    static CommandSelectionResult none(ItemStack stack) {
        return new CommandSelectionResult(stack, null, false);
    }
}
