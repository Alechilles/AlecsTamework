package com.alechilles.alecstamework.persistence.incidents;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One exact local denial key plus its separately derived remote-safe hash. */
public record PersistenceScope(@Nonnull PersistenceScopeType type,
                               @Nonnull String key,
                               @Nonnull String scopeHash,
                               @Nullable String authorityDimension) {
    public PersistenceScope {
        if (type == null) throw new IllegalArgumentException("type");
        key = requireText(key, "key");
        scopeHash = requireText(scopeHash, "scopeHash");
        authorityDimension = normalizeNullable(authorityDimension);
    }

    @Nonnull
    public ScopeKey lookupKey() {
        return new ScopeKey(type, key);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ScopeKey(@Nonnull PersistenceScopeType type, @Nonnull String key) {
    }
}
