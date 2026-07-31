package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Atomic persistence boundary for bonded projection lifecycle operations. */
public interface BondedCompanionProjectionDurability {
    /** Atomically authors ACTIVE, the lease, and its spawn-recovery intent. */
    boolean beginSummon(
            @Nonnull BondedCompanionProjectionService.SummonRequest request,
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent
                    recovery);

    /** Atomically commits the exact NPC UUID and clears spawn recovery. */
    boolean confirmSpawn(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull UUID spawnedNpcUuid);

    /** Atomically records a failed spawn and every required cleanup intent. */
    boolean failSpawnAndEnqueueCleanup(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull List<BondedCompanionProjectionCleanupService.CleanupIntent>
                    cleanups,
            @Nonnull String reason);

    /** Returns a terminal exact-store result before projection access. */
    @Nonnull
    BondedCompanionProjectionService.StoreDurabilityResult findStoreResult(
            @Nonnull BondedCompanionOperation operation);

    /** Atomically stores the snapshot and enqueues its exact cleanup. */
    @Nonnull
    BondedCompanionProjectionService.StoreDurabilityResult
            storeAndEnqueueCleanup(
                    @Nonnull BondedCompanionProjectionService.StoreRequest
                            request,
                    @Nonnull BondedCompanionProjectionStorePlanner.StorePlan
                            plan,
                    @Nonnull BondedCompanionProjectionCleanupService
                            .CleanupIntent cleanup);

    /** Atomically returns a non-death exit to STORED with exact cleanup. */
    boolean reconcileStored(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull BondedCompanionProjectionStorePlanner.StorePlan plan,
            @Nonnull List<BondedCompanionProjectionCleanupService.CleanupIntent>
                    cleanups,
            @Nonnull String reason);

    /** Atomically authors DEAD for the confirmed exact projection death. */
    boolean confirmDeath(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull BondedCompanionProjectionStorePlanner.StorePlan plan,
            long diedAtMs);
}
