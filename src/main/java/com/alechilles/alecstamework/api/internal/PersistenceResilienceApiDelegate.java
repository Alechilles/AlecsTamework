package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PersistenceIncidentSummaryView;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityView;
import com.alechilles.alecstamework.api.PersistenceResilienceView;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/** Maps the internal resilience authority into additive, sanitized, read-only API views. */
final class PersistenceResilienceApiDelegate {
    private final TameworkPersistenceRuntime persistence;

    PersistenceResilienceApiDelegate(@Nonnull TameworkPersistenceRuntime persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    @Nonnull
    PersistenceResilienceView snapshot() {
        var storage = persistence.getStorageHealthService().getState();
        List<PersistenceQuarantineRecord> quarantines =
                persistence.getQuarantineRegistry().snapshot();
        return new PersistenceResilienceView(
                storage.status().name(), storage.reason(), storage.incidentId(),
                storage.changedAtMs(), activeIncidentCount(quarantines, storage.incidentId()),
                quarantines.size(), oldestQuarantine(quarantines),
                circuitViews(), coverageViews());
    }

    @Nonnull
    PersistenceMutationAvailabilityView query(
            @Nonnull PersistenceMutationAvailabilityRequest request) {
        Objects.requireNonNull(request, "request");
        var decision = persistence.getMutationAvailabilityService().decide(
                new PersistenceMutationContext(
                        PersistenceDomain.valueOf(request.domain().name()),
                        request.operationKind(), scopes(request),
                        request.requiredEvidenceDimensions(),
                        PersistenceMutationDelta.valueOf(request.direction().name()),
                        request.traceId(), request.operationId(),
                        request.sourceMayExist(), request.liveProjectionMayExist()));
        return new PersistenceMutationAvailabilityView(
                decision.status().name(), decision.reasonCode(), decision.incidentId());
    }

    @Nonnull
    Optional<PersistenceIncidentSummaryView> findIncident(@Nonnull String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) return Optional.empty();
        try {
            Optional<PersistenceIncident> found = persistence.getIncidentRepository()
                    .findByIdOrUniquePrefix(idOrPrefix);
            if (found.isEmpty()) return Optional.empty();
            PersistenceIncident incident = found.orElseThrow();
            return Optional.of(mapIncident(incident, persistence.getIncidentRepository()
                    .listScopes(incident.incidentId())));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    private List<PersistenceScope> scopes(PersistenceMutationAvailabilityRequest request) {
        ArrayList<PersistenceScope> mapped = new ArrayList<>(request.scopes().size());
        for (var scope : request.scopes()) {
            mapped.add(persistence.getPersistenceScopeFactory().scope(
                    PersistenceScopeType.valueOf(scope.kind().name()),
                    scope.key(), scope.authorityDimension()));
        }
        return List.copyOf(mapped);
    }

    private List<PersistenceResilienceView.CircuitView> circuitViews() {
        ArrayList<PersistenceResilienceView.CircuitView> views = new ArrayList<>();
        persistence.getFeatureCircuitRegistry().snapshot().forEach((domain, state) ->
                views.add(new PersistenceResilienceView.CircuitView(
                        domain.name(), state.enabled(), state.reasonCode(), state.updatedAtMs())));
        views.sort(Comparator.comparing(PersistenceResilienceView.CircuitView::domain));
        return List.copyOf(views);
    }

    private List<PersistenceResilienceView.CoverageView> coverageViews() {
        ArrayList<PersistenceResilienceView.CoverageView> views = new ArrayList<>();
        persistence.getPersistenceCoverageRegistry().snapshot().forEach((dimension, state) ->
                views.add(new PersistenceResilienceView.CoverageView(
                        dimension, state.status().name(), state.ready(), state.reason(),
                        state.generation(), state.updatedAtMs(),
                        state.coveredScopeHashes().size(), state.absenceAuthoritative(),
                        state.nextSafeTrigger())));
        views.sort(Comparator.comparing(PersistenceResilienceView.CoverageView::dimension));
        return List.copyOf(views);
    }

    private PersistenceIncidentSummaryView mapIncident(
            PersistenceIncident incident, List<PersistenceScope> scopes) {
        ArrayList<PersistenceIncidentSummaryView.ScopeView> scopeViews = new ArrayList<>();
        for (PersistenceScope scope : scopes) {
            scopeViews.add(new PersistenceIncidentSummaryView.ScopeView(
                    scope.type().name(), scope.scopeHash(), scope.authorityDimension()));
        }
        return new PersistenceIncidentSummaryView(
                incident.incidentId(), incident.status().name(), incident.domain().name(),
                incident.phase().name(), incident.reasonCode(), incident.failureClass().name(),
                incident.disposition().name(), incident.openedAtMs(), incident.lastSeenAtMs(),
                incident.occurrenceCount(), incident.recoveryAttempts(), incident.resolutionCode(),
                incident.telemetryCorrelationId(), scopeViews);
    }

    private int activeIncidentCount(List<PersistenceQuarantineRecord> quarantines,
                                    String storageIncidentId) {
        Set<String> incidentIds = new HashSet<>();
        for (PersistenceQuarantineRecord quarantine : quarantines) {
            incidentIds.add(quarantine.incidentId());
        }
        if (storageIncidentId != null && !storageIncidentId.isBlank()) {
            incidentIds.add(storageIncidentId);
        }
        return incidentIds.size();
    }

    private long oldestQuarantine(List<PersistenceQuarantineRecord> quarantines) {
        long oldest = 0L;
        for (PersistenceQuarantineRecord quarantine : quarantines) {
            if (oldest == 0L || quarantine.createdAtMs() < oldest) {
                oldest = quarantine.createdAtMs();
            }
        }
        return oldest;
    }
}
