package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns SQLite runtime dependencies for linked-NPC persistence domains.
 */
public final class TameworkPersistenceRuntime implements AutoCloseable {
    public static final String SQLITE_FILENAME = "tamework.sqlite";
    private static final String LEGACY_MIGRATION_MARKER_FILE = "tamework.sqlite.legacy-dat-import-v2.marker";
    private static final long SNAPSHOT_PRUNE_INTERVAL_HOURS = 6L;
    private static final long SNAPSHOT_PRUNE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int SNAPSHOT_PRUNE_MAX_INACTIVE_PER_TYPE = 20;
    private static final long WAL_CHECKPOINT_INTERVAL_MINUTES = 30L;
    private static final long VACUUM_INTERVAL_HOURS = 24L;
    private static final long STARTUP_VACUUM_DELAY_MINUTES = 2L;
    private static final long MAINTENANCE_SHUTDOWN_TIMEOUT_SECONDS = 2L;

    private final Path runtimeDataDirectory;
    private final Path sqlitePath;
    private final PersistenceHealthService healthService;
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final ScheduledExecutorService maintenanceExecutor;
    private final ApiProfileDataRepository apiProfileDataRepository;
    private final CaptureRepository captureRepository;
    private final CoopLedgerRepository coopLedgerRepository;
    private final DeathRepository deathRepository;
    private final LostRepository lostRepository;
    private final ManagedCoopRuntimeServices managedCoopServices;
    private final NpcIdentityRepository npcIdentityRepository;
    private final NpcProfileRepository npcProfileRepository;
    private final NpcRecoveryOperationRepository npcRecoveryOperationRepository;
    private final NpcLiveAliasRepairRepository npcLiveAliasRepairRepository;
    private final PersistenceIntegrityService integrityService;
    private final SqliteSchemaMigrator schemaMigrator;
    @Nullable
    private final HytaleLogger logger;

    private TameworkPersistenceRuntime(@Nonnull Path runtimeDataDirectory,
                                       @Nonnull Path sqlitePath,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nonnull SqliteConnectionManager connectionManager,
                                       @Nonnull PersistenceWriteQueue writeQueue,
                                       @Nonnull ScheduledExecutorService maintenanceExecutor,
                                       @Nonnull ApiProfileDataRepository apiProfileDataRepository,
                                       @Nonnull CaptureRepository captureRepository,
                                       @Nonnull CoopLedgerRepository coopLedgerRepository,
                                       @Nonnull DeathRepository deathRepository,
                                       @Nonnull LostRepository lostRepository,
                                       @Nonnull ManagedCoopRuntimeServices managedCoopServices,
                                       @Nonnull NpcIdentityRepository npcIdentityRepository,
                                       @Nonnull NpcProfileRepository npcProfileRepository,
                                       @Nonnull NpcRecoveryOperationRepository npcRecoveryOperationRepository,
                                       @Nonnull NpcLiveAliasRepairRepository npcLiveAliasRepairRepository,
                                       @Nonnull PersistenceIntegrityService integrityService,
                                       @Nonnull SqliteSchemaMigrator schemaMigrator,
                                       @Nullable HytaleLogger logger) {
        this.runtimeDataDirectory = runtimeDataDirectory;
        this.sqlitePath = sqlitePath;
        this.healthService = healthService;
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.maintenanceExecutor = maintenanceExecutor;
        this.apiProfileDataRepository = apiProfileDataRepository;
        this.captureRepository = captureRepository;
        this.coopLedgerRepository = coopLedgerRepository;
        this.deathRepository = deathRepository;
        this.lostRepository = lostRepository;
        this.managedCoopServices = managedCoopServices;
        this.npcIdentityRepository = npcIdentityRepository;
        this.npcProfileRepository = npcProfileRepository;
        this.npcRecoveryOperationRepository = npcRecoveryOperationRepository;
        this.npcLiveAliasRepairRepository = npcLiveAliasRepairRepository;
        this.integrityService = integrityService;
        this.schemaMigrator = schemaMigrator;
        this.logger = logger;
    }

    @Nonnull
    public static TameworkPersistenceRuntime initialize(@Nonnull Path runtimeDataDirectory,
                                                        @Nullable HytaleLogger logger) {
        Path normalizedDataDir = runtimeDataDirectory.toAbsolutePath().normalize();
        Path sqlitePath = normalizedDataDir.resolve(SQLITE_FILENAME);
        PersistenceHealthService health = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        SqliteSchemaMigrator schemaMigrator = new SqliteSchemaMigrator();
        SqliteMigrationBackupService backupService = new SqliteMigrationBackupService();
        ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tamework-persistence-maintenance");
            thread.setDaemon(true);
            return thread;
        });

        try {
            backupService.backupBeforeVersion(
                    sqlitePath,
                    connectionManager,
                    schemaMigrator,
                    SqliteSchemaMigrator.SCHEMA_VERSION_V5
            );
            try (Connection connection = connectionManager.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    schemaMigrator.migrate(connection);
                    connection.commit();
                } catch (Exception ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (Exception ex) {
            String healthReason = isSqliteDriverUnavailable(ex)
                    ? "sqlite_native_unavailable"
                    : "sqlite_schema_bootstrap_failed";
            health.markDegraded(healthReason);
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_schema_bootstrap_failed",
                    ex,
                    TameworkTelemetryContext.persistence(
                            "runtime",
                            "schema_bootstrap",
                            healthReason,
                            "SQLite schema bootstrap failed."
                    ).build()
            );
            if (logger != null) {
                logger.at(Level.SEVERE).log("SQLite schema bootstrap failed: " + ex.getMessage());
            }
        }

        PersistenceWriteQueue writeQueue = new PersistenceWriteQueue(connectionManager, health, logger);

        NpcProfileRepository npcProfileRepository = new NpcProfileRepository(connectionManager, writeQueue);
        ApiProfileDataRepository apiProfileDataRepository = new ApiProfileDataRepository(connectionManager, writeQueue);
        CaptureRepository captureRepository = new CaptureRepository(connectionManager, writeQueue, npcProfileRepository);
        CoopLedgerRepository coopLedgerRepository = new CoopLedgerRepository(connectionManager, writeQueue, npcProfileRepository);
        DeathRepository deathRepository = new DeathRepository(connectionManager, writeQueue, npcProfileRepository);
        LostRepository lostRepository = new LostRepository(connectionManager, writeQueue, npcProfileRepository);
        ManagedCoopRuntimeServices managedCoopServices = new ManagedCoopRuntimeServices(
                connectionManager,
                writeQueue,
                npcProfileRepository,
                logger
        );
        NpcIdentityRepository npcIdentityRepository = new NpcIdentityRepository(connectionManager);
        NpcRecoveryOperationRepository npcRecoveryOperationRepository =
                new NpcRecoveryOperationRepository(connectionManager, writeQueue);
        NpcLiveAliasRepairRepository npcLiveAliasRepairRepository =
                new NpcLiveAliasRepairRepository(writeQueue);
        PersistenceIntegrityService integrityService =
                new PersistenceIntegrityService(connectionManager);

        TameworkPersistenceRuntime runtime = new TameworkPersistenceRuntime(
                normalizedDataDir,
                sqlitePath,
                health,
                connectionManager,
                writeQueue,
                maintenanceExecutor,
                apiProfileDataRepository,
                captureRepository,
                coopLedgerRepository,
                deathRepository,
                lostRepository,
                managedCoopServices,
                npcIdentityRepository,
                npcProfileRepository,
                npcRecoveryOperationRepository,
                npcLiveAliasRepairRepository,
                integrityService,
                schemaMigrator,
                logger
        );

        if (health.isHealthy()) {
            runtime.runLegacyDatImport(logger);
            managedCoopServices.compositeIndexRefreshService().refresh();
            runtime.scheduleSnapshotPruning();
            runtime.scheduleDatabaseMaintenance();
        }
        return runtime;
    }

    @Nonnull
    public Path getRuntimeDataDirectory() {
        return runtimeDataDirectory;
    }

    @Nonnull
    public Path getSqlitePath() {
        return sqlitePath;
    }

    @Nonnull
    public PersistenceHealthService getHealthService() {
        return healthService;
    }

    @Nonnull
    public CaptureRepository getCaptureRepository() {
        return captureRepository;
    }

    @Nonnull
    public ApiProfileDataRepository getApiProfileDataRepository() {
        return apiProfileDataRepository;
    }

    @Nonnull
    public CoopLedgerRepository getCoopLedgerRepository() {
        return coopLedgerRepository;
    }

    @Nonnull
    public DeathRepository getDeathRepository() {
        return deathRepository;
    }

    @Nonnull
    public LostRepository getLostRepository() {
        return lostRepository;
    }

    @Nonnull
    public ManagedCoopResidentRepository getManagedCoopResidentRepository() {
        return managedCoopServices.residentRepository();
    }

    @Nonnull
    public CoopLifecycleOperationRepository getCoopLifecycleOperationRepository() {
        return managedCoopServices.lifecycleRepository();
    }

    @Nonnull
    public ManagedCoopRuntimeServices getManagedCoopServices() {
        return managedCoopServices;
    }

    @Nonnull
    public NpcIdentityRepository getNpcIdentityRepository() {
        return npcIdentityRepository;
    }

    @Nonnull
    public NpcProfileRepository getNpcProfileRepository() {
        return npcProfileRepository;
    }

    @Nonnull
    public NpcRecoveryOperationRepository getNpcRecoveryOperationRepository() {
        return npcRecoveryOperationRepository;
    }

    @Nonnull
    public NpcLiveAliasRepairRepository getNpcLiveAliasRepairRepository() {
        return npcLiveAliasRepairRepository;
    }

    @Nonnull
    public PersistenceIntegrityService getIntegrityService() {
        return integrityService;
    }

    @Nonnull
    public PersistenceWriteQueue.QueueMetrics getWriteQueueMetrics() {
        return writeQueue.getMetrics();
    }

    @Nonnull
    public PersistenceWriteQueue.QueueLifecycleMetrics getWriteQueueLifecycleMetrics() {
        return writeQueue.getLifecycleMetrics();
    }

    public boolean awaitWriteQueueIdle(long timeoutMs) {
        return writeQueue.awaitIdle(timeoutMs);
    }

    @Nonnull
    public PersistenceHealthService.HealthState getHealthState() {
        return healthService.getState();
    }

    @Nonnull
    public PersistenceDiagnostics collectDiagnostics() {
        PersistenceWriteQueue.QueueMetrics queueMetrics = writeQueue.getMetrics();
        PersistenceHealthService.HealthState healthState = healthService.getState();
        long sqliteBytes = safeSize(sqlitePath);
        long walBytes = safeSize(sqlitePath.resolveSibling(sqlitePath.getFileName() + "-wal"));
        long shmBytes = safeSize(sqlitePath.resolveSibling(sqlitePath.getFileName() + "-shm"));
        return new PersistenceDiagnostics(
                sqlitePath,
                sqliteBytes,
                walBytes,
                shmBytes,
                sqliteBytes + walBytes + shmBytes,
                queueMetrics,
                healthState
        );
    }

    public boolean requestWalCheckpoint() {
        return scheduleMaintenanceTask(this::runWalCheckpoint, "wal_checkpoint_request_rejected");
    }

    public boolean requestVacuum() {
        return scheduleMaintenanceTask(this::runVacuum, "vacuum_request_rejected");
    }

    private void runLegacyDatImport(@Nullable HytaleLogger logger) {
        if (!healthService.isHealthy()) {
            return;
        }
        Path markerPath = runtimeDataDirectory.resolve(LEGACY_MIGRATION_MARKER_FILE);
        if (Files.exists(markerPath)) {
            return;
        }
        try {
            LegacyDatImporter importer = new LegacyDatImporter(runtimeDataDirectory, connectionManager, schemaMigrator);
            boolean imported = importer.importAll(
                    captureRepository,
                    coopLedgerRepository,
                    deathRepository,
                    lostRepository
            );
            if (imported) {
                Files.writeString(markerPath, "ok");
            }
        } catch (Exception ex) {
            healthService.markDegraded("legacy_dat_import_failed");
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_legacy_import_failed",
                    ex,
                    TameworkTelemetryContext.persistence(
                            "runtime",
                            "legacy_import",
                            "legacy_dat_import_failed",
                            "Legacy .dat migration failed."
                    ).build()
            );
            if (logger != null) {
                logger.at(Level.SEVERE).log("Legacy .dat migration failed: " + ex.getMessage());
            }
        }
    }

    private void scheduleSnapshotPruning() {
        long now = System.currentTimeMillis();
        long cutoff = now - SNAPSHOT_PRUNE_RETENTION_MS;
        npcProfileRepository.pruneInactiveSnapshotHistoryAsync(cutoff, SNAPSHOT_PRUNE_MAX_INACTIVE_PER_TYPE);
        maintenanceExecutor.scheduleAtFixedRate(
                () -> {
                    long currentCutoff = System.currentTimeMillis() - SNAPSHOT_PRUNE_RETENTION_MS;
                    npcProfileRepository.pruneInactiveSnapshotHistoryAsync(
                            currentCutoff,
                            SNAPSHOT_PRUNE_MAX_INACTIVE_PER_TYPE
                    );
                },
                SNAPSHOT_PRUNE_INTERVAL_HOURS,
                SNAPSHOT_PRUNE_INTERVAL_HOURS,
                TimeUnit.HOURS
        );
    }

    private void scheduleDatabaseMaintenance() {
        runWalCheckpoint();
        maintenanceExecutor.scheduleAtFixedRate(
                this::runWalCheckpoint,
                WAL_CHECKPOINT_INTERVAL_MINUTES,
                WAL_CHECKPOINT_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        maintenanceExecutor.schedule(this::runVacuum, STARTUP_VACUUM_DELAY_MINUTES, TimeUnit.MINUTES);
        maintenanceExecutor.scheduleAtFixedRate(
                this::runVacuum,
                VACUUM_INTERVAL_HOURS,
                VACUUM_INTERVAL_HOURS,
                TimeUnit.HOURS
        );
    }

    private void runWalCheckpoint() {
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (Exception ex) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_wal_checkpoint_failed",
                    ex,
                    TameworkTelemetryContext.persistence(
                            "maintenance",
                            "wal_checkpoint",
                            "wal_checkpoint_failed",
                            "SQLite WAL checkpoint failed."
                    ).build()
            );
            logWarning("SQLite WAL checkpoint failed: " + ex.getMessage());
        }
    }

    private void runVacuum() {
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("VACUUM");
        } catch (Exception ex) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_vacuum_failed",
                    ex,
                    TameworkTelemetryContext.persistence(
                            "maintenance",
                            "vacuum",
                            "vacuum_failed",
                            "SQLite VACUUM failed."
                    ).build()
            );
            logWarning("SQLite VACUUM failed: " + ex.getMessage());
        }
    }

    private boolean scheduleMaintenanceTask(@Nonnull Runnable task, @Nonnull String rejectedReason) {
        try {
            maintenanceExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException ex) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_maintenance_rejected",
                    ex,
                    TameworkTelemetryContext.persistence(
                            "maintenance",
                            "schedule_task",
                            rejectedReason,
                            "SQLite maintenance task rejected."
                    ).build()
            );
            logWarning("SQLite maintenance task rejected: " + rejectedReason);
            return false;
        }
    }

    private long safeSize(@Nonnull Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void logWarning(@Nonnull String message) {
        if (logger != null) {
            logger.at(Level.WARNING).log(message);
        }
    }

    @Override
    public void close() {
        maintenanceExecutor.shutdownNow();
        try {
            maintenanceExecutor.awaitTermination(MAINTENANCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        writeQueue.close();
    }

    public record PersistenceDiagnostics(@Nonnull Path databasePath,
                                         long sqliteBytes,
                                         long walBytes,
                                         long shmBytes,
                                         long totalBytes,
                                         @Nonnull PersistenceWriteQueue.QueueMetrics queueMetrics,
                                         @Nonnull PersistenceHealthService.HealthState healthState) {
    }

    static boolean isSqliteDriverUnavailable(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof LinkageError) {
                return true;
            }
            if (current instanceof SQLException) {
                String message = current.getMessage();
                if ("sqlite_native_unavailable".equals(message)
                        || "sqlite_jdbc_driver_missing".equals(message)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
