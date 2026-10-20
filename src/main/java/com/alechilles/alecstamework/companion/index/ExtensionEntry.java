package com.alechilles.alecstamework.companion.index;

import java.util.Objects;
import javax.annotation.Nonnull;

/** One namespaced extension value (for example HyDragon progression) and its revision. */
public record ExtensionEntry(long revision, @Nonnull String json) {
    public ExtensionEntry {
        Objects.requireNonNull(json, "json");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
