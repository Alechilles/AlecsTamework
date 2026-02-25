package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Resolves trait move-speed multipliers to concrete EntityEffect ids.
 *
 * <p>Speed effects are quantized to fixed 5% steps so runtime can reuse static
 * effect assets while still representing a range of rolled trait values.
 */
public final class TraitMoveSpeedEffectResolver {
    private static final String MOVE_SPEED_EFFECT_ID_PREFIX = "Tw_Trait_MoveSpeed_";
    private static final double MIN_MULTIPLIER = 0.80;
    private static final double MAX_MULTIPLIER = 1.30;
    private static final double STEP = 0.05;
    private static final double EPSILON = 0.000001;

    private TraitMoveSpeedEffectResolver() {
    }

    /**
     * Converts a raw trait multiplier into a supported move-speed effect id.
     *
     * @return effect id to apply, or {@code null} when no effect is needed.
     */
    @Nullable
    public static String resolveMoveSpeedEffectId(double rawMultiplier) {
        if (!Double.isFinite(rawMultiplier) || rawMultiplier <= 0.0) {
            return null;
        }
        double clamped = clamp(rawMultiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        double snapped = snapToStep(clamped);
        if (Math.abs(snapped - 1.0) <= EPSILON) {
            return null;
        }
        int percent = (int) Math.round(snapped * 100.0);
        return MOVE_SPEED_EFFECT_ID_PREFIX + String.format(Locale.ROOT, "%03d", percent);
    }

    public static boolean isTraitMoveSpeedEffectId(@Nullable String effectId) {
        return effectId != null && effectId.startsWith(MOVE_SPEED_EFFECT_ID_PREFIX);
    }

    private static double snapToStep(double value) {
        int steps = (int) Math.round((value - MIN_MULTIPLIER) / STEP);
        return MIN_MULTIPLIER + (steps * STEP);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
