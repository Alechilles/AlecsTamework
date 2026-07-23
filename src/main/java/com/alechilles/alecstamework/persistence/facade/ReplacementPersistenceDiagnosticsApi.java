package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PersistenceIncidentSummaryView;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityView;
import com.alechilles.alecstamework.api.PersistenceMutationDomain;
import com.alechilles.alecstamework.api.PersistenceResilienceView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistencePerformanceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Maps replacement operational evidence into the stable public diagnostics API.
 *
 * <p>The adapter intentionally returns only information carried by the
 * replacement control plane. It does not recreate the retired feature catalog,
 * health state machine, or incident query repository.</p>
 */
public final class ReplacementPersistenceDiagnosticsApi
        implements DiagnosticsApi {
    private final PersistenceBootstrap persistence;
    private final Supplier<PopulationDiagnosticsView> population;
    private final long readTimeoutMs;

    public ReplacementPersistenceDiagnosticsApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Supplier<PopulationDiagnosticsView> population,
            @Nonnull Duration readTimeout
    ) {
        if (persistence == null || population == null
                || readTimeout == null || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "Complete replacement diagnostics dependencies are required"
            );
        }
        this.persistence = persistence;
        this.population = population;
        readTimeoutMs = readTimeout.toMillis();
    }

    @Override
    @Nonnull
    public PersistenceDiagnosticsView getPersistenceDiagnostics() {
        PublicPersistenceOperationalStatus status =
                persistence.operationalStatus();
        PublicPersistencePerformanceSnapshot performance =
                persistence.performance();
        Path database = status.databasePath().orElse(
                status.dataDirectory()
        );
        long sqliteBytes = size(database);
        long walBytes = status.databasePath().isPresent()
                ? size(Path.of(database + "-wal"))
                : 0L;
        long shmBytes = status.databasePath().isPresent()
                ? size(Path.of(database + "-shm"))
                : 0L;
        var writer = performance.writer();
        long operations = writer.execution().count();
        long retries = persistence.metrics().features().values().stream()
                .mapToLong(value -> value.busyRetries())
                .sum();
        long failures = persistence.metrics().features().values().stream()
                .mapToLong(value -> value.unitsFailed())
                .sum();
        String failure = persistence.metrics().lastGlobalFailureCode();
        return new PersistenceDiagnosticsView(
                database.toString(),
                sqliteBytes,
                walBytes,
                shmBytes,
                sqliteBytes + walBytes + shmBytes,
                new PersistenceDiagnosticsView.QueueMetricsView(
                        0,
                        operations == 0 ? 0 : 1,
                        1,
                        operations,
                        operations,
                        retries,
                        failures,
                        operations == 0 ? 0.0D : 1.0D,
                        nanosToMillis(writer.execution().p50Nanos()),
                        nanosToMillis(writer.execution().maxNanos()),
                        failure,
                        0L
                ),
                new PersistenceDiagnosticsView.HealthView(
                        health(status.storageMode()),
                        status.startup().detail(),
                        0L
                )
        );
    }

    @Override
    @Nonnull
    public PopulationDiagnosticsView getPopulationDiagnostics() {
        PopulationDiagnosticsView result = population.get();
        return result != null
                ? result
                : PopulationDiagnosticsView.unavailable();
    }

    @Override
    @Nonnull
    public PersistenceResilienceView getPersistenceResilience() {
        PublicPersistenceOperationalStatus operational =
                persistence.operationalStatus();
        PublicPersistenceDiagnosticsSnapshot durable = durable();
        List<PersistenceResilienceView.CircuitView> circuits =
                durable == null ? List.of() : circuits(durable);
        long incidents = durable == null ? 0L
                : durable.openIncidentsByCode().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        long quarantines = durable == null ? 0L
                : durable.activeQuarantinesByScope().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        return new PersistenceResilienceView(
                health(operational.storageMode()),
                operational.startup().detail(),
                null,
                0L,
                Math.toIntExact(Math.min(Integer.MAX_VALUE, incidents)),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, quarantines)),
                0L,
                circuits,
                coverage(operational)
        );
    }

    @Override
    @Nonnull
    public PersistenceMutationAvailabilityView
    queryPersistenceAvailability(
            @Nonnull PersistenceMutationAvailabilityRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Persistence availability request is required"
            );
        }
        PublicPersistenceOperationalStatus operational =
                persistence.operationalStatus();
        if (operational.storageMode()
                != PublicPersistenceOperationalStatus.StorageMode.READ_WRITE) {
            return new PersistenceMutationAvailabilityView(
                    "GLOBAL_READ_ONLY",
                    "replacement_persistence_"
                            + operational.storageMode().name().toLowerCase(),
                    null
            );
        }
        PublicPersistenceDiagnosticsSnapshot durable = durable();
        if (durable != null && blocks(request.domain(), durable)) {
            return new PersistenceMutationAvailabilityView(
                    "QUARANTINED",
                    "replacement_feature_circuit_open",
                    null
            );
        }
        return new PersistenceMutationAvailabilityView(
                "ALLOW", "replacement_persistence_ready", null
        );
    }

    @Override
    @Nonnull
    public Optional<PersistenceIncidentSummaryView> findPersistenceIncident(
            @Nonnull String incidentIdOrUniquePrefix
    ) {
        return Optional.empty();
    }

    private PublicPersistenceDiagnosticsSnapshot durable() {
        try {
            PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot> read =
                    persistence.diagnostics().toCompletableFuture().get(
                            readTimeoutMs,
                            TimeUnit.MILLISECONDS
                    );
            if (read instanceof PersistenceReadResult.Found<
                    PublicPersistenceDiagnosticsSnapshot> found) {
                return found.value();
            }
        } catch (Exception unavailable) {
            return null;
        }
        return null;
    }

    private List<PersistenceResilienceView.CircuitView> circuits(
            PublicPersistenceDiagnosticsSnapshot snapshot
    ) {
        ArrayList<PersistenceResilienceView.CircuitView> result =
                new ArrayList<>();
        snapshot.features().values().forEach(feature ->
                result.add(new PersistenceResilienceView.CircuitView(
                        feature.featureId().toString(),
                        !feature.circuit().blocksMutation(),
                        feature.circuit().reasonCode(),
                        feature.circuit().updatedAtMs()
                )));
        result.sort(Comparator.comparing(
                PersistenceResilienceView.CircuitView::domain
        ));
        return List.copyOf(result);
    }

    private List<PersistenceResilienceView.CoverageView> coverage(
            PublicPersistenceOperationalStatus operational
    ) {
        ArrayList<PersistenceResilienceView.CoverageView> result =
                new ArrayList<>();
        operational.startupNodes().forEach((node, state) ->
                result.add(new PersistenceResilienceView.CoverageView(
                        "startup:" + node.name().toLowerCase(),
                        state.name(),
                        state == PublicPersistenceOperationalStatus.NodeState
                                .COMPLETED,
                        node == operational.startup().failedNode()
                                ? operational.startup().detail()
                                : null,
                        0L,
                        0L,
                        state == PublicPersistenceOperationalStatus.NodeState
                                .COMPLETED ? 1 : 0,
                        false,
                        state == PublicPersistenceOperationalStatus.NodeState
                                .DEFERRED ? "world_evidence" : null
                )));
        result.sort(Comparator.comparing(
                PersistenceResilienceView.CoverageView::dimension
        ));
        return List.copyOf(result);
    }

    private boolean blocks(
            PersistenceMutationDomain domain,
            PublicPersistenceDiagnosticsSnapshot snapshot
    ) {
        Set<String> candidates = featureCandidates(domain);
        return snapshot.features().values().stream()
                .filter(feature -> candidates.isEmpty()
                        || candidates.contains(
                        feature.featureId().toString()
                ))
                .anyMatch(feature -> feature.circuit().blocksMutation());
    }

    private Set<String> featureCandidates(PersistenceMutationDomain domain) {
        return switch (domain) {
            case CAPTURE_INTAKE, CAPTURE_RELEASE -> Set.of("capture");
            case MANAGED_COOP_INTAKE, MANAGED_COOP_RELEASE,
                    MANAGED_COOP_AUTOMATION -> Set.of("coop");
            case DEATH_LOST_RECOVERY, AUTOMATIC_SCOPED_RECOVERY ->
                    Set.of("death_and_lost");
            case OWNER_MUTATION, TAMING_OWNERSHIP, ADMIN_TAMED_SPAWN,
                    TAMED_SPAWN, RECONCILIATION ->
                    Set.of("core_identity", "core_lifecycle",
                            "owner_population");
            case RECALL_RELOCATION -> Set.of(
                    "core_identity", "core_lifecycle", "command_roster"
            );
            case BREEDING_PAIRING, BREEDING_BIRTH, BREEDING ->
                    Set.of("core_identity", "core_lifecycle",
                            "owner_population");
            case ALL_PERSISTENCE -> Set.of();
        };
    }

    private String health(
            PublicPersistenceOperationalStatus.StorageMode mode
    ) {
        return switch (mode) {
            case READ_WRITE -> "HEALTHY";
            case READ_ONLY -> "READ_ONLY";
            case STARTING -> "STARTING";
            case DRAINING -> "DRAINING";
            case CLOSED -> "CLOSED";
        };
    }

    private long size(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (Exception unavailable) {
            return 0L;
        }
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }
}
