package com.alechilles.alecstamework.ownership;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.addCounts;
import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.removeCounts;
import static com.alechilles.alecstamework.ownership.OwnerPopulationTransitionDraft.scopeKeys;

/** Lock-sharing reconciliation operations kept separate from admission/index policy. */
final class OwnerPopulationReconciliationAccess {
    private final ReentrantLock lock;
    private final Map<String, OwnerPopulationEntry> entries;
    private final Map<OwnerPopulationScopeKey, Long> committedCounts;
    private final Map<String, UUID> pendingByProfile;

    OwnerPopulationReconciliationAccess(ReentrantLock lock,
                                        Map<String, OwnerPopulationEntry> entries,
                                        Map<OwnerPopulationScopeKey, Long> committedCounts,
                                        Map<String, UUID> pendingByProfile) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.committedCounts = Objects.requireNonNull(committedCounts, "committedCounts");
        this.pendingByProfile = Objects.requireNonNull(pendingByProfile, "pendingByProfile");
    }

    boolean tryReconcile(OwnerPopulationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        lock.lock();
        try {
            if (pendingByProfile.containsKey(entry.profileId())) {
                return false;
            }
            OwnerPopulationEntry previous = entries.put(entry.profileId(), entry);
            if (previous != null) {
                removeCounts(committedCounts, scopeKeys(previous));
            }
            addCounts(committedCounts, scopeKeys(entry));
            return true;
        } finally {
            lock.unlock();
        }
    }

    boolean hasPending(String profileId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return pendingByProfile.containsKey(normalized);
        } finally {
            lock.unlock();
        }
    }

    boolean advanceRevision(String profileId, long expectedRevision, long newRevision) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        if (newRevision < expectedRevision) {
            throw new IllegalArgumentException("A reconciled revision cannot move backwards.");
        }
        lock.lock();
        try {
            OwnerPopulationEntry current = entries.get(normalized);
            if (pendingByProfile.containsKey(normalized)
                    || current == null
                    || current.revision() != expectedRevision) {
                return false;
            }
            entries.put(normalized, new OwnerPopulationEntry(
                    current.profileId(), current.ownerId(), current.ownershipWorldName(),
                    current.lifecycleState(), newRevision
            ));
            return true;
        } finally {
            lock.unlock();
        }
    }
}
