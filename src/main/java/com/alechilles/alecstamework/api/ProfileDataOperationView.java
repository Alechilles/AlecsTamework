package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Bounded, non-capability-bearing projection of a durable profile-data CAS operation. */
public record ProfileDataOperationView(@Nonnull UUID operationId,
                                       @Nonnull String namespace,
                                       @Nonnull String idempotencyKey,
                                       @Nonnull String profileId,
                                       @Nonnull String key,
                                       long expectedRevision,
                                       long resultingRevision,
                                       @Nonnull String payloadFingerprint,
                                       @Nonnull ProfileDataOperationStatus status,
                                       @Nonnull String reason,
                                       long updatedAtMs) {
    public static final long UNKNOWN_REVISION = -1L;

    public ProfileDataOperationView {
        operationId = Objects.requireNonNull(operationId, "operationId");
        namespace = ProfileDataValidation.requireText(namespace, "namespace", 128);
        idempotencyKey = ProfileDataValidation.requireText(idempotencyKey, "idempotencyKey", 256);
        profileId = ProfileDataValidation.requireText(profileId, "profileId", 256);
        key = ProfileDataValidation.requireText(key, "key", 256);
        payloadFingerprint = ProfileDataValidation.requireText(
                payloadFingerprint, "payloadFingerprint", 256);
        status = Objects.requireNonNull(status, "status");
        reason = ProfileDataValidation.requireText(reason, "reason", 512);
        if (expectedRevision < 0L || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException("expectedRevision must be between 0 and Long.MAX_VALUE - 1.");
        }
        if (resultingRevision < UNKNOWN_REVISION) {
            throw new IllegalArgumentException("resultingRevision must be -1 or non-negative.");
        }
        if (status == ProfileDataOperationStatus.COMMITTED
                && resultingRevision != expectedRevision + 1L) {
            throw new IllegalArgumentException("A committed CAS must publish expectedRevision + 1.");
        }
        if (status != ProfileDataOperationStatus.COMMITTED
                && resultingRevision != UNKNOWN_REVISION) {
            throw new IllegalArgumentException("Only a committed CAS may expose a resulting revision.");
        }
        if (updatedAtMs < 0L) {
            throw new IllegalArgumentException("updatedAtMs cannot be negative.");
        }
    }

    public boolean terminal() {
        return status == ProfileDataOperationStatus.COMMITTED
                || status == ProfileDataOperationStatus.TERMINAL_DENIED
                || status == ProfileDataOperationStatus.QUARANTINED;
    }
}
