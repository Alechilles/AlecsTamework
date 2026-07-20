package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
            return new CoverageState(
                    ready ? PersistenceCoverageStatus.READY
                            : PersistenceCoverageStatus.UNAVAILABLE,
                    normalizeNullable(reason), generation, System.currentTimeMillis(),
                    Set.of(), ready, Set.of(), null);
        });
    }

    /** Publishes a complete named state including exact partial coverage and recovery metadata. */
    public void publish(@Nonnull String dimension,
                        @Nonnull PersistenceCoverageStatus status,
                        @Nullable String reason,
                        long generation,
                        @Nonnull Set<String> coveredScopeHashes,
                        boolean absenceAuthoritative,
                        @Nonnull Set<String> incidentIds,
                        @Nullable String nextSafeTrigger) {
        String key = normalize(dimension);
        states.compute(key, (ignored, current) -> {
            if (current != null && generation < current.generation()) return current;
            return new CoverageState(
                    status, normalizeNullable(reason), generation, System.currentTimeMillis(),
                    normalizeSet(coveredScopeHashes), absenceAuthoritative,
                    normalizeSet(incidentIds), normalizeNullable(nextSafeTrigger));
        });
    }

    @Override
    public boolean areReady(@Nonnull Set<String> requiredDimensions) {
        for (String required : requiredDimensions) {
            CoverageState state = states.get(normalize(required));
            if (state == null || !state.status().globallyReady()) return false;
        }
        return true;
    }

    @Override
    public boolean areReady(@Nonnull Set<String> requiredDimensions,
                            @Nonnull List<PersistenceScope> scopes) {
        for (String required : requiredDimensions) {
            CoverageState state = states.get(normalize(required));
            if (state == null) return false;
            if (state.status().globallyReady()) continue;
            if (state.status() != PersistenceCoverageStatus.PARTIAL
                    || scopes.isEmpty() || !coversAll(state, scopes)) return false;
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

    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }

    private static boolean coversAll(CoverageState state, List<PersistenceScope> scopes) {
        for (PersistenceScope scope : scopes) {
            if (!state.coveredScopeHashes().contains(scope.scopeHash())) return false;
        }
        return true;
    }

    public record CoverageState(@Nonnull PersistenceCoverageStatus status,
                                @Nullable String reason,
                                long generation,
                                long updatedAtMs,
                                @Nonnull Set<String> coveredScopeHashes,
                                boolean absenceAuthoritative,
                                @Nonnull Set<String> incidentIds,
                                @Nullable String nextSafeTrigger) {
        public CoverageState {
            if (status == null) throw new IllegalArgumentException("status");
            coveredScopeHashes = normalizeSet(coveredScopeHashes);
            incidentIds = normalizeSet(incidentIds);
            reason = normalizeNullable(reason);
            nextSafeTrigger = normalizeNullable(nextSafeTrigger);
        }

        public boolean ready() {
            return status.globallyReady();
        }

        private static CoverageState loading(String reason, long generation) {
            return new CoverageState(
                    PersistenceCoverageStatus.LOADING, reason, generation, 0L,
                    Set.of(), false, Set.of(), "evidence_ready");
        }
    }
}
