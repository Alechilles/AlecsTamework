package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Result of one stage in the mutation-bound admission lifecycle. */
public record PopulationAdmissionDecision(@Nonnull Status status,
                                          @Nonnull String reason,
                                          @Nullable PopulationAdmissionToken token,
                                          @Nonnull OwnerPopulationCapDecisionViewV2.Readiness readiness,
                                          long committedCount,
                                          long pendingCount) {
    public PopulationAdmissionDecision {
        status = Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNull(reason, "reason");
        readiness = Objects.requireNonNull(readiness, "readiness");
        requireKnownOrUnknown("committedCount", committedCount);
        requireKnownOrUnknown("pendingCount", pendingCount);
        boolean tokenRequired = status == Status.RESERVED
                || status == Status.APPLYING
                || status == Status.COMMITTED;
        if (tokenRequired != (token != null)) {
            throw new IllegalArgumentException(
                    tokenRequired ? "Successful admission stages require a token." : "Denied/closed stages cannot expose a token."
            );
        }
    }

    @Nonnull
    public static PopulationAdmissionDecision unavailable(@Nonnull String reason) {
        return new PopulationAdmissionDecision(
                Status.UNAVAILABLE,
                reason,
                null,
                OwnerPopulationCapDecisionViewV2.Readiness.UNAVAILABLE,
                OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT,
                OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT
        );
    }

    public boolean accepted() {
        return status == Status.RESERVED || status == Status.APPLYING || status == Status.COMMITTED;
    }

    private static void requireKnownOrUnknown(String field, long value) {
        if (value < 0L && value != OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT) {
            throw new IllegalArgumentException(field + " must be non-negative or UNKNOWN_COUNT.");
        }
    }

    public enum Status {
        RESERVED,
        APPLYING,
        COMMITTED,
        CANCELED,
        DENIED,
        UNAVAILABLE
    }
}
