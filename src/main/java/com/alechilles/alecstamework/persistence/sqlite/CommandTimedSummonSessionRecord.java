package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable lease and cooldown state for one owner-command-family roster member. */
public record CommandTimedSummonSessionRecord(
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        long rowRevision,
        @Nonnull State state,
        @Nullable String summonSessionId,
        @Nullable Long summonRemainingMs,
        long resummonCooldownUntilMs,
        @Nullable String summonConfigId,
        @Nullable Long summonConfigRevision,
        @Nonnull CommandTimedSummonPolicySnapshot summonPolicy,
        @Nonnull Set<Long> emittedWarningThresholdsMs,
        @Nullable Long summonLastCheckpointAtMs,
        @Nullable String activeOperationId,
        long createdAtMs,
        long updatedAtMs
) {
    public CommandTimedSummonSessionRecord {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(summonPolicy, "summonPolicy");
        emittedWarningThresholdsMs = emittedWarningThresholdsMs == null
                ? Set.of() : Set.copyOf(emittedWarningThresholdsMs);
        summonSessionId = normalizeText(summonSessionId);
        summonConfigId = normalizeText(summonConfigId);
        activeOperationId = normalizeText(activeOperationId);
        if (rowRevision < 1L) {
            throw new IllegalArgumentException("rowRevision must be positive.");
        }
        if (summonRemainingMs != null && summonRemainingMs < 0L) {
            throw new IllegalArgumentException("summonRemainingMs must be non-negative.");
        }
        if (resummonCooldownUntilMs < 0L) {
            throw new IllegalArgumentException("resummonCooldownUntilMs must be non-negative.");
        }
        if (summonConfigRevision != null && summonConfigRevision < 0L) {
            throw new IllegalArgumentException("summonConfigRevision must be non-negative.");
        }
        for (Long threshold : emittedWarningThresholdsMs) {
            if (threshold == null || threshold <= 0L) {
                throw new IllegalArgumentException("Emitted warning thresholds must be positive.");
            }
        }
        if (createdAtMs < 0L || updatedAtMs < createdAtMs) {
            throw new IllegalArgumentException("Session timestamps are invalid.");
        }
        if (state.hasProjectionSession()) {
            if (summonSessionId == null || summonLastCheckpointAtMs == null) {
                throw new IllegalArgumentException("Projected session states require session and checkpoint IDs.");
            }
        } else if (summonSessionId != null || summonRemainingMs != null || summonLastCheckpointAtMs != null) {
            throw new IllegalArgumentException("Dormant session states cannot retain an active lease.");
        }
        if (state != State.RESTORING && state != State.STORING && activeOperationId != null) {
            throw new IllegalArgumentException("Only transitional states may retain an active operation reservation.");
        }
    }

    /** Null means the configured active duration is unlimited. */
    public boolean unlimitedLease() {
        return state.hasProjectionSession() && summonRemainingMs == null;
    }

    public boolean cooldownActive(long nowMs) {
        return resummonCooldownUntilMs > Math.max(0L, nowMs);
    }

    /** Remaining lease at a monotonic wall-clock checkpoint without mutating the record. */
    @Nullable
    public Long remainingAt(long nowMs) {
        if (!state.hasProjectionSession() || summonRemainingMs == null) {
            return summonRemainingMs;
        }
        long elapsed = Math.max(0L, Math.max(0L, nowMs) - Objects.requireNonNull(summonLastCheckpointAtMs));
        return Math.max(0L, summonRemainingMs - elapsed);
    }

    public enum State {
        ROSTER_STORED,
        RESTORING,
        ACTIVE,
        UNLOADED,
        STORING,
        DEAD_REVIVABLE,
        LOST;

        public boolean hasProjectionSession() {
            return this == RESTORING || this == ACTIVE || this == UNLOADED || this == STORING;
        }

        public boolean occupiesActiveCapacity() {
            return hasProjectionSession();
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
