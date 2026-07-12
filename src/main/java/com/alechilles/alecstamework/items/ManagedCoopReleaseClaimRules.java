package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;

/** Shared immutable claim checks for pre-projection rollback. */
final class ManagedCoopReleaseClaimRules {
    private ManagedCoopReleaseClaimRules() {
    }

    static boolean isUnconsumedSpawnClaim(SpawnReady claim) {
        return claim.spawnRequired()
                && claim.durableState() == OperationState.SPAWN_CLAIMED
                && claim.operationGeneration() == 1L
                && claim.actualTargetUuid() == null
                && claim.plannedTargetUuid() != null
                && claim.sourceNpcUuid() != null
                && !claim.plannedTargetUuid().equals(claim.sourceNpcUuid())
                && claim.operationId() != null && !claim.operationId().isBlank();
    }
}
