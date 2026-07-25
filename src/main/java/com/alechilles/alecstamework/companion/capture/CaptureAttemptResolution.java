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
        @Nullable Long failureCooldownUntilMs
) {
    public CaptureAttemptResolution {
        targetRoleId = requireText(targetRoleId, "Capture target role");
        reason = requireText(reason, "Capture resolution reason");
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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    public enum Outcome {
        SUCCESS,
        FAILED_ROLL
    }
}
