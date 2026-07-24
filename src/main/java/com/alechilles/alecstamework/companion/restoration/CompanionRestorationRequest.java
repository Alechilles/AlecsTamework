package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import javax.annotation.Nonnull;

/** Immutable command to restore one exact death or lost snapshot under a pre-leased alias. */
public record CompanionRestorationRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull LifecycleState sourceState,
        @Nonnull CompanionSnapshot sourceSnapshot,
        @Nonnull RestorationProjection projection,
        @Nonnull NpcAlias targetAlias,
        @Nonnull CompanionSpawnPlacement placement,
        @Nonnull String spawnReceiptKey,
        long requestedAtMs
) {
    public CompanionRestorationRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || sourceState == null || sourceSnapshot == null
                || projection == null || targetAlias == null
                || placement == null) {
            throw new IllegalArgumentException("Complete companion restoration is required");
        }
        spawnReceiptKey = requireText(spawnReceiptKey, "Restoration spawn receipt");
        if (sourceState != LifecycleState.DEAD_REVIVABLE
                && sourceState != LifecycleState.LOST) {
            throw new IllegalArgumentException(
                    "Restoration source must be death or lost"
            );
        }
        if (!profileId.equals(sourceSnapshot.profileId())
                || !sourceSnapshot.current()
                || sourceSnapshot.sourceLifecycleRevision()
                .compareTo(expectedLifecycleRevision) > 0
                || !expectedSnapshotKind(sourceState)
                .equals(sourceSnapshot.kind())) {
            throw new IllegalArgumentException(
                    "Restoration snapshot must be the exact current source artifact"
            );
        }
        if (!CompanionFullStateProjection.KIND.equals(
                projection.fullState().kind()
        )
                || projection.fullState().payloadVersion()
                != CompanionFullStateProjection.VERSION
                || projection.sourceAlias().equals(targetAlias)) {
            throw new IllegalArgumentException(
                    "Restoration projection must be complete modern state for a distinct source alias"
            );
        }
    }

    /** Returns the canonical target world without storing a second placement authority. */
    @Nonnull
    public String targetWorldKey() {
        return placement.worldKey();
    }

    private static com.alechilles.alecstamework.companion.snapshot.SnapshotKind
    expectedSnapshotKind(LifecycleState sourceState) {
        return sourceState == LifecycleState.DEAD_REVIVABLE
                ? DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind()
                : DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL.snapshotKind();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
