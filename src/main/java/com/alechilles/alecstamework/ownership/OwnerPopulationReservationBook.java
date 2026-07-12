package com.alechilles.alecstamework.ownership;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.addCounts;
import static com.alechilles.alecstamework.ownership.OwnerPopulationCountOps.removeCounts;

/**
 * Owns reservation tokens, monotonic leases, and terminal pending-count bookkeeping.
 * Every method is invoked while {@link OwnerPopulationIndex}'s short in-memory lock is held.
 */
final class OwnerPopulationReservationBook {
    private final Object authority = new Object();
    private final LongSupplier monotonicClock;
    private final Map<OwnerPopulationScopeKey, Long> pendingCounts;
    private final Map<UUID, OwnerPopulationPendingTransition> pendingByToken;
    private final Map<String, UUID> pendingTokenByProfile;
    private final OwnerPopulationMetrics metrics;
    private long lastObservedNanos = Long.MIN_VALUE;

    OwnerPopulationReservationBook(
            LongSupplier monotonicClock,
            Map<OwnerPopulationScopeKey, Long> pendingCounts,
            Map<UUID, OwnerPopulationPendingTransition> pendingByToken,
            Map<String, UUID> pendingTokenByProfile,
            OwnerPopulationMetrics metrics
    ) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.pendingCounts = Objects.requireNonNull(pendingCounts, "pendingCounts");
        this.pendingByToken = Objects.requireNonNull(pendingByToken, "pendingByToken");
        this.pendingTokenByProfile = Objects.requireNonNull(
                pendingTokenByProfile, "pendingTokenByProfile"
        );
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    OwnerPopulationReservation newReservation() {
        return new OwnerPopulationReservation(UUID.randomUUID(), authority);
    }

    boolean owns(OwnerPopulationReservation reservation) {
        return reservation != null && reservation.belongsTo(authority);
    }

    void register(OwnerPopulationPendingTransition transition) {
        OwnerPopulationReservation reservation = transition.reservation();
        pendingByToken.put(reservation.tokenId(), transition);
        pendingTokenByProfile.put(
                transition.request().profileId(), reservation.tokenId()
        );
        addCounts(pendingCounts, transition.additions());
        metrics.reservationCreated();
    }

    OwnerPopulationPendingTransition find(OwnerPopulationReservation reservation) {
        OwnerPopulationPendingTransition transition = pendingByToken.get(reservation.tokenId());
        return transition != null && transition.reservation() == reservation ? transition : null;
    }

    boolean hasProfile(String profileId) {
        return pendingTokenByProfile.containsKey(profileId);
    }

    boolean hasApplyingOwnerClear(String profileId, UUID observedOwnerId) {
        UUID token = pendingTokenByProfile.get(profileId);
        OwnerPopulationPendingTransition transition = token == null
                ? null
                : pendingByToken.get(token);
        return transition != null
                && transition.reservation().state()
                == OwnerPopulationReservation.ReservationState.APPLYING
                && Objects.equals(transition.request().expectedOwnerId(), observedOwnerId)
                && transition.request().newOwnerId() == null;
    }

    int expireReserved(long nowNanos) {
        int expired = 0;
        for (OwnerPopulationPendingTransition transition : Set.copyOf(pendingByToken.values())) {
            if (transition.reservation().state()
                    != OwnerPopulationReservation.ReservationState.RESERVED
                    || !isExpired(transition, nowNanos)) {
                continue;
            }
            close(transition, OwnerPopulationReservation.ReservationState.EXPIRED);
            expired++;
        }
        return expired;
    }

    boolean isExpired(OwnerPopulationPendingTransition transition, long nowNanos) {
        return nowNanos >= transition.expiresAtNanos();
    }

    void commit(OwnerPopulationPendingTransition transition) {
        removeCounts(pendingCounts, transition.additions());
        removeIndexes(transition);
        transition.reservation().setState(
                OwnerPopulationReservation.ReservationState.COMMITTED
        );
        metrics.reservationCommitted();
    }

    void close(
            OwnerPopulationPendingTransition transition,
            OwnerPopulationReservation.ReservationState terminalState
    ) {
        removeCounts(pendingCounts, transition.additions());
        removeIndexes(transition);
        transition.reservation().setState(terminalState);
        if (terminalState == OwnerPopulationReservation.ReservationState.CANCELED) {
            metrics.reservationCanceled();
        } else if (terminalState == OwnerPopulationReservation.ReservationState.EXPIRED) {
            metrics.reservationExpired();
        }
    }

    long observeNow() {
        long nowNanos = monotonicClock.getAsLong();
        if (lastObservedNanos != Long.MIN_VALUE && nowNanos < lastObservedNanos) {
            throw new IllegalStateException("Monotonic owner-population clock moved backwards.");
        }
        lastObservedNanos = nowNanos;
        return nowNanos;
    }

    long expiresAt(long nowNanos, long leaseDurationNanos) {
        try {
            return Math.addExact(nowNanos, leaseDurationNanos);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    boolean isEmpty() {
        return pendingByToken.isEmpty();
    }

    private void removeIndexes(OwnerPopulationPendingTransition transition) {
        pendingByToken.remove(transition.reservation().tokenId(), transition);
        pendingTokenByProfile.remove(
                transition.request().profileId(), transition.reservation().tokenId()
        );
    }
}
