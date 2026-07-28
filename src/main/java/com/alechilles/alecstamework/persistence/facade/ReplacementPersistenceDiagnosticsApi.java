package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.PersistenceIncidentSummaryView;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityView;
import com.alechilles.alecstamework.api.PersistenceResilienceView;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuitState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistencePerformanceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 * Maps replacement operational evidence into the stable public diagnostics API.
 *
 * <p>The adapter intentionally preserves only the released diagnostics
 * snapshot instead of exposing a second persistence decision authority.</p>
 */
public final class ReplacementPersistenceDiagnosticsApi
        implements DiagnosticsApi {
    private final PersistenceBootstrap persistence;
    private final long readTimeoutMs;
    private final AvailabilityProbe availability;
    private final IncidentLookup incidents;

    public ReplacementPersistenceDiagnosticsApi(
            @Nonnull PersistenceBootstrap persistence
    ) {
        this(
                persistence,
                Duration.ofSeconds(5),
                request -> PersistenceMutationAvailabilityView.unavailable(),
                ignored -> Optional.empty()
        );
    }

    public ReplacementPersistenceDiagnosticsApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Duration readTimeout,
            @Nonnull AvailabilityProbe availability,
            @Nonnull IncidentLookup incidents
    ) {
        if (persistence == null) {
            throw new IllegalArgumentException(
                    "Complete replacement diagnostics dependencies are required"
            );
        }
        this.persistence = persistence;
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "A positive diagnostics timeout is required"
            );
        }
        readTimeoutMs = readTimeout.toMillis();
        this.availability = Objects.requireNonNull(
                availability, "availability"
        );
        this.incidents = Objects.requireNonNull(incidents, "incidents");
    }

    @Override
    @Nonnull
    public PersistenceResilienceView getPersistenceResilience() {
        PublicPersistenceOperationalStatus operational =
                persistence.operationalStatus();
        Optional<PublicPersistenceDiagnosticsSnapshot> details = details();
        int openIncidents = details.map(snapshot ->
                        Math.toIntExact(Math.min(
                                Integer.MAX_VALUE,
                                snapshot.openIncidentsByCode().values()
                                        .stream().mapToLong(Long::longValue)
                                        .sum()
                        )))
                .orElse(0);
        int quarantines = details.map(snapshot ->
                        Math.toIntExact(Math.min(
                                Integer.MAX_VALUE,
                                snapshot.activeQuarantinesByScope().values()
                                        .stream().mapToLong(Long::longValue)
                                        .sum()
                        )))
                .orElse(0);
        List<PersistenceResilienceView.CircuitView> circuits =
                details.map(snapshot -> snapshot.features().values().stream()
                                .sorted(Comparator.comparing(feature ->
                                        feature.featureId().value()))
                                .map(feature ->
                                        new PersistenceResilienceView.CircuitView(
                                                feature.featureId().value(),
                                                feature.circuit().state()
                                                        == PersistenceFeatureCircuitState.CLOSED,
                                                feature.circuit().reasonCode(),
                                                feature.circuit().updatedAtMs()
                                        ))
                                .toList())
                        .orElse(List.of());
        List<PersistenceResilienceView.CoverageView> coverage =
                details.map(snapshot -> snapshot.features().values().stream()
                                .sorted(Comparator.comparing(feature ->
                                        feature.featureId().value()))
                                .map(feature ->
                                        new PersistenceResilienceView.CoverageView(
                                                feature.featureId().value(),
                                                feature.readiness().name(),
                                                feature.readiness()
                                                        == com.alechilles
                                                        .alecstamework.persistence
                                                        .control
                                                        .PersistenceReadinessLevel
                                                        .MUTATION_READY,
                                                feature.circuit().reasonCode(),
                                                0L,
                                                feature.circuit().updatedAtMs(),
                                                0,
                                                false,
                                                nextTrigger(feature.readiness())
                                        ))
                                .toList())
                        .orElse(List.of());
        return new PersistenceResilienceView(
                operational.storageMode().name(),
                operational.startup().detail(),
                null,
                latestStatusTime(details),
                openIncidents,
                quarantines,
                0L,
                circuits,
                coverage
        );
    }

    @Override
    @Nonnull
    public PersistenceMutationAvailabilityView
    queryPersistenceAvailability(
            @Nonnull PersistenceMutationAvailabilityRequest request
    ) {
        Objects.requireNonNull(request, "request");
        try {
            PersistenceMutationAvailabilityView result =
                    availability.query(request);
            return result == null
                    ? PersistenceMutationAvailabilityView.unavailable()
                    : result;
        } catch (RuntimeException failure) {
            return PersistenceMutationAvailabilityView.unavailable();
        }
    }

    @Override
    @Nonnull
    public Optional<PersistenceIncidentSummaryView> findPersistenceIncident(
            @Nonnull String incidentIdOrUniquePrefix
    ) {
        Objects.requireNonNull(
                incidentIdOrUniquePrefix, "incidentIdOrUniquePrefix"
        );
        if (incidentIdOrUniquePrefix.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<PersistenceIncidentSummaryView> result =
                    incidents.find(incidentIdOrUniquePrefix.trim());
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
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

    private Optional<PublicPersistenceDiagnosticsSnapshot> details() {
        try {
            PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot> read =
                    persistence.diagnostics().toCompletableFuture().get(
                            readTimeoutMs, TimeUnit.MILLISECONDS
                    );
            return read instanceof PersistenceReadResult.Found<
                    PublicPersistenceDiagnosticsSnapshot> found
                    ? Optional.of(found.value())
                    : Optional.empty();
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    private long latestStatusTime(
            Optional<PublicPersistenceDiagnosticsSnapshot> details
    ) {
        return details.map(snapshot -> snapshot.features().values().stream()
                        .mapToLong(feature ->
                                feature.circuit().updatedAtMs())
                        .max().orElse(0L))
                .orElse(0L);
    }

    private String nextTrigger(
            com.alechilles.alecstamework.persistence.control
                    .PersistenceReadinessLevel readiness
    ) {
        return switch (readiness) {
            case CLOSED -> "start-persistence";
            case CANONICAL_READ_ONLY, RECOVERING ->
                    "complete-operation-recovery";
            case PROJECTION_READY, WORLD_EVIDENCE_PENDING ->
                    "complete-world-reconciliation";
            case MUTATION_READY -> null;
            case QUARANTINED -> "resolve-feature-quarantine";
            case GLOBAL_READ_ONLY -> "resolve-global-storage-failure";
        };
    }

    /** Exact non-mutating admission probe supplied by the shared control plane. */
    @FunctionalInterface
    public interface AvailabilityProbe {
        @Nonnull
        PersistenceMutationAvailabilityView query(
                @Nonnull PersistenceMutationAvailabilityRequest request
        );
    }

    /** Sanitized bounded incident lookup supplied by diagnostics storage. */
    @FunctionalInterface
    public interface IncidentLookup {
        @Nonnull
        Optional<PersistenceIncidentSummaryView> find(
                @Nonnull String incidentIdOrUniquePrefix
        );
    }
}
