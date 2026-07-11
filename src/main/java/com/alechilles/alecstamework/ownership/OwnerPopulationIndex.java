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
import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.addCounts;
import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.count;
import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.removeCounts;

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
    private final Map<UUID, OwnerPopulationPendingTransition> pendingByToken = new HashMap<>();
    private final Map<String, UUID> pendingTokenByProfile = new HashMap<>();
    private final OwnerPopulationReadinessState readiness = new OwnerPopulationReadinessState();
    private final OwnerPopulationMetrics metrics = new OwnerPopulationMetrics();
    private final OwnerPopulationQueryAccess queries = new OwnerPopulationQueryAccess(
            lock, entriesByProfile, committedCounts, pendingCounts, pendingByToken, readiness, metrics
    );
    private boolean canonicalReloadInProgress;
    private final OwnerPopulationStateReplacementAccess replacement =
            new OwnerPopulationStateReplacementAccess(
                    lock, entriesByProfile, committedCounts, pendingCounts,
                    pendingByToken, pendingTokenByProfile, readiness
            );
    private final OwnerPopulationReconciliationAccess reconciliation =
            new OwnerPopulationReconciliationAccess(
                    lock, entriesByProfile, committedCounts, pendingTokenByProfile
            );
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
        replaceCommittedEntries(entries, newReadiness, newReadiness);
    }

    /** Replaces entries and both scope readiness values under the same index lock. */
    public void replaceCommittedEntries(Collection<OwnerPopulationEntry> entries,
                                        OwnerPopulationReadiness globalReadiness,
                                        OwnerPopulationReadiness perWorldReadiness) {
        replacement.replace(entries, globalReadiness, perWorldReadiness);
    }

    /** Reconciles one externally observed committed entry when that profile has no live transition. */
    public void reconcileCommittedEntry(OwnerPopulationEntry entry) {
        if (!tryReconcileCommittedEntry(entry)) {
            throw new IllegalStateException("Profile has an active reservation: " + entry.profileId());
        }
    }

    /** Reconciles an observation unless a prepared/applying transition owns the profile. */
    public boolean tryReconcileCommittedEntry(OwnerPopulationEntry entry) {
        return reconciliation.tryReconcile(entry);
    }

    public void setReadiness(OwnerPopulationReadiness readiness) {
        lock.lock();
        try {
            this.readiness.setBoth(Objects.requireNonNull(readiness, "readiness"));
        } finally {
            lock.unlock();
        }
    }

    public void setReadiness(OwnerPopulationReadiness globalReadiness,
                             OwnerPopulationReadiness perWorldReadiness) {
        lock.lock();
        try {
            readiness.set(globalReadiness, perWorldReadiness);
        } finally {
            lock.unlock();
        }
    }

    public OwnerPopulationReadiness readiness() {
        lock.lock();
        try {
            return readiness.overall();
        } finally {
            lock.unlock();
        }
    }

    public OwnerPopulationReadiness readiness(OwnerPopulationLimitScope scope) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            return readiness.forScope(scope);
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
        if (canonicalReloadInProgress) {
            return denied(request, "owner-population-canonical-reload", current, 0L, 0L, false);
        }
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
        if (!readiness.forScope(request.limitScope()).allowsPositiveCappedAdmissions()) {
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
        OwnerPopulationPendingTransition transition = new OwnerPopulationPendingTransition(
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
        metrics.reservationCreated();
        return new OwnerPopulationDecision(
                true,
                OwnerPopulationTransitionDraft.reservationReason(request, draft.positiveDelta()),
                reservation,
                readiness.forScope(request.limitScope()),
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
            OwnerPopulationPendingTransition transition = findPending(reservation);
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
            OwnerPopulationPendingTransition transition = findPending(reservation);
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
            metrics.reservationCommitted();
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
            OwnerPopulationPendingTransition transition = findPending(reservation);
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
        return queries.entry(profileId);
    }

    /** Returns whether a prepared/applying transition currently suppresses observer reconciliation. */
    public boolean hasPendingTransition(String profileId) {
        return reconciliation.hasPending(profileId);
    }

    /** Advances only the durable revision of an otherwise unchanged externally reconciled entry. */
    public boolean advanceReconciledRevision(String profileId, long expectedRevision, long newRevision) {
        return reconciliation.advanceRevision(profileId, expectedRevision, newRevision);
    }

    /** Returns global counts plus per-world counts, or zero world counts when the world is null. */
    public OwnerPopulationCounts counts(UUID ownerId, String worldName) {
        return queries.counts(ownerId, worldName);
    }

    public int pendingReservationCount() {
        return queries.pendingReservationCount();
    }

    /** Atomically blocks new transitions once every previously accepted transition has closed. */
    public boolean tryBeginCanonicalReload() {
        lock.lock();
        try {
            if (canonicalReloadInProgress || !pendingByToken.isEmpty()) {
                return false;
            }
            canonicalReloadInProgress = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Releases the brief final-reload gate after buffered live observations have replayed. */
    public void finishCanonicalReload() {
        lock.lock();
        try {
            canonicalReloadInProgress = false;
        } finally {
            lock.unlock();
        }
    }

    /** Returns one lock-consistent, allocation-light diagnostics snapshot. */
    public OwnerPopulationMetrics.Snapshot metrics(OwnerPopulationLimitScope configuredScope,
                                                   int configuredLimit) {
        return queries.metrics(configuredScope, configuredLimit);
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
                readiness.forScope(request.limitScope()),
                request.limit(),
                committed,
                pending,
                currentRevision(current),
                positiveDelta,
                request.force()
        );
    }

    private OwnerPopulationPendingTransition findPending(OwnerPopulationReservation reservation) {
        OwnerPopulationPendingTransition transition = pendingByToken.get(reservation.tokenId());
        return transition != null && transition.reservation() == reservation ? transition : null;
    }

    private boolean matchesCapturedEntry(OwnerPopulationEntry expected, OwnerPopulationEntry actual) {
        return Objects.equals(expected, actual);
    }

    private int expireReservationsLocked(long nowNanos) {
        int expired = 0;
        for (OwnerPopulationPendingTransition transition : Set.copyOf(pendingByToken.values())) {
            if (transition.reservation().state() != OwnerPopulationReservation.ReservationState.RESERVED
                    || !isExpired(transition, nowNanos)) {
                continue;
            }
            expireTransition(transition);
            expired++;
        }
        return expired;
    }

    private boolean isExpired(OwnerPopulationPendingTransition transition, long nowNanos) {
        return nowNanos >= transition.expiresAtNanos();
    }

    private void expireTransition(OwnerPopulationPendingTransition transition) {
        closePending(transition, OwnerPopulationReservation.ReservationState.EXPIRED);
    }

    private void closePending(OwnerPopulationPendingTransition transition,
                              OwnerPopulationReservation.ReservationState terminalState) {
        removeCounts(pendingCounts, transition.additions());
        removePendingIndexes(transition);
        transition.reservation().setState(terminalState);
        if (terminalState == OwnerPopulationReservation.ReservationState.CANCELED) {
            metrics.reservationCanceled();
        } else if (terminalState == OwnerPopulationReservation.ReservationState.EXPIRED) {
            metrics.reservationExpired();
        }
    }

    private void removePendingIndexes(OwnerPopulationPendingTransition transition) {
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

}
