package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable claim-admission result and coordinator-facing headroom view.
 */
public record ClaimAdmissionDecision(boolean allowed,
                                     @Nonnull String reason,
                                     @Nullable ClaimAdmissionReservation reservation,
                                     @Nonnull ClaimOccupancyReadiness readiness,
                                     long requestedSlots,
                                     long committedPopulation,
                                     long creditedDepartures,
                                     long pendingPopulation,
                                     long perChunkCapacity,
                                     long totalCapacity,
                                     long effectiveCapacity,
                                     long headroomBeforeReservation,
                                     long headroomAfterReservation,
                                     @Nonnull ClaimCapEvaluator.LimitingConstraint limitingConstraint,
                                     boolean zeroDelta,
                                     boolean forced) {
}
