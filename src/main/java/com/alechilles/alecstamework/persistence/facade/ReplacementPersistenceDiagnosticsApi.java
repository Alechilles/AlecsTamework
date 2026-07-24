package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistencePerformanceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public ReplacementPersistenceDiagnosticsApi(
            @Nonnull PersistenceBootstrap persistence
    ) {
        if (persistence == null) {
            throw new IllegalArgumentException(
                    "Complete replacement diagnostics dependencies are required"
            );
        }
        this.persistence = persistence;
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
}
