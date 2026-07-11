package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable committed owner state for one canonical companion profile.
 *
 * <p>A null owner represents an unowned profile and consumes no slot. A null ownership world
 * means that no per-world bucket is known, while a non-null owner still consumes a global slot.
 */
public record OwnerPopulationEntry(String profileId,
                                   UUID ownerId,
                                   String ownershipWorldName,
                                   CompanionLifecycleState lifecycleState,
                                   long revision) {

    public OwnerPopulationEntry {
        profileId = normalizeProfileId(profileId);
        ownershipWorldName = OwnerPopulationScopeKey.normalizeWorldName(ownershipWorldName);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        if (revision < 0L) {
            throw new IllegalArgumentException("Committed profile revisions cannot be negative.");
        }
    }

    public boolean consumesOwnerSlot() {
        return ownerId != null;
    }

    static String normalizeProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("A canonical profile ID is required.");
        }
        return profileId.trim();
    }
}
