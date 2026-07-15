package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Produces the exact durable fingerprint used by captured-item source finalization. */
final class SpawnerSourceFingerprint {
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
}
