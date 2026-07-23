package com.alechilles.alecstamework.persistence.control;

import javax.annotation.Nonnull;

/** Stable compile-time identifier for one persistence-affecting feature. */
public record PersistenceFeatureId(@Nonnull String value)
        implements Comparable<PersistenceFeatureId> {
    public PersistenceFeatureId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Persistence feature ID is required");
        }
        value = value.trim();
    }

    @Override
    public int compareTo(PersistenceFeatureId other) {
        if (other == null) {
            throw new IllegalArgumentException("Compared feature ID is required");
        }
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
