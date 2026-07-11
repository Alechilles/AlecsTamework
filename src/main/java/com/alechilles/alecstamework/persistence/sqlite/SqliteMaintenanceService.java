package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.logger.HytaleLogger;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Schedules snapshot pruning and bounded SQLite WAL/VACUUM maintenance. */
public final class SqliteMaintenanceService implements AutoCloseable {
    private static final long SNAPSHOT_PRUNE_INTERVAL_HOURS = 6L;
    private static final long SNAPSHOT_PRUNE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int SNAPSHOT_PRUNE_MAX_INACTIVE_PER_TYPE = 20;
    private static final long WAL_CHECKPOINT_INTERVAL_MINUTES = 30L;
    private static final long VACUUM_INTERVAL_HOURS = 24L;
    private static final long STARTUP_VACUUM_DELAY_MINUTES = 2L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;

    private final SqliteConnectionManager connectionManager;
    private final NpcProfileRepository profileRepository;
    private final ScheduledExecutorService executor;
    @Nullable
    private final HytaleLogger logger;

    public SqliteMaintenanceService(@Nonnull SqliteConnectionManager connectionManager,
                                    @Nonnull NpcProfileRepository profileRepository,
                                    @Nullable HytaleLogger logger) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.logger = logger;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tamework-persistence-maintenance");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        pruneSnapshots();
        executor.scheduleAtFixedRate(
                this::pruneSnapshots,
                SNAPSHOT_PRUNE_INTERVAL_HOURS,
                SNAPSHOT_PRUNE_INTERVAL_HOURS,
                TimeUnit.HOURS
        );
        runWalCheckpoint();
        executor.scheduleAtFixedRate(
                this::runWalCheckpoint,
                WAL_CHECKPOINT_INTERVAL_MINUTES,
                WAL_CHECKPOINT_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        executor.schedule(this::runVacuum, STARTUP_VACUUM_DELAY_MINUTES, TimeUnit.MINUTES);
        executor.scheduleAtFixedRate(
                this::runVacuum,
                VACUUM_INTERVAL_HOURS,
                VACUUM_INTERVAL_HOURS,
                TimeUnit.HOURS
        );
    }

    public boolean requestWalCheckpoint() {
        return schedule(this::runWalCheckpoint, "wal_checkpoint_request_rejected");
    }

    public boolean requestVacuum() {
        return schedule(this::runVacuum, "vacuum_request_rejected");
    }

    private void pruneSnapshots() {
        long cutoff = System.currentTimeMillis() - SNAPSHOT_PRUNE_RETENTION_MS;
        profileRepository.pruneInactiveSnapshotHistoryAsync(
                cutoff,
                SNAPSHOT_PRUNE_MAX_INACTIVE_PER_TYPE
        );
    }

    private void runWalCheckpoint() {
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (Exception exception) {
            recordFailure("persistence_wal_checkpoint_failed", "wal_checkpoint", exception);
            warn("SQLite WAL checkpoint failed: " + exception.getMessage());
        }
    }

    private void runVacuum() {
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("VACUUM");
        } catch (Exception exception) {
            recordFailure("persistence_vacuum_failed", "vacuum", exception);
            warn("SQLite VACUUM failed: " + exception.getMessage());
        }
    }

    private boolean schedule(@Nonnull Runnable task, @Nonnull String reason) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            recordFailure("persistence_maintenance_rejected", reason, exception);
            warn("SQLite maintenance task rejected: " + reason);
            return false;
        }
    }

    private void recordFailure(@Nonnull String event,
                               @Nonnull String operation,
                               @Nonnull Exception exception) {
        TameworkTelemetryEvents.recordErrorIfAvailable(
                event,
                exception,
                TameworkTelemetryContext.persistence(
                        "maintenance",
                        operation,
                        operation + "_failed",
                        "SQLite maintenance task failed."
                ).build()
        );
    }

    private void warn(@Nonnull String message) {
        if (logger != null) {
            logger.at(Level.WARNING).log(message);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
