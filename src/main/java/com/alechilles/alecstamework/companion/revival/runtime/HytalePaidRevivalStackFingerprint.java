package com.alechilles.alecstamework.companion.revival.runtime;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.annotation.Nonnull;

/** Stable exact-stack fingerprint shared by revival planning and charging. */
public final class HytalePaidRevivalStackFingerprint {
    private HytalePaidRevivalStackFingerprint() {
    }

    /** Returns the canonical fingerprint frozen into one reservation. */
    @Nonnull
    public static String of(@Nonnull ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nonempty revival stack is required"
            );
        }
        String metadata = stack.toPacket().metadata;
        String canonical = stack.getItemId() + "\u001f"
                + stack.getQuantity() + "\u001f"
                + stack.getDurability() + "\u001f"
                + stack.getMaxDurability() + "\u001f"
                + (metadata == null ? "" : metadata);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (Exception impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible
            );
        }
    }
}
