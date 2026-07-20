package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Publishes one durably persisted deferred observation under the reconciler reload boundary. */
final class DeferredPopulationObservationApplier {
    private final Object reloadLock;
    private final Map<String, CompanionPopulationObservation> deferred;
    private final OwnerPopulationIndex owners;
    private final ClaimOccupancyIndex claims;

    DeferredPopulationObservationApplier(@Nonnull Object reloadLock,
                                         @Nonnull Map<String, CompanionPopulationObservation> deferred,
                                         @Nonnull OwnerPopulationIndex owners,
                                         @Nonnull ClaimOccupancyIndex claims) {
        this.reloadLock = Objects.requireNonNull(reloadLock, "reloadLock");
        this.deferred = Objects.requireNonNull(deferred, "deferred");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    boolean apply(@Nonnull CompanionPopulationObservation observation, long revision) {
        synchronized (reloadLock) {
            CompanionPopulationObservation expected = deferred.get(observation.profileId());
            if (!CompanionPopulationObservationStateMatcher.matches(expected, observation)) return false;
            deferred.remove(observation.profileId());
            if (owners.hasPendingTransition(observation.profileId())) return true;
            OwnerPopulationEntry owner = new OwnerPopulationEntry(
                    observation.profileId(), observation.ownerUuid(), observation.ownershipWorldName(),
                    observation.lifecycleState(), revision);
            if (!owners.tryReconcileCommittedEntry(owner)) return true;
            claims.observeMovement(new ClaimOccupancyEntry(
                    observation.profileId(), observation.ownerUuid(), observation.lifecycleState(),
                    physical(observation), revision));
            return true;
        }
    }

    private ClaimChunkCoordinate physical(CompanionPopulationObservation observation) {
        if (observation.physicalWorldName() == null) return null;
        return new ClaimChunkCoordinate(
                observation.physicalWorldName(),
                Objects.requireNonNull(observation.physicalChunkX(), "physicalChunkX"),
                Objects.requireNonNull(observation.physicalChunkZ(), "physicalChunkZ"));
    }
}
