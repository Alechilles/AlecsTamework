package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * One document of a {@link NetworkSettingsStore}.
 *
 * @param json     the document exactly as it was saved
 * @param revision greater than zero, and higher after every save of the same key
 */
public record NetworkSettingsDocument(@Nonnull String json, long revision) {
    public NetworkSettingsDocument {
        Objects.requireNonNull(json, "json");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be greater than zero");
        }
    }
}
