package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Builds bounded, exact cleanup intents for bonded projection recovery. */
final class BondedCompanionCleanupIntentFactory {
    private static final long CLEANUP_RETENTION_MS = 300_000L;

    @Nonnull
    BondedCompanionProjectionCleanupService.CleanupIntent projection(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull String reason,
            long nowMs
    ) {
        return projection(
                lease, lease.liveNpcUuid(), lease.worldKey(), reason, nowMs
        );
    }

    @Nonnull
    BondedCompanionProjectionCleanupService.CleanupIntent projection(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull UUID targetNpcUuid,
            @Nonnull String worldKey,
            @Nonnull String reason,
            long nowMs
    ) {
        return new BondedCompanionProjectionCleanupService.CleanupIntent(
                cleanupId(lease, targetNpcUuid, reason),
                lease.ownerUuid(), lease.rosterId(), lease.profileId(),
                lease.leaseToken(),
                BondedCompanionProjectionCleanupService.Target.PROJECTION,
                targetNpcUuid, worldKey, reason, nowMs,
                retainedUntil(nowMs)
        );
    }

    @Nonnull
    List<BondedCompanionProjectionCleanupService.CleanupIntent> projections(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull List<BondedCompanionProjectionValidator.Projection> projections,
            @Nonnull String reason,
            long observedAtMs
    ) {
        ArrayList<BondedCompanionProjectionCleanupService.CleanupIntent> result =
                new ArrayList<>();
        for (var projection : projections) {
            result.add(projection(
                    lease, projection.npcUuid(), projection.worldKey(), reason,
                    observedAtMs
            ));
        }
        return List.copyOf(result);
    }

    private String cleanupId(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            UUID npcUuid,
            String reason
    ) {
        return lease.profileId() + ":" + lease.leaseToken() + ":"
                + npcUuid + ":" + reason;
    }

    private long retainedUntil(long nowMs) {
        long retained = Math.addExact(nowMs, CLEANUP_RETENTION_MS);
        return retained == 0L ? 1L : retained;
    }
}
