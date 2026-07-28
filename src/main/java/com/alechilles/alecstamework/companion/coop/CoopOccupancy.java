package com.alechilles.alecstamework.companion.coop;

import javax.annotation.Nonnull;

/** One consistent structural slot plus its current resident detail. */
public record CoopOccupancy(
        @Nonnull CoopSlot slot,
        @Nonnull CoopResidency residency
) {
    public CoopOccupancy {
        if (slot == null || residency == null
                || !slot.key().equals(residency.slotKey())) {
            throw new IllegalArgumentException("Consistent coop occupancy is required");
        }
    }
}
