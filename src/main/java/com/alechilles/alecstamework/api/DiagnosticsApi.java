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

    /** Read-only exact-scope gate query; it never reserves or mutates canonical state. */
    @Nonnull
    default PersistenceMutationAvailabilityView queryPersistenceAvailability(
            @Nonnull PersistenceMutationAvailabilityRequest request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        return PersistenceMutationAvailabilityView.unavailable();
    }

    /**
     * Returns a sanitized bounded incident view.
     *
     * <p>Implementations may perform a SQLite read, so callers must not invoke this from a world
     * tick callback.</p>
     */
    @Nonnull
    default Optional<PersistenceIncidentSummaryView> findPersistenceIncident(
            @Nonnull String incidentIdOrUniquePrefix
    ) {
        if (incidentIdOrUniquePrefix == null) {
            throw new NullPointerException("incidentIdOrUniquePrefix");
        }
        return Optional.empty();
    }
}
