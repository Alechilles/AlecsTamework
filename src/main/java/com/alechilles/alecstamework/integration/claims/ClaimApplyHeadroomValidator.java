package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates a delayed claim reservation against one freshly built occupancy evaluation. */
final class ClaimApplyHeadroomValidator {
    private final ClaimOccupancyIndex occupancyIndex;

    ClaimApplyHeadroomValidator(@Nonnull ClaimOccupancyIndex occupancyIndex) {
        this.occupancyIndex = Objects.requireNonNull(occupancyIndex, "occupancyIndex");
    }

    @Nonnull
    Result validate(@Nonnull ClaimAdmissionReservation reservation,
                    @Nonnull ClaimAdmissionEvaluation refreshed,
                    long allPendingForClaim) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(refreshed, "refreshed");
        if (refreshed.status() == ClaimAdmissionEvaluation.Status.DENIED
                || (reservation.topologyCheckRequired()
                && !ClaimAdmissionRules.sameTopology(reservation, refreshed.target()))) {
            return Result.invalid();
        }
        ClaimPopulationSnapshot snapshot = refreshed.snapshot();
        if (refreshed.status() != ClaimAdmissionEvaluation.Status.CLAIM_READY || snapshot == null) {
            return new Result(occupancyIndex.matchesTransitions(reservation.transitions()), null, false);
        }
        ClaimAdmissionRequest request = refreshed.request();
        if (request.force()) {
            return new Result(occupancyIndex.matchesTransitions(reservation.transitions()), null, false);
        }
        if (!occupancyIndex.matchesSnapshotAndTransitions(
                snapshot.occupancyRevision(), reservation.transitions())) {
            return Result.invalid();
        }
        ClaimAdmissionRules.TransitionDelta delta = ClaimAdmissionRules.analyzeTransitions(
                reservation.transitions(), snapshot.profileIds()
        );
        long ownPending = reservation.reservedSlots();
        if (delta.arrivals() != ownPending || allPendingForClaim < ownPending) {
            return Result.invalid();
        }
        long otherPending = allPendingForClaim - ownPending;
        long adjustedCommitted = Math.max(0L, snapshot.population() - delta.departures());
        ClaimResolution target = refreshed.target();
        int claimChunks = target == null || target.footprint() == null
                ? 0
                : target.footprint().chunkCount();
        ClaimCapEvaluator.Evaluation cap = ClaimCapEvaluator.evaluate(
                request.limitPerClaimChunk(),
                request.limitPerClaimTotal(),
                claimChunks,
                adjustedCommitted,
                otherPending
        );
        boolean overCap = cap.active() && adjustedCommitted > cap.effectiveCapacity();
        return new Result(cap.valid() && cap.admits(delta.arrivals()), snapshot.claimKey(), overCap);
    }

    record Result(boolean valid,
                  @Nullable ClaimPopulationKey claimKey,
                  boolean overCap) {
        private static Result invalid() {
            return new Result(false, null, false);
        }
    }
}
