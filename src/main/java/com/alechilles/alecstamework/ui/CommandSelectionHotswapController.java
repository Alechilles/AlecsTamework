package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.items.CommandHotswapAction;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore.Slot;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Owns hotswap dropdown presentation and assignment validation for the command page. */
final class CommandSelectionHotswapController {
    private final TwCommandItemConfig config;
    private final Supplier<String> languageSupplier;
    private final CommandHotswapAssignmentStore assignments = new CommandHotswapAssignmentStore();
    private Supplier<ItemStack> stackSupplier;
    private BiConsumer<Slot, String> assignmentCallback;

    CommandSelectionHotswapController(@Nonnull TwCommandItemConfig config,
                                      @Nonnull Supplier<String> languageSupplier) {
        this.config = config;
        this.languageSupplier = languageSupplier;
    }

    void configure(@Nonnull Supplier<ItemStack> stackSupplier,
                   @Nonnull BiConsumer<Slot, String> assignmentCallback) {
        this.stackSupplier = stackSupplier;
        this.assignmentCallback = assignmentCallback;
    }

    void build(@Nonnull UICommandBuilder commands) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(LocalizableString.fromString("Unassigned"), ""));
        if (!config.usesBondedCompanionRoster()) {
            entries.add(new DropdownEntryInfo(
                    LocalizableString.fromString("Cycle Group"),
                    CommandHotswapAction.CYCLE_GROUP
            ));
        }
        for (CommandSelectionOptionSource.Option option : CommandSelectionOptionSource.build(
                config, null, languageSupplier.get(), Integer.MAX_VALUE)) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(option.label()), option.id()));
        }
        commands.set("#TameworkCommandHotswapQ.Entries", entries);
        commands.set("#TameworkCommandHotswapE.Entries", entries);
        commands.set("#TameworkCommandHotswapR.Entries", entries);
        ItemStack stack = stackSupplier == null ? null : stackSupplier.get();
        commands.set("#TameworkCommandHotswapQ.Value", valueFor(stack, Slot.Q));
        commands.set("#TameworkCommandHotswapE.Value", valueFor(stack, Slot.E));
        commands.set("#TameworkCommandHotswapR.Value", valueFor(stack, Slot.R));
    }

    void apply(@Nonnull Slot slot, String commandId) {
        if (assignmentCallback == null) {
            return;
        }
        String value = commandId == null ? "" : commandId.trim();
        if (!value.isEmpty()
                && !CommandHotswapAction.isCycleGroup(value)
                && config.findCommandById(value) == null) {
            return;
        }
        assignmentCallback.accept(slot, value);
    }

    private String valueFor(ItemStack stack, @Nonnull Slot slot) {
        String value = assignments.read(stack, slot);
        return value == null ? "" : value;
    }
}
