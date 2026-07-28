package com.alechilles.alecstamework.persistence.kernel;

import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Stable identifier for one replacement persistence read.
 *
 * @param value lowercase snake-case identifier
 */
public record PersistenceReadKind(@Nonnull String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public PersistenceReadKind {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Persistence read kind must be lowercase snake case");
        }
    }

    /** Returns the metrics and failure-report representation. */
    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
