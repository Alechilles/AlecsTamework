package com.alechilles.alecstamework.api;

import java.util.Optional;
import javax.annotation.Nonnull;

public interface DiagnosticsApi {
    @Nonnull
    PersistenceDiagnosticsView getPersistenceDiagnostics();

    /** Additive population diagnostics; older implementations report an unavailable snapshot. */
    @Nonnull
    default PopulationDiagnosticsView getPopulationDiagnostics() {
        return PopulationDiagnosticsView.unavailable();
    }

    /** Additive process-local resilience snapshot; older implementations fail closed. */
    @Nonnull
    default PersistenceResilienceView getPersistenceResilience() {
        return PersistenceResilienceView.unavailable();
    }

    /** Read-only exact-scope gate query; it never reserves capacity or mutates canonical state. */
    @Nonnull
    default PersistenceMutationAvailabilityView queryPersistenceAvailability(
            @Nonnull PersistenceMutationAvailabilityRequest request) {
        return PersistenceMutationAvailabilityView.unavailable();
    }

    /**
     * Returns a sanitized bounded incident view. Implementations may perform a SQLite read, so
     * callers must not invoke this method from a world tick callback.
     */
    @Nonnull
    default Optional<PersistenceIncidentSummaryView> findPersistenceIncident(
            @Nonnull String incidentIdOrUniquePrefix) {
        return Optional.empty();
    }
}
