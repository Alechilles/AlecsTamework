package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import java.util.function.LongConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Performs provider lookup and population snapshot construction without holding admission locks.
 */
final class ClaimAdmissionEvaluator {
    private final ClaimOccupancyIndex occupancyIndex;
    private final ClaimPopulationSnapshotService snapshotService;
    private final LongConsumer snapshotDurationRecorder;

    ClaimAdmissionEvaluator(@Nonnull ClaimOccupancyIndex occupancyIndex,
                            @Nonnull ClaimPopulationSnapshotService snapshotService,
                            @Nonnull LongConsumer snapshotDurationRecorder) {
        this.occupancyIndex = Objects.requireNonNull(occupancyIndex, "occupancyIndex");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.snapshotDurationRecorder = Objects.requireNonNull(
                snapshotDurationRecorder,
                "snapshotDurationRecorder"
        );
    }

    @Nonnull
    ClaimAdmissionEvaluation evaluate(@Nonnull ClaimAdmissionRequest request,
                                      @Nonnull ClaimLookupSession lookupSession) {
        return evaluate(request, lookupSession, null);
    }

    @Nonnull
    ClaimAdmissionEvaluation evaluateForApply(@Nonnull ClaimAdmissionReservation reservation,
                                              @Nonnull ClaimLookupSession lookupSession) {
        ClaimAdmissionRequest rebuilt = reservation.rebuildRequest(lookupSession.context());
        return ClaimAdmissionRules.sameStoredPolicy(reservation, lookupSession.context())
                ? evaluate(rebuilt, lookupSession)
                : denied(rebuilt, "claim-policy-context-mismatch");
    }

    @Nonnull
    ClaimAdmissionEvaluation evaluate(@Nonnull ClaimAdmissionRequest request,
                                      @Nonnull ClaimLookupSession lookupSession,
                                      @Nullable ClaimOccupancySnapshot sharedSnapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lookupSession, "lookupSession");
        if (!ClaimAdmissionRules.samePolicy(request.policyContext(), lookupSession.context())) {
            return denied(request, "claim-policy-context-mismatch");
        }
        if (!request.policyEnabled()
                || (!request.requireClaim()
                && ClaimAdmissionRules.transitionsKnownNonPositive(request.transitions()))) {
            return unconstrained(request, null, false);
        }
        if (!occupancyIndex.readiness().allowsPositiveAdmissions() && !request.force()) {
            return denied(request, "claim-occupancy-not-ready");
        }
        ClaimResolution target = resolveTarget(request, lookupSession);
        if (target.status() == ClaimLookupResult.Status.NO_CLAIM) {
            return request.requireClaim()
                    ? denied(request, "claim-required")
                    : unconstrained(request, target, true);
        }
        if (target.status() == ClaimLookupResult.Status.UNAVAILABLE) {
            return denied(request, "claim-provider-unavailable");
        }
        if (target.status() == ClaimLookupResult.Status.ERROR || target.key() == null) {
            return denied(request, "claim-lookup-error");
        }
        if (request.limitPerClaimChunk() > 0
                && (target.footprint() == null || target.footprint().chunks().isEmpty())) {
            return denied(request, "claim-footprint-required");
        }
        if (request.limitPerClaimChunk() <= 0 && request.limitPerClaimTotal() <= 0) {
            return unconstrained(request, target, true);
        }
        ClaimPopulationSnapshot snapshot = snapshot(target, lookupSession, sharedSnapshot);
        if (snapshot.status() != ClaimPopulationSnapshot.Status.READY) {
            return denied(
                    request,
                    snapshot.status() == ClaimPopulationSnapshot.Status.UNAVAILABLE
                            ? "claim-provider-unavailable"
                            : "claim-population-snapshot-error"
            );
        }
        return new ClaimAdmissionEvaluation(
                request,
                ClaimAdmissionEvaluation.Status.CLAIM_READY,
                null,
                target,
                snapshot,
                true
        );
    }

    @Nonnull
    private ClaimPopulationSnapshot snapshot(@Nonnull ClaimResolution target,
                                             @Nonnull ClaimLookupSession lookupSession,
                                             @Nullable ClaimOccupancySnapshot sharedSnapshot) {
        long started = System.nanoTime();
        try {
            return sharedSnapshot == null
                    ? snapshotService.snapshot(occupancyIndex, target, lookupSession)
                    : snapshotService.snapshot(sharedSnapshot, target, lookupSession);
        } finally {
            snapshotDurationRecorder.accept(Math.max(0L, System.nanoTime() - started));
        }
    }

    @Nonnull
    private static ClaimResolution resolveTarget(@Nonnull ClaimAdmissionRequest request,
                                                 @Nonnull ClaimLookupSession lookupSession) {
        if (request.destinationChunk() == null) {
            return ClaimResolution.noClaim();
        }
        return lookupSession.resolveChunk(request.destinationChunk());
    }

    @Nonnull
    private static ClaimAdmissionEvaluation denied(@Nonnull ClaimAdmissionRequest request,
                                                   @Nonnull String reason) {
        return new ClaimAdmissionEvaluation(
                request,
                ClaimAdmissionEvaluation.Status.DENIED,
                reason,
                null,
                null,
                false
        );
    }

    @Nonnull
    private static ClaimAdmissionEvaluation unconstrained(@Nonnull ClaimAdmissionRequest request,
                                                          ClaimResolution target,
                                                          boolean topologyCheckRequired) {
        return new ClaimAdmissionEvaluation(
                request,
                ClaimAdmissionEvaluation.Status.UNCONSTRAINED,
                null,
                target,
                null,
                topologyCheckRequired
        );
    }
}
