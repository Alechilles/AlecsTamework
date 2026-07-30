package com.alechilles.alecstamework.npc.progression;

import java.math.BigDecimal;
import java.util.Locale;
import javax.annotation.Nullable;

/** Resolves and classifies the static entity effects managed for companion movement speed. */
public final class CompanionMovementSpeedEffectIdResolver {
    private static final String MANAGED_PREFIX = "Tw_MovementSpeed_";
    private static final String LEGACY_PREFIX = "Tw_Trait_MoveSpeed_";
    private static final int MIN_MANAGED_PERCENT = 50;
    private static final int MAX_MANAGED_PERCENT = 200;
    private static final int MIN_LEGACY_PERCENT = 80;
    private static final int MAX_LEGACY_PERCENT = 130;

    /**
     * Resolves an already quantized multiplier to its managed static effect ID.
     *
     * @return {@code null} only for exactly neutral 1.00
     * @throws IllegalArgumentException when the caller supplies a non-neutral value outside this resolver's
     *                                  quantized managed-effect range
     */
    @Nullable
    public String resolveManagedEffectId(double quantizedMultiplier) {
        if (Double.compare(quantizedMultiplier, 1.0) == 0) {
            return null;
        }
        int percent = exactPercent(quantizedMultiplier);
        if (!isSupportedPercent(percent, MIN_MANAGED_PERCENT, MAX_MANAGED_PERCENT)) {
            throw new IllegalArgumentException("Unsupported quantized movement multiplier: " + quantizedMultiplier);
        }
        return MANAGED_PREFIX + String.format(Locale.ROOT, "%03d", percent);
    }

    /** Returns whether an ID belongs to the managed movement-speed asset family. */
    public boolean isManagedEffectId(@Nullable String effectId) {
        return hasSupportedId(effectId, MANAGED_PREFIX, MIN_MANAGED_PERCENT, MAX_MANAGED_PERCENT);
    }

    /** Returns whether an ID belongs to the retained legacy trait speed-effect family. */
    public boolean isLegacyEffectId(@Nullable String effectId) {
        return hasSupportedId(effectId, LEGACY_PREFIX, MIN_LEGACY_PERCENT, MAX_LEGACY_PERCENT);
    }

    private static boolean hasSupportedId(@Nullable String effectId, String prefix, int min, int max) {
        if (effectId == null || !effectId.startsWith(prefix)) {
            return false;
        }
        String suffix = effectId.substring(prefix.length());
        if (suffix.length() != 3) {
            return false;
        }
        try {
            return isSupportedPercent(Integer.parseInt(suffix), min, max);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int exactPercent(double multiplier) {
        if (!Double.isFinite(multiplier)) {
            return -1;
        }
        try {
            return BigDecimal.valueOf(multiplier).movePointRight(2).intValueExact();
        } catch (ArithmeticException exception) {
            return -1;
        }
    }

    private static boolean isSupportedPercent(int percent, int min, int max) {
        return percent >= min && percent <= max && percent % 5 == 0 && percent != 100;
    }
}
