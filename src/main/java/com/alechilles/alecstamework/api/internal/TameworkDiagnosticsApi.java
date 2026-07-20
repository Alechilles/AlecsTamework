package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PersistenceIncidentSummaryView;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityView;
import com.alechilles.alecstamework.api.PersistenceResilienceView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Focused diagnostics and read-only persistence resilience API implementation. */
final class TameworkDiagnosticsApi implements DiagnosticsApi {
    private final TameworkPersistenceRuntime persistence;
    private final PopulationPolicyApiDelegate population;
    private final PersistenceResilienceApiDelegate resilience;

    TameworkDiagnosticsApi(@Nonnull TameworkPersistenceRuntime persistence,
                           @Nonnull PopulationPolicyApiDelegate population) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.population = Objects.requireNonNull(population, "population");
        this.resilience = new PersistenceResilienceApiDelegate(persistence);
    }

    @Override
    @Nonnull
    public PersistenceDiagnosticsView getPersistenceDiagnostics() {
        return ApiMapper.mapPersistenceDiagnostics(persistence.collectDiagnostics());
    }

    @Override
    @Nonnull
    public PopulationDiagnosticsView getPopulationDiagnostics() {
        return population.diagnostics();
    }

    @Override
    @Nonnull
    public PersistenceResilienceView getPersistenceResilience() {
        return resilience.snapshot();
    }

    @Override
    @Nonnull
    public PersistenceMutationAvailabilityView queryPersistenceAvailability(
            @Nonnull PersistenceMutationAvailabilityRequest request) {
        return resilience.query(request);
    }

    @Override
    @Nonnull
    public Optional<PersistenceIncidentSummaryView> findPersistenceIncident(
            @Nonnull String incidentIdOrUniquePrefix) {
        return resilience.findIncident(incidentIdOrUniquePrefix);
    }
}
