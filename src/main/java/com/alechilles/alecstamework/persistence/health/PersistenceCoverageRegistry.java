package com.alechilles.alecstamework.persistence.health;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local projection of named, generation-aware evidence coverage readiness. */
public final class PersistenceCoverageRegistry implements PersistenceCoverageReadiness {
    private final ConcurrentHashMap<String, CoverageState> states = new ConcurrentHashMap<>();

    public PersistenceCoverageRegistry() {
        for (PersistenceEvidenceDimension dimension : PersistenceEvidenceDimension.values()) {
            states.put(dimension.key(), CoverageState.loading("startup", 0L));
        }
    }

    public void publish(@Nonnull PersistenceEvidenceDimension dimension,
                        boolean ready,
                        @Nullable String reason,
                        long generation) {
        publish(dimension.key(), ready, reason, generation);
    }

    public void publish(@Nonnull String dimension,
                        boolean ready,
                        @Nullable String reason,
                        long generation) {
        String key = normalize(dimension);
        states.compute(key, (ignored, current) -> {
            if (current != null && generation < current.generation()) return current;
            return new CoverageState(ready, normalizeNullable(reason), generation,
                    System.currentTimeMillis());
        });
    }

    @Override
    public boolean areReady(@Nonnull Set<String> requiredDimensions) {
        for (String required : requiredDimensions) {
            CoverageState state = states.get(normalize(required));
            if (state == null || !state.ready()) return false;
        }
        return true;
    }

    @Nonnull
    public Map<String, CoverageState> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(states));
    }

    @Nonnull
    private static String normalize(@Nonnull String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("dimension");
        return normalized;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CoverageState(boolean ready,
                                @Nullable String reason,
                                long generation,
                                long updatedAtMs) {
        private static CoverageState loading(String reason, long generation) {
            return new CoverageState(false, reason, generation, 0L);
        }
    }
}
