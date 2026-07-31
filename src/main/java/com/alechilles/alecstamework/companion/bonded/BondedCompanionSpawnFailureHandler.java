package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists and executes exact cleanup for one failed summon world effect. */
final class BondedCompanionSpawnFailureHandler {
    private final BondedCompanionProjectionDurability durability;
    private final BondedCompanionProjectionCleanupService cleanup;
    private final BondedCompanionCleanupIntentFactory cleanupIntents =
            new BondedCompanionCleanupIntentFactory();

    BondedCompanionSpawnFailureHandler(
            BondedCompanionProjectionDurability durability,
            BondedCompanionProjectionCleanupService cleanup
    ) {
        this.durability = Objects.requireNonNull(durability, "durability");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    BondedCompanionProjectionService.SummonResult rollback(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionService.SpawnResult spawned,
            String reason,
            long nowMs
    ) {
        List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups =
                cleanupIntents(lease, spawned, reason, nowMs);
        boolean stored;
        try {
            stored = durability.failSpawnAndEnqueueCleanup(
                    lease, cleanups, reason);
        } catch (RuntimeException failure) {
            stored = false;
        }
        if (!stored) {
            return new BondedCompanionProjectionService.SummonResult(
                    BondedCompanionProjectionService.SummonStatus
                            .SPAWN_ROLLBACK_PENDING,
                    lease);
        }
        cleanups.forEach(cleanup::recover);
        return new BondedCompanionProjectionService.SummonResult(
                BondedCompanionProjectionService.SummonStatus
                        .SPAWN_FAILED_STORED,
                lease);
    }

    private List<BondedCompanionProjectionCleanupService.CleanupIntent>
    cleanupIntents(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionService.SpawnResult spawned,
            String reason,
            long nowMs
    ) {
        ArrayList<BondedCompanionProjectionCleanupService.CleanupIntent> result =
                new ArrayList<>();
        UUID observedUuid = spawned == null ? null : spawned.npcUuid();
        if (observedUuid != null) {
            result.add(cleanupIntents.projection(
                    lease, observedUuid, lease.worldKey(), reason, nowMs));
        }
        if (!lease.liveNpcUuid().equals(observedUuid)) {
            result.add(cleanupIntents.projection(
                    lease, lease.liveNpcUuid(), lease.worldKey(), reason, nowMs));
        }
        return List.copyOf(result);
    }
}
