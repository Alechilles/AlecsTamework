package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen capture-roll fields required by bonded completion and recovery. */
public record BondedCompanionCaptureAttemptEvidence(
        @Nonnull UUID attemptId,
        @Nonnull String sourceItemId,
        @Nonnull String spawnerConfigId,
        long spawnerConfigRevision,
        @Nullable String capturePolicyConfigId,
        long capturePolicyConfigRevision,
        @Nonnull CaptureSourceConsumption sourceConsumption,
        @Nonnull CaptureSuccessDisposition successDisposition,
        @Nonnull CaptureAttemptOutcome outcome,
        @Nonnull String reason
) {
    public BondedCompanionCaptureAttemptEvidence {
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        sourceItemId = text(sourceItemId, "sourceItemId");
        spawnerConfigId = text(spawnerConfigId, "spawnerConfigId");
        capturePolicyConfigId = optional(capturePolicyConfigId);
        sourceConsumption = Objects.requireNonNull(
                sourceConsumption, "sourceConsumption");
        successDisposition = Objects.requireNonNull(
                successDisposition, "successDisposition");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reason = text(reason, "reason");
        if (spawnerConfigRevision < 0L
                || capturePolicyConfigRevision < -1L
                || (capturePolicyConfigId == null)
                != (capturePolicyConfigRevision == -1L)) {
            throw new IllegalArgumentException(
                    "Capture config evidence is inconsistent");
        }
        if (successDisposition
                != CaptureSuccessDisposition.STORE_BONDED_COMPANION) {
            throw new IllegalArgumentException(
                    "Bonded evidence requires bonded capture disposition");
        }
    }

    /** Copies exact immutable evidence from the evaluated capture roll. */
    @Nonnull
    public static BondedCompanionCaptureAttemptEvidence from(
            @Nonnull String sourceItemId,
            @Nonnull CaptureAttemptResolution resolution
    ) {
        Objects.requireNonNull(resolution, "resolution");
        CaptureAttemptFormula formula = resolution.formula();
        return new BondedCompanionCaptureAttemptEvidence(
                resolution.attemptId(), sourceItemId,
                formula.itemConfigId(), formula.itemConfigRevision(),
                formula.policyConfigId(), formula.policyConfigId() == null
                        ? -1L : formula.policyConfigRevision(),
                resolution.sourceConsumption(),
                resolution.successDisposition(),
                resolution.successful()
                        ? CaptureAttemptOutcome.CAPTURED
                        : CaptureAttemptOutcome.FAILED_ROLL,
                resolution.reason()
        );
    }

    private static String text(String value, String field) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
