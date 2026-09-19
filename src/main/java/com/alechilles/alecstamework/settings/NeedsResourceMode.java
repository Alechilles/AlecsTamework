package com.alechilles.alecstamework.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Controls how hunger/thirst resource target selection resolves candidate resources.
 */
public enum NeedsResourceMode {
    ACCURATE("Accurate"),
    AUTO_FAST("AutoFast"),
    ALWAYS_FAST("AlwaysFast");

    private final String configValue;

    NeedsResourceMode(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public static NeedsResourceMode fromConfigValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return AUTO_FAST;
        }
        String normalized = value.trim();
        for (NeedsResourceMode mode : values()) {
            if (mode.configValue.equalsIgnoreCase(normalized)
                    || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return AUTO_FAST;
    }

    @Nonnull
    public String toConfigValue() {
        return configValue;
    }
}
