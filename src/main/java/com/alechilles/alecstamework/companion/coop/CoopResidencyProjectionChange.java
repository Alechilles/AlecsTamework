package com.alechilles.alecstamework.companion.coop;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained before/after occupancy change for one monotonic coop-slot stream. */
public record CoopResidencyProjectionChange(
        @Nonnull CoopSlotKey slotKey,
        long slotRevision,
        @Nullable CoopResidency before,
        @Nullable CoopResidency after,
        long changedAtMs
) {
    public CoopResidencyProjectionChange {
        if (slotKey == null || slotRevision <= 0 || (before == null && after == null)) {
            throw new IllegalArgumentException("Valid coop projection change is required");
        }
        requireSlot(slotKey, before);
        requireSlot(slotKey, after);
    }

    private static void requireSlot(CoopSlotKey slotKey, CoopResidency residency) {
        if (residency != null && !slotKey.equals(residency.slotKey())) {
            throw new IllegalArgumentException("Coop projection residency slot mismatch");
        }
    }
}
