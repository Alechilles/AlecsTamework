package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable proof used to terminalize a paid revival after its deterministic projection is live.
 *
 * <p>The paid operation, death source, roster state, and optional timed lease are committed by one
 * SQLite transaction. A caller must not treat a live entity alone as successful revival proof.</p>
 */
public record PaidCommandRevivalApplyCommit(
        @Nonnull UUID operationId,
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        @Nonnull UUID projectionNpcUuid,
        long expectedDeathRevision,
        @Nullable TimedLease timedLease,
        long nowMs
) {
    public PaidCommandRevivalApplyCommit {
        operationId = Objects.requireNonNull(operationId, "operationId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        projectionNpcUuid = Objects.requireNonNull(projectionNpcUuid, "projectionNpcUuid");
        if (expectedDeathRevision < 0L) {
            throw new IllegalArgumentException("expectedDeathRevision cannot be negative");
        }
        if (nowMs < 0L) throw new IllegalArgumentException("nowMs cannot be negative");
    }

    /** Exact lease snapshot installed in the same transaction when timed summoning is enabled. */
    public record TimedLease(
            @Nonnull String sessionId,
            @Nullable Long remainingMs,
            @Nullable String configId,
            @Nullable Long configRevision,
            @Nonnull CommandTimedSummonPolicySnapshot policy
    ) {
        public TimedLease {
            sessionId = requireText(sessionId, "sessionId");
            if (remainingMs != null && remainingMs < 0L) {
                throw new IllegalArgumentException("remainingMs cannot be negative");
            }
            configId = normalize(configId);
            if (configRevision != null && configRevision < 0L) {
                throw new IllegalArgumentException("configRevision cannot be negative");
            }
            policy = Objects.requireNonNull(policy, "policy");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
