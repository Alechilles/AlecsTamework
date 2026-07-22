package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Produces the exact durable fingerprint used by captured-item source finalization. */
final class SpawnerSourceFingerprint {
    static final String EMPTY_AFTER_CONSUMPTION = "EMPTY";
    private SpawnerSourceFingerprint() {
    }

    @Nonnull
    static String of(@Nonnull ItemStack stack) {
        UUID target = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING
        );
        String profile = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING
        );
        return stack.getItemId() + "|" + String.valueOf(target) + "|"
                + String.valueOf(profile) + "|"
                + Integer.toUnsignedString(stack.hashCode(), 16);
    }

    @Nonnull
    static String afterConsumingOne(@Nonnull ItemStack stack) {
        if (stack.getQuantity() <= 0) {
            throw new IllegalArgumentException("Source stack must contain at least one item");
        }
        return stack.getQuantity() == 1
                ? EMPTY_AFTER_CONSUMPTION
                : of(stack.withQuantity(stack.getQuantity() - 1));
    }
}
