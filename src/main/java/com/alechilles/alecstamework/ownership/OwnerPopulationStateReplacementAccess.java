package com.alechilles.alecstamework.ownership;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Atomic bootstrap/reconciliation replacement of all committed owner-index state. */
final class OwnerPopulationStateReplacementAccess {
    private final ReentrantLock lock;
    private final Map<String, OwnerPopulationEntry> entries;
    private final Map<OwnerPopulationScopeKey, Long> committed;
    private final Map<OwnerPopulationScopeKey, Long> pending;
    private final Map<UUID, OwnerPopulationPendingTransition> pendingTransitions;
    private final Map<String, UUID> pendingByProfile;
    private final OwnerPopulationReadinessState readiness;

    OwnerPopulationStateReplacementAccess(
            ReentrantLock lock,
            Map<String, OwnerPopulationEntry> entries,
            Map<OwnerPopulationScopeKey, Long> committed,
            Map<OwnerPopulationScopeKey, Long> pending,
            Map<UUID, OwnerPopulationPendingTransition> pendingTransitions,
            Map<String, UUID> pendingByProfile,
            OwnerPopulationReadinessState readiness
    ) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.committed = Objects.requireNonNull(committed, "committed");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.pendingTransitions = Objects.requireNonNull(pendingTransitions, "pendingTransitions");
        this.pendingByProfile = Objects.requireNonNull(pendingByProfile, "pendingByProfile");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    void replace(Collection<OwnerPopulationEntry> source,
                 OwnerPopulationReadiness global,
                 OwnerPopulationReadiness perWorld) {
        Objects.requireNonNull(source, "entries");
        Objects.requireNonNull(global, "globalReadiness");
        Objects.requireNonNull(perWorld, "perWorldReadiness");
        lock.lock();
        try {
            if (!pendingTransitions.isEmpty()) {
                throw new IllegalStateException("Cannot replace committed entries while reservations are active.");
            }
            OwnerPopulationCommittedState replacement = OwnerPopulationCommittedState.from(source);
            entries.clear();
            entries.putAll(replacement.entries());
            committed.clear();
            committed.putAll(replacement.counts());
            pending.clear();
            pendingByProfile.clear();
            readiness.set(global, perWorld);
        } finally {
            lock.unlock();
        }
    }
}
