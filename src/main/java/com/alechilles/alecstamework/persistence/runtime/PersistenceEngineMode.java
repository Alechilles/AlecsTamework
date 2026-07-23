package com.alechilles.alecstamework.persistence.runtime;

import java.util.Locale;
import javax.annotation.Nonnull;

/** Process-wide persistence implementation selected before any storage opens. */
public enum PersistenceEngineMode {
    NEXT("next"),
    LEGACY("legacy");

    private final String configurationValue;

    PersistenceEngineMode(String configurationValue) {
        this.configurationValue = configurationValue;
    }

    /** Returns the stable development configuration value. */
    @Nonnull
    public String configurationValue() {
        return configurationValue;
    }

    /** Parses one explicit engine value without guessing or falling back. */
    @Nonnull
    public static PersistenceEngineMode parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "persistence_engine_value_required"
            );
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PersistenceEngineMode mode : values()) {
            if (mode.configurationValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "unsupported_persistence_engine:" + normalized
        );
    }
}
