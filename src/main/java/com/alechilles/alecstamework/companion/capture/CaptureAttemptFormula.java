package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable item, role-policy, and requirement inputs used for one capture roll. */
public record CaptureAttemptFormula(
        @Nonnull String itemConfigId,
        long itemConfigRevision,
        @Nonnull CaptureChanceMode chanceMode,
        int itemPower,
        double baseChance,
        double chancePerPower,
        double minimumChance,
        double maximumChance,
        @Nullable String policyConfigId,
        long policyConfigRevision,
        int minimumPower,
        double resistance,
        double chanceMultiplier,
        double missingHealthBonus,
        @Nullable Integer guaranteedAtPower,
        @Nonnull Sha256Hash requirementsHash,
        long requirementGeneration
) {
    public CaptureAttemptFormula {
        itemConfigId = requireText(itemConfigId, "Capture item config");
        policyConfigId = normalize(policyConfigId);
        if (chanceMode == null || requirementsHash == null
                || itemConfigRevision < 0 || policyConfigRevision < 0
                || itemPower < 0 || minimumPower < 0
                || requirementGeneration < 0
                || guaranteedAtPower != null && guaranteedAtPower < 0) {
            throw new IllegalArgumentException(
                    "Complete non-negative capture formula evidence is required"
            );
        }
        probability(baseChance, "Base chance");
        probability(minimumChance, "Minimum chance");
        probability(maximumChance, "Maximum chance");
        if (minimumChance > maximumChance
                || !finiteNonNegative(chancePerPower)
                || !finiteNonNegative(resistance)
                || !finiteNonNegative(chanceMultiplier)
                || !finiteNonNegative(missingHealthBonus)) {
            throw new IllegalArgumentException(
                    "Capture formula chance inputs are inconsistent"
            );
        }
        if (policyConfigId == null && policyConfigRevision != 0) {
            throw new IllegalArgumentException(
                    "Absent capture policy must use revision zero"
            );
        }
    }

    private static void probability(double value, String label) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(
                    label + " must be between zero and one"
            );
        }
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D;
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
}
