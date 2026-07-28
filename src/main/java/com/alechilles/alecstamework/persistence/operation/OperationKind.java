package com.alechilles.alecstamework.persistence.operation;

import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Registered, stable kind of a persistence operation.
 *
 * @param value lowercase snake-case identifier
 */
public record OperationKind(@Nonnull String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public OperationKind {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Operation kind must be lowercase snake case");
        }
    }

    /** Returns the durable representation. */
    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
