package com.alechilles.alecstamework.performance;

import javax.annotation.Nonnull;

/**
 * Current runtime pressure classification for a tracked Tamework subsystem.
 */
public enum RuntimePressureLevel {
    NORMAL(1.0),
    WARM(1.5),
    HOT(2.5),
    EMERGENCY(4.0);

    private final double multiplier;

    RuntimePressureLevel(double multiplier) {
        this.multiplier = multiplier;
    }

    double multiplier() {
        return multiplier;
    }

    @Nonnull
    RuntimePressureLevel decayOneStep() {
        int index = ordinal();
        if (index <= 0) {
            return NORMAL;
        }
        return values()[index - 1];
    }

    boolean isAtLeast(@Nonnull RuntimePressureLevel minimum) {
        return ordinal() >= minimum.ordinal();
    }
}
