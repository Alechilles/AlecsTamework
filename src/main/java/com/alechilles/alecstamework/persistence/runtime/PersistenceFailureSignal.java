package com.alechilles.alecstamework.persistence.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Terminal persistence failure evidence sent outside the storage path.
 */
public record PersistenceFailureSignal(@Nonnull String eventName,
                                       @Nonnull String incidentKey,
                                       @Nonnull String operation,
                                       @Nonnull String phase,
                                       @Nonnull String reason,
                                       @Nullable Throwable cause) {

    public PersistenceFailureSignal {
        eventName = normalize(eventName, "persistence_failure");
        incidentKey = normalize(incidentKey, eventName + ":unknown");
        operation = normalize(operation, "unknown");
        phase = normalize(phase, "unknown");
        reason = normalize(reason, "unknown_failure");
    }

    @Nonnull
    private static String normalize(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
