package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact, queryable reason why a coop capture or release cannot acquire its slot fence. */
public record CoopConflictDiagnostic(
        @Nonnull Reason reason,
        @Nonnull CoopSlotKey requestedSlot,
        @Nonnull ProfileId requestedProfile,
        @Nullable CoopSlot slot,
        @Nullable CoopResidency slotResidency,
        @Nullable CoopResidency profileResidency
) {
    public CoopConflictDiagnostic {
        if (reason == null || requestedSlot == null || requestedProfile == null) {
            throw new IllegalArgumentException("Complete coop conflict diagnostic is required");
        }
    }

    public enum Reason {
        NONE,
        SLOT_MISSING,
        SLOT_RESERVED,
        SLOT_OCCUPIED,
        SLOT_EMPTY,
        PROFILE_ALREADY_RESIDENT,
        RESIDENT_MISMATCH
    }
}
