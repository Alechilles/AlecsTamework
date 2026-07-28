package com.alechilles.alecstamework.persistence.projection;

import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Stable registered projection event type.
 *
 * @param value lowercase snake-case identifier
 */
public record ProjectionEventType(@Nonnull String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public ProjectionEventType {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Projection event type must be lowercase snake case");
        }
    }

    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
