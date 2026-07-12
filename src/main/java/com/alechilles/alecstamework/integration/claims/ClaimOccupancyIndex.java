package com.alechilles.alecstamework.integration.claims;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;

/**
 * Thread-safe in-memory projection of durable physical companion occupancy.
 *
 * <p>The index has no ECS, persistence, settings, or provider dependency. Natural movement is
 * always observed; cap decisions belong to {@link ClaimAdmissionService} and never reject an
 * observation.</p>
 */
public final class ClaimOccupancyIndex {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ClaimOccupancyEntry> entriesByProfile = new HashMap<>();
    private final Map<ClaimChunkCoordinate, Set<String>> profilesByChunk = new HashMap<>();
    private ClaimOccupancyReadiness readiness = ClaimOccupancyReadiness.LOADING;
    private long revision;

    public void replaceCommittedEntries(@Nonnull Collection<ClaimOccupancyEntry> entries,
                                        @Nonnull ClaimOccupancyReadiness newReadiness) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(newReadiness, "newReadiness");
        Map<String, ClaimOccupancyEntry> replacements = new HashMap<>();
        for (ClaimOccupancyEntry entry : entries) {
            Objects.requireNonNull(entry, "entries cannot contain null");
            if (replacements.put(entry.profileId(), entry) != null) {
                throw new IllegalArgumentException("Duplicate profile entry: " + entry.profileId());
            }
        }

        lock.lock();
        try {
            entriesByProfile.clear();
            entriesByProfile.putAll(replacements);
            rebuildChunkIndex();
            readiness = newReadiness;
            incrementRevision();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies an authoritative committed record. This method never consults a cap.
     */
    public void reconcileCommittedEntry(@Nonnull ClaimOccupancyEntry entry) {
        Objects.requireNonNull(entry, "entry");
        lock.lock();
        try {
            ClaimOccupancyEntry current = entriesByProfile.get(entry.profileId());
            if (current != null && entry.revision() < current.revision()) {
                throw new IllegalArgumentException("Cannot reconcile an older occupancy revision.");
            }
            replaceEntry(current, entry);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records natural movement without admission checks. Stale observations are ignored.
     */
    public boolean observeMovement(@Nonnull ClaimOccupancyEntry observed) {
        Objects.requireNonNull(observed, "observed");
        lock.lock();
        try {
            ClaimOccupancyEntry current = entriesByProfile.get(observed.profileId());
            if (current != null && observed.revision() < current.revision()) {
                return false;
            }
            replaceEntry(current, observed);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void setReadiness(@Nonnull ClaimOccupancyReadiness readiness) {
        lock.lock();
        try {
            this.readiness = Objects.requireNonNull(readiness, "readiness");
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    public ClaimOccupancyReadiness readiness() {
        lock.lock();
        try {
            return readiness;
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    public Optional<ClaimOccupancyEntry> entry(@Nonnull String profileId) {
        String normalized = ClaimOccupancyEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return Optional.ofNullable(entriesByProfile.get(normalized));
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    public ClaimOccupancySnapshot snapshot() {
        lock.lock();
        try {
            return new ClaimOccupancySnapshot(revision, entriesByProfile, profilesByChunk);
        } finally {
            lock.unlock();
        }
    }

    public long revision() {
        lock.lock();
        try {
            return revision;
        } finally {
            lock.unlock();
        }
    }

    boolean matchesTransitions(@Nonnull List<ClaimOccupancyTransition> transitions) {
        lock.lock();
        try {
            return matchesTransitionsLocked(transitions);
        } finally {
            lock.unlock();
        }
    }

    /** Validates a prepared snapshot and its subject transitions at one index linearization point. */
    boolean matchesSnapshotAndTransitions(long expectedRevision,
                                          @Nonnull List<ClaimOccupancyTransition> transitions) {
        lock.lock();
        try {
            return revision == expectedRevision && matchesTransitionsLocked(transitions);
        } finally {
            lock.unlock();
        }
    }

    boolean applyTransitions(@Nonnull List<ClaimOccupancyTransition> transitions) {
        lock.lock();
        try {
            if (!matchesTransitionsLocked(transitions)) {
                return false;
            }
            for (ClaimOccupancyTransition transition : transitions) {
                ClaimOccupancyEntry current = entriesByProfile.get(transition.profileId());
                replaceEntry(current, transition.proposed());
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean matchesTransitionsLocked(List<ClaimOccupancyTransition> transitions) {
        for (ClaimOccupancyTransition transition : transitions) {
            if (!Objects.equals(transition.expected(), entriesByProfile.get(transition.profileId()))) {
                return false;
            }
        }
        return true;
    }

    private void replaceEntry(ClaimOccupancyEntry current, ClaimOccupancyEntry replacement) {
        if (Objects.equals(current, replacement)) {
            return;
        }
        if (current != null) {
            removeChunkMembership(current);
        }
        entriesByProfile.put(replacement.profileId(), replacement);
        addChunkMembership(replacement);
        incrementRevision();
    }

    private void rebuildChunkIndex() {
        profilesByChunk.clear();
        for (ClaimOccupancyEntry entry : entriesByProfile.values()) {
            addChunkMembership(entry);
        }
    }

    private void addChunkMembership(ClaimOccupancyEntry entry) {
        if (!entry.occupiesClaim()) {
            return;
        }
        profilesByChunk.computeIfAbsent(entry.physicalChunk(), ignored -> new HashSet<>())
                .add(entry.profileId());
    }

    private void removeChunkMembership(ClaimOccupancyEntry entry) {
        if (!entry.occupiesClaim()) {
            return;
        }
        Set<String> profiles = profilesByChunk.get(entry.physicalChunk());
        if (profiles == null) {
            throw new IllegalStateException("Missing physical occupancy bucket for " + entry.profileId());
        }
        profiles.remove(entry.profileId());
        if (profiles.isEmpty()) {
            profilesByChunk.remove(entry.physicalChunk());
        }
    }

    private void incrementRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Claim occupancy index revision exhausted.");
        }
        revision++;
    }
}
