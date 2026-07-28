package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Post-commit notification for one durably resolved capture attempt. */
public record CaptureAttemptResolvedEvent(@Nonnull UUID attemptId,
                                          @Nonnull UUID operationId,
                                          @Nonnull UUID actorUuid,
                                          @Nonnull UUID targetNpcUuid,
                                          @Nullable String profileId,
                                          @Nonnull String roleId,
                                          @Nonnull String sourceItemId,
                                          @Nonnull String spawnerConfigId,
                                          long spawnerConfigRevision,
                                          @Nullable String capturePolicyConfigId,
                                          long capturePolicyRevision,
                                          int power,
                                          int minimumPower,
                                          @Nullable Double currentHealth,
                                          @Nullable Double maximumHealth,
                                          double missingHealthFraction,
                                          double configuredConditionBonus,
                                          double effectiveChance,
                                          boolean guaranteed,
                                          @Nonnull CaptureAttemptOutcome outcome,
                                          @Nonnull String reason,
                                          long resolvedAtMs,
                                          long emittedAtMs,
                                          @Nullable CaptureAttemptReplayEvidence replayEvidence)
        implements TameworkEvent {
    public CaptureAttemptResolvedEvent {
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
        roleId = requireText(roleId, "roleId");
        sourceItemId = requireText(sourceItemId, "sourceItemId");
        spawnerConfigId = requireText(spawnerConfigId, "spawnerConfigId");
        capturePolicyConfigId = capturePolicyConfigId == null || capturePolicyConfigId.isBlank()
                ? null
                : capturePolicyConfigId.trim();
        outcome = Objects.requireNonNull(outcome, "outcome");
        reason = requireText(reason, "reason");
        if (spawnerConfigRevision < 0L || capturePolicyRevision < -1L) {
            throw new IllegalArgumentException("Config revisions must be non-negative or unknown.");
        }
        if ((capturePolicyConfigId == null) != (capturePolicyRevision == -1L)) {
            throw new IllegalArgumentException("Target-policy id and revision must be present together.");
        }
        if (power < 0 || minimumPower < 0) {
            throw new IllegalArgumentException("Capture power values cannot be negative.");
        }
        if ((currentHealth == null) != (maximumHealth == null)) {
            throw new IllegalArgumentException(
                    "Current and maximum health must appear together."
            );
        }
        if (currentHealth != null) {
            validateFinite("currentHealth", currentHealth);
            validateFinite("maximumHealth", maximumHealth);
            if (maximumHealth <= 0.0D || currentHealth < 0.0D
                    || currentHealth > maximumHealth) {
                throw new IllegalArgumentException(
                        "Current health must be within a positive maximum health range."
                );
            }
        }
        validateUnit("missingHealthFraction", missingHealthFraction);
        validateFinite("configuredConditionBonus", configuredConditionBonus);
        if (configuredConditionBonus < 0.0D) {
            throw new IllegalArgumentException("Configured condition bonus cannot be negative.");
        }
        validateUnit("effectiveChance", effectiveChance);
    }

    /** Source-compatible constructor for callers compiled against the original API event. */
    public CaptureAttemptResolvedEvent(
            UUID attemptId,
            UUID operationId,
            UUID actorUuid,
            UUID targetNpcUuid,
            String profileId,
            String roleId,
            String sourceItemId,
            String spawnerConfigId,
            long spawnerConfigRevision,
            String capturePolicyConfigId,
            long capturePolicyRevision,
            int power,
            int minimumPower,
            double currentHealth,
            double maximumHealth,
            double missingHealthFraction,
            double configuredConditionBonus,
            double effectiveChance,
            boolean guaranteed,
            CaptureAttemptOutcome outcome,
            String reason,
            long resolvedAtMs,
            long emittedAtMs
    ) {
        this(
                attemptId, operationId, actorUuid, targetNpcUuid,
                profileId, roleId, sourceItemId, spawnerConfigId,
                spawnerConfigRevision, capturePolicyConfigId,
                capturePolicyRevision, power, minimumPower,
                currentHealth, maximumHealth, missingHealthFraction,
                configuredConditionBonus, effectiveChance, guaranteed,
                outcome, reason, resolvedAtMs, emittedAtMs, null
        );
    }

    private static void validateFinite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite.");
        }
    }

    private static void validateUnit(String field, double value) {
        validateFinite(field, value);
        if (value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(field + " must be between zero and one.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
