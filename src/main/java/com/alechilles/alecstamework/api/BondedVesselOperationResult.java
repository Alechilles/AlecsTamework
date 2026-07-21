package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Result of one stage in a bonded-vessel transition. */
public record BondedVesselOperationResult(@Nonnull Status status,
                                          @Nonnull String reason,
                                          @Nullable UUID operationId,
                                          @Nullable BondedVesselTransitionToken token,
                                          @Nullable UUID bindingId,
                                          @Nullable String profileId,
                                          long generation,
                                          long profileRevision,
                                          @Nullable Long cooldownUntilMs,
                                          @Nullable BondedVesselState candidateState,
                                          @Nullable String candidateItemId,
                                          @Nullable String candidateItemFingerprint) {
    public static final long UNKNOWN = -1L;

    public BondedVesselOperationResult {
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason");
        profileId = profileId == null || profileId.isBlank() ? null : profileId.trim();
        candidateItemId = candidateItemId == null || candidateItemId.isBlank() ? null : candidateItemId.trim();
        candidateItemFingerprint = candidateItemFingerprint == null || candidateItemFingerprint.isBlank()
                ? null : candidateItemFingerprint.trim();
        requireKnownOrUnknown("generation", generation);
        requireKnownOrUnknown("profileRevision", profileRevision);
        boolean tokenRequired = status == Status.RESERVED
                || status == Status.APPLYING
                || status == Status.APPLIED;
        if (tokenRequired != (token != null)) {
            throw new IllegalArgumentException(
                    tokenRequired ? "Successful vessel stages require a token."
                            : "Closed/denied vessel stages cannot expose a token."
            );
        }
        if (tokenRequired && (operationId == null || candidateState == null
                || candidateItemId == null || candidateItemFingerprint == null)) {
            throw new IllegalArgumentException("Open vessel stages require operation and candidate projection identity.");
        }
    }

    public static BondedVesselOperationResult unavailable(@Nonnull String reason) {
        return new BondedVesselOperationResult(
                Status.UNAVAILABLE, reason, null, null, null, null,
                UNKNOWN, UNKNOWN, null, null, null, null
        );
    }

    public boolean accepted() {
        return status == Status.RESERVED || status == Status.APPLYING
                || status == Status.APPLIED || status == Status.COMMITTED;
    }

    private static void requireKnownOrUnknown(String field, long value) {
        if (value < 0L && value != UNKNOWN) {
            throw new IllegalArgumentException(field + " must be non-negative or UNKNOWN.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    public enum Status {
        RESERVED,
        APPLYING,
        APPLIED,
        COMMITTED,
        CANCELED,
        DENIED,
        UNAVAILABLE,
        QUARANTINED
    }
}
