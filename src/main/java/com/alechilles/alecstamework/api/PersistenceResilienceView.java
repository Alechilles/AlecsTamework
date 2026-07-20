package com.alechilles.alecstamework.api;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local persistence health, containment, circuit, and evidence snapshot. */
public record PersistenceResilienceView(
        @Nonnull String storageState,
        @Nullable String storageReason,
        @Nullable String storageIncidentId,
        long storageChangedAtMs,
        int activeIncidentCount,
        int activeQuarantineCount,
        long oldestActiveQuarantineAtMs,
        @Nonnull List<CircuitView> circuits,
        @Nonnull List<CoverageView> coverage) {
    public PersistenceResilienceView {
        circuits = circuits == null ? List.of() : List.copyOf(circuits);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
    }

    @Nonnull
    public static PersistenceResilienceView unavailable() {
        return new PersistenceResilienceView(
                "READ_ONLY", "persistence_resilience_api_unavailable", null,
                0L, 0, 0, 0L, List.of(), List.of());
    }

    public record CircuitView(@Nonnull String domain,
                              boolean enabled,
                              @Nullable String reasonCode,
                              long updatedAtMs) {
    }

    public record CoverageView(@Nonnull String dimension,
                               boolean ready,
                               @Nullable String reasonCode,
                               long generation,
                               long updatedAtMs) {
    }
}
