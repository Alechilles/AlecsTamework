package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Applies canonical-reload replays and persisted revision advances to both population indexes. */
final class CompanionPopulationIndexReplayService {
    private final OwnerPopulationIndex ownerIndex;
    private final ClaimOccupancyIndex claimIndex;

    CompanionPopulationIndexReplayService(@Nonnull OwnerPopulationIndex ownerIndex,
                                          @Nonnull ClaimOccupancyIndex claimIndex) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
    }

    void replay(@Nonnull CompanionPopulationObservation observation) {
        OwnerPopulationEntry current = ownerIndex.entry(observation.profileId()).orElse(null);
        long revision = current == null ? 0L : current.revision();
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                observation.profileId(), observation.ownerUuid(), observation.ownershipWorldName(),
                observation.lifecycleState(), revision
        );
        if (!ownerIndex.tryReconcileCommittedEntry(owner)) {
            return;
        }
        ClaimChunkCoordinate physical = observation.physicalWorldName() == null
                ? null
                : new ClaimChunkCoordinate(
                        observation.physicalWorldName(),
                        observation.physicalChunkX(),
                        observation.physicalChunkZ()
                );
        claimIndex.observeMovement(new ClaimOccupancyEntry(
                observation.profileId(), observation.ownerUuid(), observation.lifecycleState(),
                physical, revision
        ));
    }

    void advanceClaimRevision(@Nonnull String profileId,
                              long expectedRevision,
                              long newRevision) {
        ClaimOccupancyEntry current = claimIndex.entry(profileId).orElse(null);
        if (current == null || current.revision() != expectedRevision) {
            return;
        }
        claimIndex.observeMovement(new ClaimOccupancyEntry(
                current.profileId(), current.ownerId(), current.lifecycleState(),
                current.physicalChunk(), newRevision
        ));
    }
}
