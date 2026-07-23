package com.alechilles.alecstamework.persistence.control;

import javax.annotation.Nonnull;

/** Typed registry key for a loader, recovery handler, or shutdown participant. */
public record PersistenceFeatureHookId(@Nonnull String value) {
    public PersistenceFeatureHookId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Persistence feature hook ID is required");
        }
        value = value.trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
