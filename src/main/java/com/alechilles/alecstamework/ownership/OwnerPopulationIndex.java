package com.alechilles.alecstamework.ownership;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final Map<String, OwnerPopulationEntry> entriesByProfile = new HashMap<>();
    private final Map<OwnerPopulationScopeKey, Long> committedCounts = new HashMap<>();
    private final Map<OwnerPopulationScopeKey, Long> pendingCounts = new HashMap<>();
    private final Map<UUID, OwnerPopulationPendingTransition> pendingByToken = new HashMap<>();
    private final Map<String, UUID> pendingTokenByProfile = new HashMap<>();
    private final OwnerPopulationReadinessState readiness = new OwnerPopulationReadinessState();
    private final OwnerPopulationMetrics metrics = new OwnerPopulationMetrics();
    private final OwnerPopulationReservationBook reservations;
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
    public OwnerPopulationIndex() {
        this(System::nanoTime);
    }

    public OwnerPopulationIndex(LongSupplier monotonicClock) {
        this.reservations = new OwnerPopulationReservationBook(
                Objects.requireNonNull(monotonicClock, "monotonicClock"),
                pendingCounts,
                pendingByToken,
                pendingTokenByProfile,
                metrics
        );
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
        reservations.expireReserved(nowNanos);
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
        if (reservations.hasProfile(request.profileId())) {
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
        OwnerPopulationReservation reservation = reservations.newReservation();
        OwnerPopulationPendingTransition transition = new OwnerPopulationPendingTransition(
                reservation,
                request,
                draft.current(),
                draft.proposed(),
                draft.additions(),
                draft.constrainedKey(),
                draft.positiveDelta(),
                reservations.expiresAt(nowNanos, request.leaseDurationNanos())
        );
        reservations.register(transition);
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
        if (!reservations.owns(Objects.requireNonNull(reservation, "reservation"))) {
            return false;
        }
        lock.lock();
        try {
            long nowNanos = observeNow();
            OwnerPopulationPendingTransition transition = reservations.find(reservation);
            if (transition == null || reservation.state() != OwnerPopulationReservation.ReservationState.RESERVED) {
                return false;
            }
            if (reservations.isExpired(transition, nowNanos)) {
                reservations.close(
                        transition, OwnerPopulationReservation.ReservationState.EXPIRED
                );
                return false;
            }
            if (!hasApplyHeadroom(transition)) {
                return false;
            }
            reservation.setState(OwnerPopulationReservation.ReservationState.APPLYING);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean hasApplyHeadroom(OwnerPopulationPendingTransition transition) {
        OwnerPopulationTransitionRequest request = transition.request();
        if (!transition.positiveDelta() || request.limit() <= 0 || request.force()) {
            return true;
        }
        if (canonicalReloadInProgress
                || !readiness.forScope(request.limitScope()).allowsPositiveCappedAdmissions()) {
            return false;
        }
        OwnerPopulationScopeKey key = transition.constrainedKey();
        long ownPending = transition.additions().contains(key) ? 1L : 0L;
        if (key == null || ownPending == 0L) {
            return false;
        }
        long committed = count(committedCounts, key);
        long allPending = count(pendingCounts, key);
        long otherPending = Math.max(0L, allPending - ownPending);
        long remaining = (long) request.limit() - committed;
        return remaining >= ownPending && otherPending <= remaining - ownPending;
    }

    /** Commits once; repeated commits of the same successfully committed capability are harmless. */
    public boolean commit(OwnerPopulationReservation reservation) {
        if (!reservations.owns(Objects.requireNonNull(reservation, "reservation"))) {
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
            OwnerPopulationPendingTransition transition = reservations.find(reservation);
            if (transition == null) {
                return false;
            }
            if (!matchesCapturedEntry(transition.current(), entriesByProfile.get(transition.request().profileId()))) {
                reservations.close(
                        transition, OwnerPopulationReservation.ReservationState.CANCELED
                );
                return false;
            }

            if (transition.current() != null) {
                removeCounts(committedCounts, scopeKeys(transition.current()));
            }
            entriesByProfile.put(transition.proposed().profileId(), transition.proposed());
            addCounts(committedCounts, scopeKeys(transition.proposed()));
            reservations.commit(transition);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Cancels a reserved or applying transition without changing its captured committed entry. */
    public boolean cancel(OwnerPopulationReservation reservation) {
        if (!reservations.owns(Objects.requireNonNull(reservation, "reservation"))) {
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
            OwnerPopulationPendingTransition transition = reservations.find(reservation);
            if (transition == null) {
                return false;
            }
            reservations.close(
                    transition, OwnerPopulationReservation.ReservationState.CANCELED
            );
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Expires only unclaimed RESERVED leases using the injected monotonic clock. */
    public int expireReservations() {
        lock.lock();
        try {
            return reservations.expireReserved(reservations.observeNow());
        } finally {
            lock.unlock();
        }
    }

    public Optional<OwnerPopulationEntry> entry(String profileId) {
        return queries.entry(profileId);
    }

    /** Captures readiness, reload, transition, and lifecycle state under the index's single lock. */
    public OwnerPopulationProfileStateSnapshot profileStateSnapshot(String profileId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return new OwnerPopulationProfileStateSnapshot(
                    readiness.overall(),
                    canonicalReloadInProgress,
                    pendingTokenByProfile.containsKey(normalized),
                    Optional.ofNullable(entriesByProfile.get(normalized))
            );
        } finally {
            lock.unlock();
        }
    }

    /** Returns whether a prepared/applying transition currently suppresses observer reconciliation. */
    public boolean hasPendingTransition(String profileId) {
        return reconciliation.hasPending(profileId);
    }

    /**
     * Returns whether the exact profile is currently applying an owner clear from the observed
     * owner. A merely reserved transition cannot authorize an ECS component removal.
     */
    public boolean hasApplyingOwnerClearTransition(String profileId, UUID observedOwnerId) {
        String normalized = OwnerPopulationEntry.normalizeProfileId(profileId);
        lock.lock();
        try {
            return reservations.hasApplyingOwnerClear(normalized, observedOwnerId);
        } finally {
            lock.unlock();
        }
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
            if (canonicalReloadInProgress || !reservations.isEmpty()) {
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

    private boolean matchesCapturedEntry(OwnerPopulationEntry expected, OwnerPopulationEntry actual) {
        return Objects.equals(expected, actual);
    }

    private long observeNow() {
        return reservations.observeNow();
    }

    private long incrementRevision(long revision) {
        return revision + 1L;
    }

    private long currentRevision(OwnerPopulationEntry entry) {
        return entry == null ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION : entry.revision();
    }

}
