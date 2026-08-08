package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;

/** Stores the three player-selected command IDs on one command-item stack. */
public final class CommandHotswapAssignmentStore {
    public enum Slot { Q, E, R }

    @Nullable
    public String read(@Nullable ItemStack stack, @Nullable Slot slot) {
        if (stack == null || stack.isEmpty() || slot == null) return null;
        String value = stack.getFromMetadataOrNull(key(slot), Codec.STRING);
        return value == null || value.isBlank() ? null : value;
    }

    @Nullable
    public ItemStack write(@Nullable ItemStack stack, @Nullable Slot slot, @Nullable String commandId) {
        if (stack == null || stack.isEmpty() || slot == null) return stack;
        String normalized = commandId == null ? null : commandId.trim();
        return stack.withMetadata(key(slot), Codec.STRING,
                normalized == null || normalized.isEmpty() ? null : normalized);
    }

    private String key(Slot slot) {
        return switch (slot) {
            case Q -> TameworkMetadataKeys.COMMAND_HOTSWAP_Q_ID;
            case E -> TameworkMetadataKeys.COMMAND_HOTSWAP_E_ID;
            case R -> TameworkMetadataKeys.COMMAND_HOTSWAP_R_ID;
        };
    }
}
