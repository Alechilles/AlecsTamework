package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandHotswapHudViewModel;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import javax.annotation.Nonnull;

/** Binds the equipped flute's resolved Q/E/R assignments to the HUD controls. */
final class CommandHotswapHudBinder {
    private CommandHotswapHudBinder() {
    }

    static void bind(@Nonnull UICommandBuilder commandBuilder,
                     @Nonnull CommandHotswapHudViewModel model) {
        commandBuilder.set("#TameworkCommandHotswapControls.Visible", model.visible());
        bindSlot(commandBuilder, "Primary", model.primary());
        bindSlot(commandBuilder, "Secondary", model.secondary());
        bindSlot(commandBuilder, "Q", model.q());
        bindSlot(commandBuilder, "E", model.e());
        bindSlot(commandBuilder, "R", model.r());
    }

    private static void bindSlot(@Nonnull UICommandBuilder commandBuilder,
                                 @Nonnull String slotId,
                                 @Nonnull CommandHotswapHudViewModel.Slot slot) {
        String root = "#CommandHotswap" + slotId + "Control";
        commandBuilder.set(root + ".Visible", slot.visible());
        commandBuilder.set(root + " #Icon.Visible", slot.visible() && slot.hasIconTexturePath());
        commandBuilder.set(root + " #Icon.Background", slot.iconTexturePath());
        commandBuilder.set(root + " #FallbackGlyph.Visible", slot.visible() && !slot.hasIconTexturePath());
        commandBuilder.set(root + " #FallbackGlyph.Text", slot.fallbackGlyph());
        commandBuilder.set(root + " #Binding.Text", slot.bindingLabel());
    }
}
