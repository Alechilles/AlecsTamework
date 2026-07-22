package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable durable view of one exactly-once capture attempt and its pinned evidence. */
public record CaptureAttemptRecord(
        @Nonnull Identity identity,
        @Nonnull ConfigEvidence config,
        @Nonnull State state,
        @Nullable Resolution resolution,
        @Nullable String populationOperationId,
        @Nullable String captureOperationId,
        long eventEmittedAtMs,
        @Nonnull String recoveryStatus,
        long expiresAtMs,
        long createdAtMs,
        long updatedAtMs,
        long completedAtMs,
        @Nullable String lastError,
        @Nonnull SourceSpend sourceSpend
) {
    /** Source-compatible constructor for rows/tests created before source-spend journaling. */
    public CaptureAttemptRecord(Identity identity, ConfigEvidence config, State state,
                                Resolution resolution, String populationOperationId,
                                String captureOperationId, long eventEmittedAtMs,
                                String recoveryStatus, long expiresAtMs, long createdAtMs,
                                long updatedAtMs, long completedAtMs, String lastError) {
        this(identity, config, state, resolution, populationOperationId, captureOperationId,
                eventEmittedAtMs, recoveryStatus, expiresAtMs, createdAtMs, updatedAtMs,
                completedAtMs, lastError, SourceSpend.NOT_REQUIRED);
    }

    public enum State {
        PREPARED,
        RESOLVED_FAILURE,
        RESOLVED_SUCCESS,
        APPLYING,
        COMMITTED,
        CANCELED,
        COMPENSATING,
        QUARANTINED;

        public boolean isTerminal() {
            return this == RESOLVED_FAILURE || this == COMMITTED || this == CANCELED;
        }

        public boolean canTransitionTo(@Nonnull State next) {
            return switch (this) {
                case PREPARED -> next == RESOLVED_FAILURE || next == RESOLVED_SUCCESS
                        || next == CANCELED || next == QUARANTINED;
                case RESOLVED_SUCCESS -> next == APPLYING || next == COMPENSATING
                        || next == QUARANTINED;
                case APPLYING -> next == COMMITTED || next == COMPENSATING
                        || next == QUARANTINED;
                case COMPENSATING -> next == CANCELED || next == QUARANTINED;
                case QUARANTINED -> next == APPLYING || next == COMPENSATING
                        || next == CANCELED;
                case RESOLVED_FAILURE, COMMITTED, CANCELED -> false;
            };
        }
    }

    /** Identity and source fences allocated before the first asynchronous hop. */
    public record Identity(
            @Nonnull String attemptId,
            @Nullable String callerNamespace,
            @Nullable String idempotencyKey,
            @Nonnull UUID actorUuid,
            @Nonnull UUID targetNpcUuid,
            @Nullable String profileId,
            @Nullable Long expectedProfileRevision,
            @Nonnull String sourceItemId,
            @Nullable String sourceRoleId,
            @Nonnull String sourceContextJson
    ) {
        public Identity {
            attemptId = requireText(attemptId, "attemptId");
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
            profileId = normalize(profileId);
            sourceItemId = requireText(sourceItemId, "sourceItemId");
            sourceRoleId = normalize(sourceRoleId);
            sourceContextJson = requireText(sourceContextJson, "sourceContextJson");
            callerNamespace = normalize(callerNamespace);
            idempotencyKey = normalize(idempotencyKey);
            if ((callerNamespace == null) != (idempotencyKey == null)) {
                throw new IllegalArgumentException("callerNamespace and idempotencyKey must both be present or absent.");
            }
            if (expectedProfileRevision != null && expectedProfileRevision < 0L) {
                throw new IllegalArgumentException("expectedProfileRevision must be non-negative.");
            }
        }
    }

    /** Immutable asset revisions selected before an attempt can be resolved. */
    public record ConfigEvidence(
            @Nonnull String spawnerConfigId,
            long spawnerConfigRevision,
            @Nullable String targetPolicyConfigId,
            @Nullable Long targetPolicyConfigRevision,
            boolean targetPolicyBypassed,
            boolean guaranteed,
            @Nonnull CaptureSourceConsumption sourceConsumption,
            @Nonnull CaptureSuccessDisposition successDisposition,
            @Nullable String commandFamilyId,
            @Nullable String requiredCommandConfigId,
            boolean requireCommandAccessItem
    ) {
        /** Source-compatible constructor for legacy/default captured-item attempts. */
        public ConfigEvidence(String spawnerConfigId, long spawnerConfigRevision,
                              String targetPolicyConfigId, Long targetPolicyConfigRevision,
                              boolean targetPolicyBypassed, boolean guaranteed) {
            this(spawnerConfigId, spawnerConfigRevision, targetPolicyConfigId,
                    targetPolicyConfigRevision, targetPolicyBypassed, guaranteed,
                    CaptureSourceConsumption.SUCCESS_ONLY,
                    CaptureSuccessDisposition.CAPTURED_ITEM, null, null, false);
        }

        public ConfigEvidence {
            spawnerConfigId = requireText(spawnerConfigId, "spawnerConfigId");
            targetPolicyConfigId = normalize(targetPolicyConfigId);
            sourceConsumption = Objects.requireNonNull(sourceConsumption, "sourceConsumption");
            successDisposition = Objects.requireNonNull(successDisposition, "successDisposition");
            commandFamilyId = normalize(commandFamilyId);
            requiredCommandConfigId = normalize(requiredCommandConfigId);
            if (spawnerConfigRevision < 0L) {
                throw new IllegalArgumentException("spawnerConfigRevision must be non-negative.");
            }
            if (targetPolicyConfigRevision != null && targetPolicyConfigRevision < 0L) {
                throw new IllegalArgumentException("targetPolicyConfigRevision must be non-negative.");
            }
            if ((targetPolicyConfigId == null) != (targetPolicyConfigRevision == null)) {
                throw new IllegalArgumentException("Target policy ID and revision must both be present or absent.");
            }
            if (targetPolicyBypassed && targetPolicyConfigId != null) {
                throw new IllegalArgumentException("A bypassed target policy cannot carry a resolved policy revision.");
            }
            if (guaranteed && !targetPolicyBypassed) {
                throw new IllegalArgumentException("Guaranteed capture must explicitly bypass target policy.");
            }
            if (successDisposition == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK
                    && commandFamilyId == null) {
                throw new IllegalArgumentException("TameAndCommandLink requires CommandFamilyId.");
            }
            if (requireCommandAccessItem && requiredCommandConfigId == null) {
                throw new IllegalArgumentException(
                        "RequireCommandAccessItem requires RequiredCommandConfigId.");
            }
        }
    }

    public enum SourceSpendState {
        NOT_REQUIRED,
        PENDING,
        CONSUMED
    }

    /** Durable exact-stack evidence for consumption that must precede result publication/apply. */
    public record SourceSpend(@Nonnull SourceSpendState state,
                              @Nullable String beforeFingerprint,
                              @Nullable String afterFingerprint,
                              long consumedAtMs) {
        public static final SourceSpend NOT_REQUIRED = new SourceSpend(
                SourceSpendState.NOT_REQUIRED, null, null, 0L);

        public SourceSpend {
            state = Objects.requireNonNull(state, "state");
            beforeFingerprint = normalize(beforeFingerprint);
            afterFingerprint = normalize(afterFingerprint);
            if (consumedAtMs < 0L) {
                throw new IllegalArgumentException("consumedAtMs must be non-negative.");
            }
            if (state == SourceSpendState.NOT_REQUIRED
                    && (beforeFingerprint != null || afterFingerprint != null || consumedAtMs != 0L)) {
                throw new IllegalArgumentException("NOT_REQUIRED source spend cannot carry evidence.");
            }
            if (state != SourceSpendState.NOT_REQUIRED
                    && (beforeFingerprint == null || afterFingerprint == null)) {
                throw new IllegalArgumentException("Pending/consumed source spend requires both fingerprints.");
            }
            if ((state == SourceSpendState.CONSUMED) != (consumedAtMs > 0L)) {
                throw new IllegalArgumentException(
                        "Only consumed source spend carries a positive consumption timestamp.");
            }
        }
    }

    /** Formula evidence recorded by the one terminal entropy boundary. */
    public record Resolution(
            double power,
            double minimumPower,
            double currentHealth,
            double maximumHealth,
            double missingHealthFraction,
            double conditionBonus,
            double effectiveChance,
            @Nullable Double entropySample,
            @Nullable String outcome,
            @Nonnull String reasonCode,
            long failureCooldownUntilMs,
            long resolvedAtMs
    ) {
        public Resolution {
            requireFinite(power, "power");
            requireFinite(minimumPower, "minimumPower");
            requireFinite(currentHealth, "currentHealth");
            requireFinite(maximumHealth, "maximumHealth");
            requireUnit(missingHealthFraction, "missingHealthFraction");
            requireFinite(conditionBonus, "conditionBonus");
            requireUnit(effectiveChance, "effectiveChance");
            if (entropySample != null) {
                requireFinite(entropySample, "entropySample");
                if (entropySample < 0.0 || entropySample >= 1.0) {
                    throw new IllegalArgumentException("entropySample must be in [0, 1).");
                }
            }
            outcome = normalize(outcome);
            reasonCode = requireText(reasonCode, "reasonCode");
        }
    }

    public CaptureAttemptRecord {
        identity = Objects.requireNonNull(identity, "identity");
        config = Objects.requireNonNull(config, "config");
        state = Objects.requireNonNull(state, "state");
        sourceSpend = Objects.requireNonNull(sourceSpend, "sourceSpend");
        recoveryStatus = requireText(recoveryStatus, "recoveryStatus");
        if (state != State.PREPARED && resolution == null && state != State.CANCELED) {
            throw new IllegalArgumentException("Resolved or applying attempts require resolution evidence.");
        }
        if (config.guaranteed() && resolution != null && resolution.entropySample() != null) {
            throw new IllegalArgumentException("Guaranteed capture must not record entropy.");
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite.");
        }
    }

    private static void requireUnit(double value, String field) {
        requireFinite(value, field);
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be in [0, 1].");
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
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
