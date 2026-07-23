package com.alechilles.alecstamework.persistence.projection;

import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Stable durable projection consumer identity.
 *
 * @param value lowercase snake-case identifier
 */
public record ProjectionConsumerId(@Nonnull String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public ProjectionConsumerId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Projection consumer ID must be lowercase snake case");
        }
    }

    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
