package com.alechilles.alecstamework.ownership;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Lock-consistent read and diagnostics facade over owner population index state. */
final class OwnerPopulationQueryAccess {
    private final ReentrantLock lock;
    private final Map<String, OwnerPopulationEntry> entries;
    private final Map<OwnerPopulationScopeKey, Long> committed;
    private final Map<OwnerPopulationScopeKey, Long> pending;
    private final Map<UUID, OwnerPopulationPendingTransition> pendingByToken;
    private final OwnerPopulationReadinessState readiness;
    private final OwnerPopulationMetrics metrics;

    OwnerPopulationQueryAccess(ReentrantLock lock,
                               Map<String, OwnerPopulationEntry> entries,
                               Map<OwnerPopulationScopeKey, Long> committed,
                               Map<OwnerPopulationScopeKey, Long> pending,
                               Map<UUID, OwnerPopulationPendingTransition> pendingByToken,
                               OwnerPopulationReadinessState readiness,
                               OwnerPopulationMetrics metrics) {
        this.lock = lock;
        this.entries = entries;
        this.committed = committed;
        this.pending = pending;
        this.pendingByToken = pendingByToken;
        this.readiness = readiness;
        this.metrics = metrics;
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
