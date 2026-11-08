package com.alechilles.alecstamework.companioncard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** What F on an owned companion does when its interaction config selects the {@code ModeCycle} entry. */
public enum CompanionCardFBehaviour {
    /** Opens the companion card. */
    CARD("CARD"),
    /** Cycles to the next mode, the behaviour before the companion card existed. */
    CYCLE("CYCLE");

    private final String configValue;

    CompanionCardFBehaviour(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public String toConfigValue() {
        return configValue;
    }

    /** Parses a saved value; returns {@code null} for a missing or unknown value so callers can fall back. */
    @Nullable
    public static CompanionCardFBehaviour fromConfigValueOrNull(@Nullable String value) {
        if (value != null) {
            for (CompanionCardFBehaviour behaviour : values()) {
                if (behaviour.configValue.equalsIgnoreCase(value.trim())) {
                    return behaviour;
                }
            }
        }
        return null;
    }
}
