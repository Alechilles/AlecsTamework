package com.alechilles.alecstamework.persistence.incidents;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Stable identity of one replacement persistence incident.
 *
 * @param value incident UUID
 */
public record IncidentId(@Nonnull UUID value) {
    public IncidentId {
        if (value == null) {
            throw new IllegalArgumentException("Incident ID is required");
        }
    }

    /** Creates a new incident identity. */
    @Nonnull
    public static IncidentId create() {
        return new IncidentId(UUID.randomUUID());
    }

    /** Parses the canonical durable representation. */
    @Nonnull
    public static IncidentId parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Incident ID is required");
        }
        return new IncidentId(UUID.fromString(value.trim()));
    }

    @Override
    @Nonnull
    public String toString() {
        return value.toString();
    }
}
