package com.alechilles.alecstamework.ownership;

import java.util.Objects;

/**
 * Immutable result of attempting to reserve one owner population transition.
 *
 * <p>The reservation is non-null only when {@link #allowed()} is true. Reported counts are the
 * committed and pending values observed immediately before an accepted reservation is added.
 */
public record OwnerPopulationDecision(boolean allowed,
                                      String reason,
                                      OwnerPopulationReservation reservation,
                                      OwnerPopulationReadiness readiness,
                                      int limit,
                                      long committedCount,
                                      long pendingCount,
                                      long currentRevision,
                                      boolean positiveDelta,
                                      boolean forced) {

    public OwnerPopulationDecision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(readiness, "readiness");
        if (allowed && reservation == null) {
            throw new IllegalArgumentException("Allowed decisions require a reservation.");
        }
        if (!allowed && reservation != null) {
            throw new IllegalArgumentException("Denied decisions cannot expose a reservation.");
        }
        if (committedCount < 0L || pendingCount < 0L) {
            throw new IllegalArgumentException("Decision counts cannot be negative.");
        }
    }

    public long remainingHeadroom() {
        if (limit <= 0) {
            return Long.MAX_VALUE;
        }
        long accepted = allowed && positiveDelta ? 1L : 0L;
        return Math.max(0L, (long) limit - committedCount - pendingCount - accepted);
    }
}
