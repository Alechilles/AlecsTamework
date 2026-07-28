package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.annotation.Nonnull;

/** Produces the exact durable fingerprint used by captured-item source finalization. */
final class SpawnerSourceFingerprint {
    static final String EMPTY_AFTER_CONSUMPTION = "EMPTY";
    private SpawnerSourceFingerprint() {
    }

    @Nonnull
    static String of(@Nonnull ItemStack stack) {
        String canonical = "v2\u001f" + stack.getItemId() + "\u001f" + stack.getQuantity()
                + "\u001f" + stack.getDurability() + "\u001f" + stack.getMaxDurability()
                + "\u001f" + (stack.getMetadata() == null ? "" : stack.getMetadata().toJson());
        try {
            return "v2:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
