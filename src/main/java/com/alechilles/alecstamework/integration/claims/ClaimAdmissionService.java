package com.alechilles.alecstamework.integration.claims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thread-safe reservation authority for physical claim admissions.
 *
 * <p>Provider calls and population snapshot construction happen before the short reservation
 * lock. The service deliberately does not coordinate owner slots yet; its immutable decision and
 * reservation form the claim-side seam for the combined population coordinator.</p>
 */
public final class ClaimAdmissionService {
    private static final int SNAPSHOT_RETRY_LIMIT = 3;

    private final ReentrantLock lock = new ReentrantLock();
    private final Object reservationAuthority = new Object();
    private final ClaimOccupancyIndex occupancyIndex;
    private final ClaimAdmissionEvaluator evaluator;
    private final LongSupplier monotonicClock;
    private final Map<UUID, PendingAdmission> pendingByToken = new HashMap<>();
    private final Map<String, UUID> pendingTokenByProfile = new HashMap<>();
    private final Map<ClaimPopulationKey, Long> pendingByClaim = new HashMap<>();
    private final ClaimAdmissionMetrics metrics = new ClaimAdmissionMetrics();
    private long lastObservedNanos = Long.MIN_VALUE;

    public ClaimAdmissionService(@Nonnull ClaimOccupancyIndex occupancyIndex) {
        this(occupancyIndex, new ClaimPopulationSnapshotService(), System::nanoTime);
    }

    public ClaimAdmissionService(@Nonnull ClaimOccupancyIndex occupancyIndex,
                                 @Nonnull ClaimPopulationSnapshotService snapshotService,
                                 @Nonnull LongSupplier monotonicClock) {
        this.occupancyIndex = Objects.requireNonNull(occupancyIndex, "occupancyIndex");
        this.evaluator = new ClaimAdmissionEvaluator(
                occupancyIndex,
                Objects.requireNonNull(snapshotService, "snapshotService"),
                metrics::recordSnapshotDuration
        );
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    /**
     * Resolves and reserves one exact request. No provider lookup is performed when caps are off
     * or every transition is already known to be non-positive at its durable location.
     */
    @Nonnull
    public ClaimAdmissionDecision reserve(@Nonnull ClaimAdmissionRequest request,
                                          @Nonnull ClaimLookupSession lookupSession) {
        for (int attempt = 0; attempt < SNAPSHOT_RETRY_LIMIT; attempt++) {
            ClaimAdmissionDecision decision = reserveEvaluated(evaluate(request, lookupSession));
            if (!"claim-occupancy-changed-during-admission".equals(decision.reason())) {
                return decision;
            }
        }
        return denied(
                request,
                "claim-occupancy-changed-during-admission",
                ClaimAdmissionRules.pessimisticSlots(request),
                0L,
                0L,
                null
        );
    }

    @Nonnull
    public ClaimAdmissionEvaluation evaluate(@Nonnull ClaimAdmissionRequest request,
                                             @Nonnull ClaimLookupSession lookupSession) {
        return evaluator.evaluate(request, lookupSession);
    }

    /** Evaluates against one sweep-scoped immutable committed-occupancy snapshot. */
    @Nonnull
    public ClaimAdmissionEvaluation evaluate(@Nonnull ClaimAdmissionRequest request,
                                             @Nonnull ClaimLookupSession lookupSession,
                                             @Nullable ClaimOccupancySnapshot sharedSnapshot) {
        return evaluator.evaluate(request, lookupSession, sharedSnapshot);
    }

    /** Converts an immutable provider/snapshot evaluation into a pending reservation. */
    @Nonnull
    public ClaimAdmissionDecision reserveEvaluated(@Nonnull ClaimAdmissionEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        ClaimAdmissionRequest request = evaluation.request();
        if (evaluation.status() == ClaimAdmissionEvaluation.Status.DENIED) {
            return denied(
                    request,
                    evaluation.denialReason(),
                    ClaimAdmissionRules.pessimisticSlots(request),
                    0L,
                    0L,
                    null
            );
        }
        lock.lock();
        try {
            long now = observeNow();
            expireReservationsLocked(now);
            ClaimAdmissionDecision precondition = validatePreconditions(request);
            if (precondition != null) {
                return precondition;
            }
            if (evaluation.status() == ClaimAdmissionEvaluation.Status.UNCONSTRAINED) {
                ClaimCapEvaluator.Evaluation cap = ClaimCapEvaluator.evaluate(0, 0, 0, 0L, 0L);
                return register(
                        request,
                        evaluation.target(),
                        evaluation.topologyCheckRequired(),
                        0L,
                        0L,
                        0L,
                        0L,
                        cap,
                        now
                );
            }
            return reserveEvaluatedClaim(evaluation, now);
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    private ClaimAdmissionDecision reserveEvaluatedClaim(
            @Nonnull ClaimAdmissionEvaluation evaluation,
            long now
    ) {
        ClaimAdmissionRequest request = evaluation.request();
        ClaimResolution target = evaluation.target();
        ClaimPopulationSnapshot snapshot = evaluation.snapshot();
        if (target == null || target.key() == null || snapshot == null) {
            return denied(request, "claim-evaluation-invalid", 0L, 0L, 0L, null);
        }
        if (snapshot.occupancyRevision() != occupancyIndex.revision()) {
            return denied(
                    request,
                    "claim-occupancy-changed-during-admission",
                    ClaimAdmissionRules.pessimisticSlots(request),
                    0L,
                    0L,
                    null
            );
        }
        ClaimAdmissionRules.TransitionDelta delta = ClaimAdmissionRules.analyzeTransitions(
                request.transitions(), snapshot.profileIds()
        );
        long pending = pendingByClaim.getOrDefault(target.key(), 0L);
        long adjustedCommitted = Math.max(0L, snapshot.population() - delta.departures());
        ClaimCapEvaluator.Evaluation cap = ClaimCapEvaluator.evaluate(
                request.limitPerClaimChunk(),
                request.limitPerClaimTotal(),
                target.footprint() == null ? 0 : target.footprint().chunkCount(),
                adjustedCommitted,
                pending
        );
        metrics.claimCapacityObserved(target.key(), cap.active() && adjustedCommitted > cap.effectiveCapacity());
        if (!cap.valid()) {
            return denied(request, cap.reason(), delta.arrivals(), snapshot.population(), pending, cap);
        }
        if (!request.force() && !cap.admits(delta.arrivals())) {
            return denied(request, "claim-cap-reached", delta.arrivals(), snapshot.population(), pending, cap);
        }
        return register(
                request,
                target,
                true,
                delta.arrivals(),
                delta.departures(),
                snapshot.population(),
                pending,
                cap,
                now
        );
    }

    /**
     * Claims a reservation exactly once after checking the current settings/provider generation
     * and, when relevant, freshly resolving the target topology.
     */
    public boolean claimForApply(@Nonnull ClaimAdmissionReservation reservation,
                                 @Nonnull ClaimLookupSession refreshedSession) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(refreshedSession, "refreshedSession");
        if (!reservation.belongsTo(reservationAuthority)) {
            return false;
        }
        boolean contextMatches = ClaimAdmissionRules.sameStoredPolicy(reservation, refreshedSession.context());
        ClaimResolution refreshed = null;
        if (contextMatches && reservation.topologyCheckRequired() && reservation.destinationChunk() != null) {
            refreshed = refreshedSession.resolveChunk(reservation.destinationChunk());
        }
        boolean topologyMatches = !reservation.topologyCheckRequired()
                || ClaimAdmissionRules.sameTopology(reservation, refreshed);

        lock.lock();
        try {
            long now = observeNow();
            PendingAdmission pending = findPending(reservation);
            if (pending == null || reservation.state() != ClaimAdmissionReservation.State.RESERVED) {
                return false;
            }
            if (now >= pending.expiresAtNanos()) {
                closePending(pending, ClaimAdmissionReservation.State.EXPIRED);
                return false;
            }
            if (!contextMatches || !topologyMatches
                    || !occupancyIndex.matchesTransitions(reservation.transitions())) {
                closePending(pending, ClaimAdmissionReservation.State.INVALIDATED);
                return false;
            }
            reservation.setState(ClaimAdmissionReservation.State.APPLYING);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Commits the exact reserved occupancy records and converts pending capacity atomically. */
    public boolean commit(@Nonnull ClaimAdmissionReservation reservation) {
        if (!Objects.requireNonNull(reservation, "reservation").belongsTo(reservationAuthority)) {
            return false;
        }
        lock.lock();
        try {
            if (reservation.state() == ClaimAdmissionReservation.State.COMMITTED) {
                return true;
            }
            if (reservation.state() != ClaimAdmissionReservation.State.APPLYING) {
                return false;
            }
            PendingAdmission pending = findPending(reservation);
            if (pending == null) {
                return false;
            }
            if (!occupancyIndex.applyTransitions(reservation.transitions())) {
                closePending(pending, ClaimAdmissionReservation.State.INVALIDATED);
                return false;
            }
            removePendingIndexes(pending);
            reservation.setState(ClaimAdmissionReservation.State.COMMITTED);
            metrics.reservationCommitted();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean cancel(@Nonnull ClaimAdmissionReservation reservation) {
        if (!Objects.requireNonNull(reservation, "reservation").belongsTo(reservationAuthority)) {
            return false;
        }
        lock.lock();
        try {
            if (reservation.state() == ClaimAdmissionReservation.State.CANCELED
                    || reservation.state() == ClaimAdmissionReservation.State.EXPIRED
                    || reservation.state() == ClaimAdmissionReservation.State.INVALIDATED) {
                return true;
            }
            if (reservation.state() == ClaimAdmissionReservation.State.COMMITTED) {
                return false;
            }
            PendingAdmission pending = findPending(reservation);
            if (pending == null) {
                return false;
            }
            closePending(pending, ClaimAdmissionReservation.State.CANCELED);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Invalidates unclaimed reservations captured under a different policy generation. */
    public int invalidateForPolicyContext(@Nonnull ClaimPolicyContext currentContext) {
        Objects.requireNonNull(currentContext, "currentContext");
        lock.lock();
        try {
            int invalidated = 0;
            for (PendingAdmission pending : Set.copyOf(pendingByToken.values())) {
                if (pending.reservation().state() == ClaimAdmissionReservation.State.RESERVED
                        && !ClaimAdmissionRules.sameStoredPolicy(pending.reservation(), currentContext)) {
                    closePending(pending, ClaimAdmissionReservation.State.INVALIDATED);
                    invalidated++;
                }
            }
            return invalidated;
        } finally {
            lock.unlock();
        }
    }

    public int expireReservations() {
        lock.lock();
        try {
            return expireReservationsLocked(observeNow());
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
    /** Fails positive admissions closed after a post-apply accounting failure. */
    public void markReadinessDegraded() { occupancyIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED); }
    public long pendingForClaim(@Nonnull ClaimPopulationKey key) {
        lock.lock();
        try {
            return pendingByClaim.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
        } finally {
            lock.unlock();
        }
    }
    /** Returns one consistent reservation/count snapshot plus lock-free snapshot timings. */
    @Nonnull
    public ClaimAdmissionMetrics.Snapshot metrics() {
        lock.lock();
        try {
            long pendingSlots = 0L;
            for (PendingAdmission pending : pendingByToken.values()) {
                pendingSlots += pending.reservation().reservedSlots();
            }
            return metrics.snapshot(occupancyIndex, pendingByToken.size(), pendingSlots);
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    private ClaimAdmissionDecision validatePreconditions(ClaimAdmissionRequest request) {
        if (!occupancyIndex.matchesTransitions(request.transitions())) {
            return denied(request, "claim-occupancy-state-mismatch", 0L, 0L, 0L, null);
        }
        for (ClaimOccupancyTransition transition : request.transitions()) {
            if (pendingTokenByProfile.containsKey(transition.profileId())) {
                return denied(request, "claim-profile-pending", 0L, 0L, 0L, null);
            }
        }
        return null;
    }

    @Nonnull
    private ClaimAdmissionDecision register(ClaimAdmissionRequest request,
                                            @Nullable ClaimResolution target,
                                            boolean topologyCheckRequired,
                                            long slots,
                                            long departures,
                                            long committed,
                                            long pending,
                                            ClaimCapEvaluator.Evaluation cap,
                                            long nowNanos) {
        long expiresAtNanos = addLease(nowNanos, request.leaseDurationNanos());
        ClaimAdmissionReservation reservation = new ClaimAdmissionReservation(
                UUID.randomUUID(),
                reservationAuthority,
                request,
                target,
                topologyCheckRequired,
                slots,
                expiresAtNanos
        );
        PendingAdmission pendingAdmission = new PendingAdmission(
                reservation,
                expiresAtNanos
        );
        pendingByToken.put(reservation.tokenId(), pendingAdmission);
        for (ClaimOccupancyTransition transition : request.transitions()) {
            pendingTokenByProfile.put(transition.profileId(), reservation.tokenId());
        }
        if (reservation.targetClaimKey() != null && slots > 0L) {
            pendingByClaim.merge(reservation.targetClaimKey(), slots, Long::sum);
        }
        metrics.reservationCreated();
        long before = cap.remainingHeadroom();
        long after = before == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, before - slots);
        return new ClaimAdmissionDecision(
                true,
                slots == 0L ? "claim-zero-delta-reserved" : "claim-capacity-reserved",
                reservation,
                occupancyIndex.readiness(),
                slots,
                committed,
                departures,
                pending,
                cap.perChunkCapacity(),
                cap.totalCapacity(),
                cap.effectiveCapacity(),
                before,
                after,
                cap.limitingConstraint(),
                slots == 0L,
                request.force()
        );
    }

    @Nonnull
    private ClaimAdmissionDecision denied(ClaimAdmissionRequest request,
                                          @Nullable String reason,
                                          long requestedSlots,
                                          long committed,
                                          long pending,
                                          @Nullable ClaimCapEvaluator.Evaluation cap) {
        ClaimCapEvaluator.Evaluation resolvedCap = cap == null
                ? ClaimCapEvaluator.evaluate(
                        request.limitPerClaimChunk(), request.limitPerClaimTotal(), 0, committed, pending
                )
                : cap;
        return new ClaimAdmissionDecision(
                false,
                reason == null ? "claim-admission-denied" : reason,
                null,
                occupancyIndex.readiness(),
                Math.max(0L, requestedSlots),
                Math.max(0L, committed),
                0L,
                Math.max(0L, pending),
                resolvedCap.perChunkCapacity(),
                resolvedCap.totalCapacity(),
                resolvedCap.effectiveCapacity(),
                resolvedCap.remainingHeadroom(),
                resolvedCap.remainingHeadroom(),
                resolvedCap.limitingConstraint(),
                requestedSlots == 0L,
                request.force()
        );
    }

    @Nullable
    private PendingAdmission findPending(ClaimAdmissionReservation reservation) {
        if (!reservation.belongsTo(reservationAuthority)) {
            return null;
        }
        PendingAdmission pending = pendingByToken.get(reservation.tokenId());
        return pending != null && pending.reservation() == reservation ? pending : null;
    }

    private int expireReservationsLocked(long nowNanos) {
        int expired = 0;
        for (PendingAdmission pending : Set.copyOf(pendingByToken.values())) {
            if (pending.reservation().state() == ClaimAdmissionReservation.State.RESERVED
                    && nowNanos >= pending.expiresAtNanos()) {
                closePending(pending, ClaimAdmissionReservation.State.EXPIRED);
                expired++;
            }
        }
        return expired;
    }

    private void closePending(PendingAdmission pending, ClaimAdmissionReservation.State terminalState) {
        removePendingIndexes(pending);
        pending.reservation().setState(terminalState);
        metrics.reservationClosed(terminalState);
    }

    private void removePendingIndexes(PendingAdmission pending) {
        ClaimAdmissionReservation reservation = pending.reservation();
        pendingByToken.remove(reservation.tokenId(), pending);
        for (ClaimOccupancyTransition transition : reservation.transitions()) {
            pendingTokenByProfile.remove(transition.profileId(), reservation.tokenId());
        }
        if (reservation.targetClaimKey() != null && reservation.reservedSlots() > 0L) {
            long updated = pendingByClaim.getOrDefault(reservation.targetClaimKey(), 0L)
                    - reservation.reservedSlots();
            if (updated < 0L) {
                throw new IllegalStateException("Claim pending population underflow.");
            }
            if (updated == 0L) {
                pendingByClaim.remove(reservation.targetClaimKey());
            } else {
                pendingByClaim.put(reservation.targetClaimKey(), updated);
            }
        }
    }

    private long observeNow() {
        long now = monotonicClock.getAsLong();
        if (lastObservedNanos != Long.MIN_VALUE && now < lastObservedNanos) {
            throw new IllegalStateException("Monotonic claim-admission clock moved backwards.");
        }
        lastObservedNanos = now;
        return now;
    }

    private long addLease(long now, long duration) {
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record PendingAdmission(@Nonnull ClaimAdmissionReservation reservation,
                                    long expiresAtNanos) {
    }
}
