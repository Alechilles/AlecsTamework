package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * Stable identity for a physical coop authority, independent of mutable coop asset evidence.
 */
public record ManagedCoopAuthorityKey(@Nonnull String worldName, int x, int y, int z) {
    public ManagedCoopAuthorityKey {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        worldName = worldName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Matches the schema-v5 location-derived identifier used by legacy reconciliation.
     */
    @Nonnull
    public String authorityId() {
        return worldName.replace("|", "||") + "|" + x + "|" + y + "|" + z;
    }

    @Nonnull
    public String slotKey(int residentSlot) {
        if (residentSlot < 0) {
            throw new IllegalArgumentException("residentSlot must not be negative");
        }
        return authorityId() + "|" + residentSlot;
    }
}
