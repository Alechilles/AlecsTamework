package com.alechilles.alecstamework.companion.coop;

import javax.annotation.Nonnull;

/** Immutable database-only request to register one normalized structural coop slot. */
public record CoopSlotRegistration(
        @Nonnull CoopSlot slot,
        long requestedAtMs
) {
    public CoopSlotRegistration {
        if (slot == null || slot.residencyRevision() != 0 || slot.reserved()) {
            throw new IllegalArgumentException(
                    "Coop registration requires a new unoccupied slot"
            );
        }
    }
}
