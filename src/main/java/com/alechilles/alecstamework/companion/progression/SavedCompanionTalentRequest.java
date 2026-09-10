package com.alechilles.alecstamework.companion.progression;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, fenced request to update talents held in an offline companion snapshot. */
public record SavedCompanionTalentRequest(
        @Nonnull ProfileId profileId,
        @Nonnull OwnerId ownerId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull SnapshotId expectedSnapshotId,
        @Nonnull Sha256Hash expectedSnapshotHash,
        @Nonnull Action action,
        @Nullable String talentId,
        @Nonnull String expectedTalentConfigId,
        long expectedAllocationRevision,
        long requestedAtMs
) {
    public SavedCompanionTalentRequest {
        if (profileId == null || ownerId == null
                || expectedLifecycleRevision == null
                || expectedSnapshotId == null || expectedSnapshotHash == null
                || action == null || expectedAllocationRevision < 0) {
            throw new IllegalArgumentException(
                    "Saved companion talent request evidence is required"
            );
        }
        talentId = normalize(talentId);
        expectedTalentConfigId = requireText(
                expectedTalentConfigId, "Expected talent config ID"
        );
        if (action == Action.PURCHASE && talentId == null) {
            throw new IllegalArgumentException(
                    "A talent ID is required to purchase a talent"
            );
        }
        if (action == Action.RESET && talentId != null) {
            throw new IllegalArgumentException(
                    "A talent ID is not valid when resetting talents"
            );
        }
    }

    private static String requireText(String value, String label) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum Action {
        PURCHASE,
        RESET
    }
}
