package com.alechilles.alecstamework.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Server-wide lifecycle behavior after an animal reaches adulthood.
 */
public enum AnimalAgingMode {
    OFF("OFF"),
    FREEZE_AT_PRIME("FREEZE_AT_PRIME"),
    FULL("FULL");

    private final String configValue;

    AnimalAgingMode(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public String toConfigValue() {
        return configValue;
    }

    @Nonnull
    public static AnimalAgingMode fromConfigValue(@Nullable String value) {
        if (value != null) {
            for (AnimalAgingMode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return FREEZE_AT_PRIME;
    }
}
