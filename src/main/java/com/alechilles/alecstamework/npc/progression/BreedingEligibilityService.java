package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Shared math helpers for interaction-driven breeding eligibility checks.
 */
public final class BreedingEligibilityService {
    private BreedingEligibilityService() {
    }

    public static double resolveThreshold(@Nullable Double interactionMinHappiness, double fallbackThreshold) {
        if (interactionMinHappiness != null && Double.isFinite(interactionMinHappiness)) {
            return interactionMinHappiness;
        }
        return Double.isFinite(fallbackThreshold) ? fallbackThreshold : 0.0;
    }

    public static double resolveEffectiveHappiness(double baseHappiness,
                                                   double fertilityMultiplier,
                                                   @Nullable Double fertilityBonus) {
        double safeBase = Double.isFinite(baseHappiness) ? baseHappiness : 0.0;
        double safeMultiplier = Double.isFinite(fertilityMultiplier) ? fertilityMultiplier : 1.0;
        if (safeMultiplier < 0.0) {
            safeMultiplier = 0.0;
        }
        double safeBonus = (fertilityBonus != null && Double.isFinite(fertilityBonus)) ? fertilityBonus : 0.0;
        return (safeBase * safeMultiplier) + safeBonus;
    }

    public static boolean isEligible(double effectiveHappiness, double threshold) {
        if (!Double.isFinite(effectiveHappiness)) {
            return false;
        }
        double safeThreshold = Double.isFinite(threshold) ? threshold : 0.0;
        return effectiveHappiness >= safeThreshold;
    }

    public static boolean isBlockedByState(@Nullable String stateName,
                                           boolean requireNotSleeping,
                                           boolean requireNotInCombat) {
        if (stateName == null || stateName.isBlank()) {
            return false;
        }
        String normalized = stateName.trim().toLowerCase(Locale.ROOT);
        if (requireNotSleeping && normalized.contains("sleep")) {
            return true;
        }
        return requireNotInCombat
                && (normalized.contains("defend") || normalized.contains("combat"));
    }
}
