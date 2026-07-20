package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact local scope supplied to a read-only availability query; keys are never returned. */
public record PersistenceScopeReference(@Nonnull PersistenceScopeKind kind,
                                        @Nonnull String key,
                                        @Nullable String authorityDimension) {
    public PersistenceScopeReference {
        if (kind == null) throw new IllegalArgumentException("kind");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key");
        key = key.trim();
        authorityDimension = normalize(authorityDimension);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
