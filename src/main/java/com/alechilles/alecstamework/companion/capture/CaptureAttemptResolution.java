package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen terminal roll evidence carried by the canonical capture operation. */
public record CaptureAttemptResolution(
        @Nonnull UUID attemptId,
        @Nonnull String targetRoleId,
        @Nonnull CaptureAttemptFormula formula,
        @Nonnull CaptureSourceConsumption sourceConsumption,
        @Nonnull CaptureSuccessDisposition successDisposition,
        @Nonnull Outcome outcome,
        @Nonnull String reason,
        double effectiveChance,
        boolean guaranteed,
        double missingHealthFraction,
        @Nullable Double entropy,
        @Nullable Long failureCooldownUntilMs,
        @Nullable String callerNamespace,
        @Nullable String callerIdempotencyKey,
        @Nullable String targetDisplayName,
        @Nullable Double currentHealth,
        @Nullable Double maximumHealth
) {
    public CaptureAttemptResolution {
        targetRoleId = requireText(targetRoleId, "Capture target role");
        reason = requireText(reason, "Capture resolution reason");
        callerNamespace = normalize(callerNamespace);
        callerIdempotencyKey = normalize(callerIdempotencyKey);
        targetDisplayName = normalize(targetDisplayName);
        if (attemptId == null || formula == null || sourceConsumption == null
                || successDisposition == null || outcome == null) {
            throw new IllegalArgumentException(
                    "Complete capture resolution is required"
            );
        }
        probability(effectiveChance, "Effective chance");
        probability(missingHealthFraction, "Missing health fraction");
        if (entropy != null && (!Double.isFinite(entropy)
                || entropy < 0.0D || entropy >= 1.0D)) {
            throw new IllegalArgumentException(
                    "Capture entropy must be in [0,1)"
            );
        }
        if (outcome == Outcome.FAILED_ROLL
                && sourceConsumption
                != CaptureSourceConsumption.RESOLVED_ATTEMPT) {
            throw new IllegalArgumentException(
                    "Durable failed rolls require resolved-attempt consumption"
            );
        }
        if (outcome == Outcome.SUCCESS
                && failureCooldownUntilMs != null) {
            throw new IllegalArgumentException(
                    "Successful capture cannot carry failure cooldown"
            );
        }
        if ((callerNamespace == null) != (callerIdempotencyKey == null)) {
            throw new IllegalArgumentException(
                    "Capture caller namespace and idempotency key must appear together"
            );
        }
        if ((currentHealth == null) != (maximumHealth == null)) {
            throw new IllegalArgumentException(
                    "Capture absolute health values must appear together"
            );
        }
        if (currentHealth != null
                && (!Double.isFinite(currentHealth)
                || !Double.isFinite(maximumHealth)
                || maximumHealth <= 0.0D
                || currentHealth < 0.0D
                || currentHealth > maximumHealth)) {
            throw new IllegalArgumentException(
                    "Capture absolute health evidence is invalid"
            );
        }
    }

    /** Source-compatible constructor for evidence authored before replay facts were additive. */
    public CaptureAttemptResolution(
            UUID attemptId,
            String targetRoleId,
            CaptureAttemptFormula formula,
            CaptureSourceConsumption sourceConsumption,
            CaptureSuccessDisposition successDisposition,
            Outcome outcome,
            String reason,
            double effectiveChance,
            boolean guaranteed,
            double missingHealthFraction,
            Double entropy,
            Long failureCooldownUntilMs
    ) {
        this(
                attemptId, targetRoleId, formula, sourceConsumption,
                successDisposition, outcome, reason, effectiveChance,
                guaranteed, missingHealthFraction, entropy,
                failureCooldownUntilMs, null, null, null, null, null
        );
    }

    /** Adds live display-name evidence after the world-thread snapshot is frozen. */
    public CaptureAttemptResolution withTargetDisplayName(
            @Nullable String displayName
    ) {
        String normalized = normalize(displayName);
        return java.util.Objects.equals(normalized, targetDisplayName)
                ? this
                : new CaptureAttemptResolution(
                        attemptId, targetRoleId, formula, sourceConsumption,
                        successDisposition, outcome, reason, effectiveChance,
                        guaranteed, missingHealthFraction, entropy,
                        failureCooldownUntilMs, callerNamespace,
                        callerIdempotencyKey, normalized, currentHealth,
                        maximumHealth
                );
    }

    public boolean successful() {
        return outcome == Outcome.SUCCESS;
    }

    private static void probability(double value, String label) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(
                    label + " must be between zero and one"
            );
        }
    }

    private static String requireText(String value, String label) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum Outcome {
        SUCCESS,
        FAILED_ROLL
    }
}
