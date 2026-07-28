package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import javax.annotation.Nonnull;

/** Immutable command to restore one exact coop residency under a pre-leased live alias. */
public record CompanionCoopReleaseRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull CoopResidency sourceResidency,
        @Nonnull CompanionSnapshot sourceSnapshot,
        @Nonnull NpcAlias targetAlias,
        @Nonnull CompanionSpawnPlacement placement,
        @Nonnull String spawnReceiptKey,
        long requestedAtMs
) {
    public CompanionCoopReleaseRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || sourceResidency == null || sourceSnapshot == null
                || targetAlias == null || placement == null) {
            throw new IllegalArgumentException("Complete coop release request is required");
        }
        spawnReceiptKey = requireText(spawnReceiptKey, "Coop release spawn receipt");
        if (!profileId.equals(sourceResidency.profileId())
                || !profileId.equals(sourceSnapshot.profileId())
                || !sourceResidency.snapshotId().equals(
                sourceSnapshot.snapshotId()
        )
                || !CompanionCoopCaptureRequest.SNAPSHOT_KIND.equals(
                sourceSnapshot.kind()
        )
                || !sourceSnapshot.current()
                || sourceSnapshot.sourceLifecycleRevision()
                .compareTo(expectedLifecycleRevision) > 0) {
            throw new IllegalArgumentException(
                    "Coop release must reference the exact current residency snapshot"
            );
        }
    }

    /** Returns the canonical target world without storing a second placement authority. */
    @Nonnull
    public String targetWorldKey() {
        return placement.worldKey();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
