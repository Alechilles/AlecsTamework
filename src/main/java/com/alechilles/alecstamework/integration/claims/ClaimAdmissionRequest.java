package com.alechilles.alecstamework.integration.claims;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable input for one claim admission reservation.
 *
 * <p>Batch transitions share one physical destination and therefore one claim policy snapshot.
 * Canonical profile IDs must be allocated before requesting the reservation.</p>
 */
public record ClaimAdmissionRequest(@Nonnull ClaimAdmissionOperation operation,
                                    @Nonnull List<ClaimOccupancyTransition> transitions,
                                    @Nullable ClaimChunkCoordinate destinationChunk,
                                    @Nonnull ClaimPolicyContext policyContext,
                                    int limitPerClaimChunk,
                                    int limitPerClaimTotal,
                                    boolean requireClaim,
                                    boolean force,
                                    long leaseDurationNanos) {
    public ClaimAdmissionRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(policyContext, "policyContext");
        if (transitions == null || transitions.isEmpty()) {
            throw new IllegalArgumentException("At least one occupancy transition is required.");
        }
        transitions = List.copyOf(transitions);
        Set<String> profiles = new HashSet<>();
        for (ClaimOccupancyTransition transition : transitions) {
            Objects.requireNonNull(transition, "transitions cannot contain null");
            if (!profiles.add(transition.profileId())) {
                throw new IllegalArgumentException("Duplicate profile transition: " + transition.profileId());
            }
            if (transition.proposed().occupiesClaim()
                    && !Objects.equals(destinationChunk, transition.proposed().physicalChunk())) {
                throw new IllegalArgumentException("Every physically occupying proposal must use the destination chunk.");
            }
        }
        limitPerClaimChunk = Math.max(0, limitPerClaimChunk);
        limitPerClaimTotal = Math.max(0, limitPerClaimTotal);
        if (leaseDurationNanos <= 0L) {
            throw new IllegalArgumentException("Claim admission leases must be positive.");
        }
    }

    public boolean capEnabled() {
        return limitPerClaimChunk > 0 || limitPerClaimTotal > 0;
    }

    public boolean policyEnabled() {
        return capEnabled() || requireClaim;
    }

    /** Compatibility constructor for admission-cap-only callers. */
    public ClaimAdmissionRequest(@Nonnull ClaimAdmissionOperation operation,
                                 @Nonnull List<ClaimOccupancyTransition> transitions,
                                 @Nullable ClaimChunkCoordinate destinationChunk,
                                 @Nonnull ClaimPolicyContext policyContext,
                                 int limitPerClaimChunk,
                                 int limitPerClaimTotal,
                                 boolean force,
                                 long leaseDurationNanos) {
        this(
                operation,
                transitions,
                destinationChunk,
                policyContext,
                limitPerClaimChunk,
                limitPerClaimTotal,
                false,
                force,
                leaseDurationNanos
        );
    }

    public boolean hasPhysicalDestination() {
        return destinationChunk != null;
    }

    @Nonnull
    public static ClaimAdmissionRequest single(@Nonnull ClaimAdmissionOperation operation,
                                               @Nonnull ClaimOccupancyTransition transition,
                                               @Nullable ClaimChunkCoordinate destinationChunk,
                                               @Nonnull ClaimPolicyContext policyContext,
                                               int limitPerClaimChunk,
                                               int limitPerClaimTotal,
                                               boolean force,
                                               long leaseDurationNanos) {
        return new ClaimAdmissionRequest(
                operation,
                List.of(transition),
                destinationChunk,
                policyContext,
                limitPerClaimChunk,
                limitPerClaimTotal,
                false,
                force,
                leaseDurationNanos
        );
    }
}
