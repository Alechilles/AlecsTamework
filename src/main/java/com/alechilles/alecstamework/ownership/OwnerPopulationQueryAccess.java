package com.alechilles.alecstamework.ownership;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Lock-consistent read and diagnostics facade over owner population index state. */
final class OwnerPopulationQueryAccess {
    private final ReentrantLock lock;
    private final Map<String, OwnerPopulationEntry> entries;
    private final Map<OwnerPopulationScopeKey, Long> committed;
    private final Map<OwnerPopulationScopeKey, Long> pending;
    private final Map<UUID, OwnerPopulationPendingTransition> pendingByToken;
    private final Map<String, UUID> pendingTokenByProfile;
    private final OwnerPopulationReadinessState readiness;
    private final OwnerPopulationMetrics metrics;
    private final BooleanSupplier canonicalReloadInProgress;
    private final OwnerPopulationReservationBook reservations;

    OwnerPopulationQueryAccess(ReentrantLock lock,
                               Map<String, OwnerPopulationEntry> entries,
                               Map<OwnerPopulationScopeKey, Long> committed,
                               Map<OwnerPopulationScopeKey, Long> pending,
                               Map<UUID, OwnerPopulationPendingTransition> pendingByToken,
                               Map<String, UUID> pendingTokenByProfile,
                               OwnerPopulationReadinessState readiness,
                               OwnerPopulationMetrics metrics,
                               BooleanSupplier canonicalReloadInProgress,
                               OwnerPopulationReservationBook reservations) {
        this.lock = lock;
        this.entries = entries;
        this.committed = committed;
        this.pending = pending;
        this.pendingByToken = pendingByToken;
        this.pendingTokenByProfile = pendingTokenByProfile;
        this.readiness = readiness;
        this.metrics = metrics;
        this.canonicalReloadInProgress = canonicalReloadInProgress;
        this.reservations = reservations;
    }

    Optional<OwnerPopulationEntry> entry(String profileId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return Optional.ofNullable(entries.get(normalized));
        } finally {
            lock.unlock();
        }
    }

    OwnerPopulationProfileStateSnapshot profileStateSnapshot(String profileId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return new OwnerPopulationProfileStateSnapshot(
                    readiness.overall(), canonicalReloadInProgress.getAsBoolean(),
                    pendingTokenByProfile.containsKey(normalized),
                    Optional.ofNullable(entries.get(normalized)));
        } finally {
            lock.unlock();
        }
    }

    boolean hasApplyingOwnerClear(String profileId, UUID observedOwnerId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return reservations.hasApplyingOwnerClear(normalized, observedOwnerId);
        } finally {
            lock.unlock();
        }
    }

    OwnerPopulationCounts counts(UUID ownerId, String worldName) {
        Objects.requireNonNull(ownerId, "ownerId");
        String normalizedWorld = OwnerPopulationScopeKey.normalizeWorldName(worldName);
        lock.lock();
        try {
            OwnerPopulationScopeKey global = OwnerPopulationScopeKey.global(ownerId);
            long worldCommitted = 0L;
            long worldPending = 0L;
            if (normalizedWorld != null) {
                OwnerPopulationScopeKey world = OwnerPopulationScopeKey.perWorld(ownerId, normalizedWorld);
                worldCommitted = OwnerPopulationCountOps.count(committed, world);
                worldPending = OwnerPopulationCountOps.count(pending, world);
            }
            return new OwnerPopulationCounts(
                    OwnerPopulationCountOps.count(committed, global),
                    OwnerPopulationCountOps.count(pending, global),
                    worldCommitted,
                    worldPending
            );
        } finally {
            lock.unlock();
        }
    }

    int pendingReservationCount() {
        lock.lock();
        try {
            return pendingByToken.size();
        } finally {
            lock.unlock();
        }
    }

    OwnerPopulationMetrics.Snapshot metrics(OwnerPopulationLimitScope scope, int limit) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            return metrics.snapshot(
                    readiness, entries, committed, pending, pendingByToken.size(), scope, limit
            );
        } finally {
            lock.unlock();
        }
    }
}
