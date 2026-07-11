package com.alechilles.alecstamework.ownership;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import static com.alechilles.alecstamework.ownership.OwnerPopulationTransitionDraft.scopeKeys;

/**
 * Thread-safe, process-local authority for committed and reserved owner population.
 *
 * <p>This class deliberately has no ECS, persistence, player, plugin, or world dependency. Every
 * operation takes one short in-memory lock and never waits on another executor.
 */
public final class OwnerPopulationIndex {
    private final ReentrantLock lock = new ReentrantLock();
    private final Object reservationAuthority = new Object();
    private final LongSupplier monotonicClock;
    private final Map<String, OwnerPopulationEntry> entriesByProfile = new HashMap<>();
    private final Map<OwnerPopulationScopeKey, Long> committedCounts = new HashMap<>();
    private final Map<OwnerPopulationScopeKey, Long> pendingCounts = new HashMap<>();
    private final Map<UUID, PendingTransition> pendingByToken = new HashMap<>();
    private final Map<String, UUID> pendingTokenByProfile = new HashMap<>();
    private OwnerPopulationReadiness readiness = OwnerPopulationReadiness.LOADING;
    private long lastObservedNanos = Long.MIN_VALUE;

    public OwnerPopulationIndex() {
        this(System::nanoTime);
    }

    public OwnerPopulationIndex(LongSupplier monotonicClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    /** Replaces startup state. Active reservations are rejected to avoid invalidating live leases. */
    public void replaceCommittedEntries(Collection<OwnerPopulationEntry> entries,
                                        OwnerPopulationReadiness newReadiness) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(newReadiness, "newReadiness");
        lock.lock();
        try {
            if (!pendingByToken.isEmpty()) {
                throw new IllegalStateException("Cannot replace committed entries while reservations are active.");
            }
            Map<String, OwnerPopulationEntry> replacementEntries = new HashMap<>();
            Map<OwnerPopulationScopeKey, Long> replacementCounts = new HashMap<>();
            for (OwnerPopulationEntry entry : entries) {
                Objects.requireNonNull(entry, "entry");
                OwnerPopulationEntry previous = replacementEntries.put(entry.profileId(), entry);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate profile entry: " + entry.profileId());
                }
                addCounts(replacementCounts, scopeKeys(entry));
            }
            entriesByProfile.clear();
            entriesByProfile.putAll(replacementEntries);
            committedCounts.clear();
            committedCounts.putAll(replacementCounts);
            pendingCounts.clear();
            pendingTokenByProfile.clear();
            readiness = newReadiness;
        } finally {
            lock.unlock();
        }
    }

    /** Reconciles one externally observed committed entry when that profile has no live transition. */
    public void reconcileCommittedEntry(OwnerPopulationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        lock.lock();
        try {
            if (pendingTokenByProfile.containsKey(entry.profileId())) {
                throw new IllegalStateException("Profile has an active reservation: " + entry.profileId());
            }
            OwnerPopulationEntry previous = entriesByProfile.put(entry.profileId(), entry);
            if (previous != null) {
                removeCounts(committedCounts, scopeKeys(previous));
            }
            addCounts(committedCounts, scopeKeys(entry));
        } finally {
            lock.unlock();
        }
    }

    public void setReadiness(OwnerPopulationReadiness readiness) {
        lock.lock();
        try {
            this.readiness = Objects.requireNonNull(readiness, "readiness");
        } finally {
            lock.unlock();
        }
    }

    public OwnerPopulationReadiness readiness() {
        lock.lock();
        try {
            return readiness;
        } finally {
            lock.unlock();
        }
    }

    /** Atomically compares state, checks headroom, and reserves every positive destination key. */
    public OwnerPopulationDecision reserve(OwnerPopulationTransitionRequest request) {
        Objects.requireNonNull(request, "request");
        lock.lock();
        try {
            return reserveLocked(request, observeNow());
        } finally {
            lock.unlock();
        }
    }

    private OwnerPopulationDecision reserveLocked(OwnerPopulationTransitionRequest request,
                                                  long nowNanos) {
        expireReservationsLocked(nowNanos);
        OwnerPopulationEntry current = entriesByProfile.get(request.profileId());
        OwnerPopulationDecision denial = validateReservationPreconditions(request, current);
        if (denial != null) {
            return denial;
        }
        // Revision zero is the durable unowned baseline allocated while PREPARED is journaled.
        long nextRevision = current == null ? 1L : incrementRevision(current.revision());
        OwnerPopulationTransitionDraft draft = OwnerPopulationTransitionDraft.create(
                request,
                current,
                nextRevision
        );
        long committed = count(committedCounts, draft.constrainedKey());
        long pending = count(pendingCounts, draft.constrainedKey());
        denial = validateAdmission(request, current, draft, committed, pending);
        return denial != null
                ? denial
                : registerReservation(request, draft, nowNanos, committed, pending);
    }

    private OwnerPopulationDecision validateReservationPreconditions(
            OwnerPopulationTransitionRequest request,
            OwnerPopulationEntry current) {
        OwnerPopulationDecision mismatch = validateExpectedState(request, current);
        if (mismatch != null) {
            return mismatch;
        }
        if (pendingTokenByProfile.containsKey(request.profileId())) {
            return denied(request, "owner-population-profile-pending", current, 0L, 0L, false);
        }
        if (current != null && current.revision() == Long.MAX_VALUE) {
            return denied(request, "owner-population-revision-exhausted", current, 0L, 0L, false);
        }
        if (OwnerPopulationTransitionDraft.requiresWorldContext(request, current)) {
            return denied(request, "owner-cap-world-context-required", current, 0L, 0L, true);
        }
        return null;
    }

    private OwnerPopulationDecision validateAdmission(OwnerPopulationTransitionRequest request,
                                                      OwnerPopulationEntry current,
                                                      OwnerPopulationTransitionDraft draft,
                                                      long committed,
                                                      long pending) {
        if (!draft.positiveDelta() || request.limit() <= 0 || request.force()) {
            return null;
        }
        if (!readiness.allowsPositiveCappedAdmissions()) {
            return denied(request, "owner-population-not-ready", current, committed, pending, true);
        }
        if (committed >= request.limit() || pending >= request.limit() - committed) {
            return denied(request, "owner-cap-reached", current, committed, pending, true);
        }
        return null;
    }

    private OwnerPopulationDecision registerReservation(OwnerPopulationTransitionRequest request,
                                                        OwnerPopulationTransitionDraft draft,
                                                        long nowNanos,
                                                        long committed,
                                                        long pending) {
        OwnerPopulationReservation reservation =
                new OwnerPopulationReservation(UUID.randomUUID(), reservationAuthority);
        PendingTransition transition = new PendingTransition(
                reservation,
                request,
                draft.current(),
                draft.proposed(),
                draft.additions(),
                addLease(nowNanos, request.leaseDurationNanos())
        );
        pendingByToken.put(reservation.tokenId(), transition);
        pendingTokenByProfile.put(request.profileId(), reservation.tokenId());
        addCounts(pendingCounts, draft.additions());
        return new OwnerPopulationDecision(
                true,
                OwnerPopulationTransitionDraft.reservationReason(request, draft.positiveDelta()),
                reservation,
                readiness,
                request.limit(),
                committed,
                pending,
                currentRevision(draft.current()),
                draft.positiveDelta(),
                request.force()
        );
    }

    /** Claims a reserved lease exactly once and makes it non-expiring while its mutation applies. */
    public boolean claimForApply(OwnerPopulationReservation reservation) {
        if (!Objects.requireNonNull(reservation, "reservation").belongsTo(reservationAuthority)) {
            return false;
        }
        lock.lock();
        try {
            long nowNanos = observeNow();
            PendingTransition transition = findPending(reservation);
            if (transition == null || reservation.state() != OwnerPopulationReservation.ReservationState.RESERVED) {
                return false;
            }
            if (isExpired(transition, nowNanos)) {
                expireTransition(transition);
                return false;
            }
            reservation.setState(OwnerPopulationReservation.ReservationState.APPLYING);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Commits once; repeated commits of the same successfully committed capability are harmless. */
    public boolean commit(OwnerPopulationReservation reservation) {
        if (!Objects.requireNonNull(reservation, "reservation").belongsTo(reservationAuthority)) {
            return false;
        }
        lock.lock();
        try {
            if (reservation.state() == OwnerPopulationReservation.ReservationState.COMMITTED) {
                return true;
            }
            if (reservation.state() == OwnerPopulationReservation.ReservationState.CANCELED
                    || reservation.state() == OwnerPopulationReservation.ReservationState.EXPIRED) {
                return false;
            }
            if (reservation.state() != OwnerPopulationReservation.ReservationState.APPLYING) {
                return false;
            }
            PendingTransition transition = findPending(reservation);
            if (transition == null) {
                return false;
            }
            if (!matchesCapturedEntry(transition.current(), entriesByProfile.get(transition.request().profileId()))) {
                closePending(transition, OwnerPopulationReservation.ReservationState.CANCELED);
                return false;
            }

            removeCounts(pendingCounts, transition.additions());
            if (transition.current() != null) {
                removeCounts(committedCounts, scopeKeys(transition.current()));
            }
            entriesByProfile.put(transition.proposed().profileId(), transition.proposed());
            addCounts(committedCounts, scopeKeys(transition.proposed()));
            removePendingIndexes(transition);
            reservation.setState(OwnerPopulationReservation.ReservationState.COMMITTED);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Cancels a reserved or applying transition without changing its captured committed entry. */
    public boolean cancel(OwnerPopulationReservation reservation) {
        if (!Objects.requireNonNull(reservation, "reservation").belongsTo(reservationAuthority)) {
            return false;
        }
        lock.lock();
        try {
            if (reservation.state() == OwnerPopulationReservation.ReservationState.CANCELED
                    || reservation.state() == OwnerPopulationReservation.ReservationState.EXPIRED) {
                return true;
            }
            if (reservation.state() == OwnerPopulationReservation.ReservationState.COMMITTED) {
                return false;
            }
            PendingTransition transition = findPending(reservation);
            if (transition == null) {
                return false;
            }
            closePending(transition, OwnerPopulationReservation.ReservationState.CANCELED);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Expires only unclaimed RESERVED leases using the injected monotonic clock. */
    public int expireReservations() {
        lock.lock();
        try {
            return expireReservationsLocked(observeNow());
        } finally {
            lock.unlock();
        }
    }

    public Optional<OwnerPopulationEntry> entry(String profileId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return Optional.ofNullable(entriesByProfile.get(normalized));
        } finally {
            lock.unlock();
        }
    }

    /** Returns global counts plus per-world counts, or zero world counts when the world is null. */
    public OwnerPopulationCounts counts(UUID ownerId, String worldName) {
        Objects.requireNonNull(ownerId, "ownerId");
        String normalizedWorld = OwnerPopulationScopeKey.normalizeWorldName(worldName);
        lock.lock();
        try {
            OwnerPopulationScopeKey global = OwnerPopulationScopeKey.global(ownerId);
            long worldCommitted = 0L;
            long worldPending = 0L;
            if (normalizedWorld != null) {
                OwnerPopulationScopeKey perWorld = OwnerPopulationScopeKey.perWorld(ownerId, normalizedWorld);
                worldCommitted = count(committedCounts, perWorld);
                worldPending = count(pendingCounts, perWorld);
            }
            return new OwnerPopulationCounts(
                    count(committedCounts, global),
                    count(pendingCounts, global),
                    worldCommitted,
                    worldPending
            );
        } finally {
            lock.unlock();
        }
    }

    public int pendingReservationCount() {
        lock.lock();
        try {
            return pendingByToken.size();
        } finally {
            lock.unlock();
        }
    }

    private OwnerPopulationDecision validateExpectedState(OwnerPopulationTransitionRequest request,
                                                          OwnerPopulationEntry current) {
        if (current == null) {
            if (request.expectedRevision() != OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION) {
                return denied(request, "owner-population-profile-missing", null, 0L, 0L, false);
            }
            if (request.expectedOwnerId() != null || request.sourceWorldName() != null) {
                return denied(request, "owner-population-expected-state-mismatch", null, 0L, 0L, false);
            }
            return null;
        }
        if (request.expectedRevision() != current.revision()) {
            return denied(request, "owner-population-revision-mismatch", current, 0L, 0L, false);
        }
        if (!Objects.equals(request.expectedOwnerId(), current.ownerId())
                || !Objects.equals(request.sourceWorldName(), current.ownershipWorldName())) {
            return denied(request, "owner-population-expected-state-mismatch", current, 0L, 0L, false);
        }
        return null;
    }

    private OwnerPopulationDecision denied(OwnerPopulationTransitionRequest request,
                                           String reason,
                                           OwnerPopulationEntry current,
                                           long committed,
                                           long pending,
                                           boolean positiveDelta) {
        return new OwnerPopulationDecision(
                false,
                reason,
                null,
                readiness,
                request.limit(),
                committed,
                pending,
                currentRevision(current),
                positiveDelta,
                request.force()
        );
    }

    private void addCounts(Map<OwnerPopulationScopeKey, Long> counts,
                           Collection<OwnerPopulationScopeKey> keys) {
        for (OwnerPopulationScopeKey key : keys) {
            counts.merge(key, 1L, Long::sum);
        }
    }

    private void removeCounts(Map<OwnerPopulationScopeKey, Long> counts,
                              Collection<OwnerPopulationScopeKey> keys) {
        for (OwnerPopulationScopeKey key : keys) {
            long updated = count(counts, key) - 1L;
            if (updated < 0L) {
                throw new IllegalStateException("Owner population count underflow for " + key);
            }
            if (updated == 0L) {
                counts.remove(key);
            } else {
                counts.put(key, updated);
            }
        }
    }

    private long count(Map<OwnerPopulationScopeKey, Long> counts, OwnerPopulationScopeKey key) {
        return key == null ? 0L : counts.getOrDefault(key, 0L);
    }

    private PendingTransition findPending(OwnerPopulationReservation reservation) {
        PendingTransition transition = pendingByToken.get(reservation.tokenId());
        return transition != null && transition.reservation() == reservation ? transition : null;
    }

    private boolean matchesCapturedEntry(OwnerPopulationEntry expected, OwnerPopulationEntry actual) {
        return Objects.equals(expected, actual);
    }

    private int expireReservationsLocked(long nowNanos) {
        int expired = 0;
        for (PendingTransition transition : Set.copyOf(pendingByToken.values())) {
            if (transition.reservation().state() != OwnerPopulationReservation.ReservationState.RESERVED
                    || !isExpired(transition, nowNanos)) {
                continue;
            }
            expireTransition(transition);
            expired++;
        }
        return expired;
    }

    private boolean isExpired(PendingTransition transition, long nowNanos) {
        return nowNanos >= transition.expiresAtNanos();
    }

    private void expireTransition(PendingTransition transition) {
        closePending(transition, OwnerPopulationReservation.ReservationState.EXPIRED);
    }

    private void closePending(PendingTransition transition,
                              OwnerPopulationReservation.ReservationState terminalState) {
        removeCounts(pendingCounts, transition.additions());
        removePendingIndexes(transition);
        transition.reservation().setState(terminalState);
    }

    private void removePendingIndexes(PendingTransition transition) {
        pendingByToken.remove(transition.reservation().tokenId(), transition);
        pendingTokenByProfile.remove(transition.request().profileId(), transition.reservation().tokenId());
    }

    private long observeNow() {
        long nowNanos = monotonicClock.getAsLong();
        if (lastObservedNanos != Long.MIN_VALUE && nowNanos < lastObservedNanos) {
            throw new IllegalStateException("Monotonic owner-population clock moved backwards.");
        }
        lastObservedNanos = nowNanos;
        return nowNanos;
    }

    private long addLease(long nowNanos, long leaseDurationNanos) {
        try {
            return Math.addExact(nowNanos, leaseDurationNanos);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private long incrementRevision(long revision) {
        return revision + 1L;
    }

    private long currentRevision(OwnerPopulationEntry entry) {
        return entry == null ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION : entry.revision();
    }

    private record PendingTransition(OwnerPopulationReservation reservation,
                                     OwnerPopulationTransitionRequest request,
                                     OwnerPopulationEntry current,
                                     OwnerPopulationEntry proposed,
                                     Set<OwnerPopulationScopeKey> additions,
                                     long expiresAtNanos) {
        private PendingTransition {
            additions = Set.copyOf(additions);
        }
    }
}
