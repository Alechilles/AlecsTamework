package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable provider/topology/snapshot work prepared before entering a combined reservation mutex.
 */
public record ClaimAdmissionEvaluation(
        @Nonnull ClaimAdmissionRequest request,
        @Nonnull Status status,
        @Nullable String denialReason,
        @Nullable ClaimResolution target,
        @Nullable ClaimPopulationSnapshot snapshot,
        boolean topologyCheckRequired
) {
    public ClaimAdmissionEvaluation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        if (status == Status.DENIED && (denialReason == null || denialReason.isBlank())) {
            throw new IllegalArgumentException("A denied claim evaluation requires a reason.");
        }
        if (status == Status.CLAIM_READY
                && (target == null || target.key() == null || snapshot == null)) {
            throw new IllegalArgumentException("A claim-ready evaluation requires target and snapshot data.");
        }
    }

    public enum Status {
        DENIED,
        UNCONSTRAINED,
        CLAIM_READY
    }
}
