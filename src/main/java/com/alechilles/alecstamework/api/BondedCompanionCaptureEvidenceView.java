package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable proof that a bonded capture durably created its stored profile.
 *
 * <p>This proof does not claim that later source-NPC cleanup or capture-item
 * finalization has completed. It is retained with the bonded profile's
 * dedicated source authority so operation-history pruning cannot release the
 * captured NPC identity for reuse.</p>
 */
public record BondedCompanionCaptureEvidenceView(
        @Nonnull UUID operationId,
        @Nonnull UUID attemptId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String familyId,
        @Nonnull UUID sourceNpcUuid,
        @Nonnull String profileId,
        @Nonnull String roleId,
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull String sourceItemId,
        @Nonnull String spawnerConfigId,
        long spawnerConfigRevision,
        @Nullable String capturePolicyConfigId,
        long capturePolicyConfigRevision,
        @Nonnull CaptureSourceConsumption sourceConsumption,
        @Nonnull CaptureSuccessDisposition successDisposition,
        @Nonnull CaptureAttemptOutcome outcome,
        @Nonnull String reason,
        @Nonnull String sourceWorldKey,
        long committedAtMs
) {
    public BondedCompanionCaptureEvidenceView {
        operationId = Objects.requireNonNull(operationId, "operationId");
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = text(rosterId, "rosterId");
        familyId = text(familyId, "familyId");
        sourceNpcUuid = Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        profileId = text(profileId, "profileId");
        roleId = text(roleId, "roleId");
        callerNamespace = text(callerNamespace, "callerNamespace");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        sourceItemId = text(sourceItemId, "sourceItemId");
        spawnerConfigId = text(spawnerConfigId, "spawnerConfigId");
        capturePolicyConfigId = optional(capturePolicyConfigId);
        sourceConsumption = Objects.requireNonNull(
                sourceConsumption, "sourceConsumption");
        successDisposition = Objects.requireNonNull(
                successDisposition, "successDisposition");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reason = text(reason, "reason");
        sourceWorldKey = text(sourceWorldKey, "sourceWorldKey");
        if (spawnerConfigRevision < 0L
                || capturePolicyConfigRevision < -1L
                || (capturePolicyConfigId == null)
                != (capturePolicyConfigRevision == -1L)) {
            throw new IllegalArgumentException(
                    "Capture config evidence is inconsistent.");
        }
        if (successDisposition
                != CaptureSuccessDisposition.STORE_BONDED_COMPANION
                || outcome != CaptureAttemptOutcome.CAPTURED) {
            throw new IllegalArgumentException(
                    "Bonded capture evidence requires a durable stored success.");
        }
    }

    private static String text(String value, String field) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
