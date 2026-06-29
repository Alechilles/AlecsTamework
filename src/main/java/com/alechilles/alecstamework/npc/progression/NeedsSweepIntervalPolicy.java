package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Chooses passive needs update intervals from current hunger/thirst urgency.
 */
final class NeedsSweepIntervalPolicy {
    private static final long MAX_INTERVAL_MS = 30_000L;

    private NeedsSweepIntervalPolicy() {
    }

    static long intervalMs(@Nullable TameworkNeedsComponent component,
                           @Nonnull TwNeedsConfig config,
                           long baseIntervalMs) {
        if (component == null || baseIntervalMs <= 0L) {
            return 0L;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hungerRatio = ratio(component.getHunger(), values.getHungerMin(), values.getHungerMax());
        double thirstRatio = ratio(component.getThirst(), values.getThirstMin(), values.getThirstMax());
        return intervalMsForRatios(hungerRatio, thirstRatio, baseIntervalMs);
    }

    static long intervalMsForRatios(double hungerRatio, double thirstRatio, long baseIntervalMs) {
        if (baseIntervalMs <= 0L) {
            return 0L;
        }
        double urgency = Math.min(sanitizeRatio(hungerRatio), sanitizeRatio(thirstRatio));
        long multiplier;
        if (urgency <= 0.15) {
            multiplier = 1L;
        } else if (urgency <= 0.35) {
            multiplier = 2L;
        } else if (urgency <= 0.70) {
            multiplier = 4L;
        } else {
            multiplier = 8L;
        }
        return Math.min(MAX_INTERVAL_MS, baseIntervalMs * multiplier);
    }

    private static double ratio(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return 0.0;
        }
        return sanitizeRatio((value - min) / (max - min));
    }

    private static double sanitizeRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }
}
