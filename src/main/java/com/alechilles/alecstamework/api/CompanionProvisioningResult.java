package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable result of companion provisioning or a provisioned-profile transition. */
public record CompanionProvisioningResult(@Nonnull Status status,
                                          @Nonnull String reason,
                                          @Nullable String callerNamespace,
                                          @Nullable String idempotencyKey,
                                          @Nullable UUID operationId,
                                          @Nullable String profileId,
                                          @Nullable UUID ownerUuid,
                                          @Nullable String roleId,
                                          @Nullable PopulationCompanionLifecycle lifecycle,
                                          @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
                                          @Nonnull String projectionReason,
                                          @Nullable PopulationAdmissionDecision populationDecision,
                                          long profileRevision) {
    public static final long UNKNOWN_PROFILE_REVISION = -1L;

    public CompanionProvisioningResult {
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason");
        callerNamespace = normalizeBlank(callerNamespace);
        idempotencyKey = normalizeBlank(idempotencyKey);
        profileId = normalizeBlank(profileId);
        roleId = normalizeBlank(roleId);
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
        projectionReason = requireText(projectionReason, "projectionReason");
        if (profileRevision < UNKNOWN_PROFILE_REVISION) {
            throw new IllegalArgumentException("Profile revision must be -1 or non-negative.");
        }
        boolean successful = status == Status.PROVISIONED_ACTIVE
                || status == Status.PROVISIONED_DORMANT
                || status == Status.PARTIAL_DORMANT
                || status == Status.ALREADY_PROVISIONED
                || status == Status.TRANSITIONED || status == Status.ALREADY_TRANSITIONED;
        if (successful && (callerNamespace == null || idempotencyKey == null
                || operationId == null || profileId == null || ownerUuid == null
                || roleId == null || lifecycle == null || profileRevision < 0L)) {
            throw new IllegalArgumentException("Successful provisioning results require canonical identity.");
        }
        if (status == Status.PROVISIONED_ACTIVE
                && (lifecycle != PopulationCompanionLifecycle.ACTIVE
                || projectionStatus != CompanionProvisioningProjectionStatus.ACTIVE)) {
            throw new IllegalArgumentException("PROVISIONED_ACTIVE requires an active canonical projection.");
        }
        if (status == Status.PROVISIONED_DORMANT
                && (lifecycle != PopulationCompanionLifecycle.PROVISIONED_DORMANT
                || projectionStatus != CompanionProvisioningProjectionStatus.NOT_REQUESTED)) {
            throw new IllegalArgumentException("PROVISIONED_DORMANT requires a dormant profile without projection.");
        }
        if (status == Status.PARTIAL_DORMANT
                && (lifecycle != PopulationCompanionLifecycle.PROVISIONED_DORMANT
                || projectionStatus != CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE)) {
            throw new IllegalArgumentException("PARTIAL_DORMANT requires a recoverable dormant profile.");
        }
        if (successful && lifecycle == PopulationCompanionLifecycle.ACTIVE
                && projectionStatus != CompanionProvisioningProjectionStatus.ACTIVE) {
            throw new IllegalArgumentException("An active profile requires an active projection result.");
        }
        if (successful && lifecycle == PopulationCompanionLifecycle.PROVISIONED_DORMANT
                && projectionStatus == CompanionProvisioningProjectionStatus.ACTIVE) {
            throw new IllegalArgumentException("A dormant profile cannot report an active projection.");
        }
    }

    public static CompanionProvisioningResult unavailable(@Nonnull String reason) {
        return new CompanionProvisioningResult(
                Status.UNAVAILABLE,
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CompanionProvisioningProjectionStatus.UNAVAILABLE,
                reason,
                null,
                UNKNOWN_PROFILE_REVISION
        );
    }

    public boolean accepted() {
        return status == Status.PROVISIONED_ACTIVE
                || status == Status.PROVISIONED_DORMANT
                || status == Status.PARTIAL_DORMANT
                || status == Status.ALREADY_PROVISIONED
                || status == Status.TRANSITIONED || status == Status.ALREADY_TRANSITIONED;
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    public enum Status {
        PROVISIONED_ACTIVE,
        PROVISIONED_DORMANT,
        PARTIAL_DORMANT,
        ALREADY_PROVISIONED,
        TRANSITIONED,
        ALREADY_TRANSITIONED,
        DENIED,
        UNAVAILABLE,
        QUARANTINED
    }
}
