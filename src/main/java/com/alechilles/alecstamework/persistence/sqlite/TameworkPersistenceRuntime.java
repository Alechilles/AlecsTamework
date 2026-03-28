package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter BACKUP_SUFFIX_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

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
    private final NpcProfileRepository npcProfileRepository;
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
                                       @Nonnull NpcProfileRepository npcProfileRepository,
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
        this.npcProfileRepository = npcProfileRepository;
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
        PersistenceWriteQueue writeQueue = new PersistenceWriteQueue(connectionManager, health, logger);
        SqliteSchemaMigrator schemaMigrator = new SqliteSchemaMigrator();
        ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tamework-persistence-maintenance");
            thread.setDaemon(true);
            return thread;
        });

        try {
            backupAndResetPreV2SqliteIfNeeded(sqlitePath, connectionManager, schemaMigrator);
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
            health.markDegraded("sqlite_schema_bootstrap_failed");
            if (logger != null) {
                logger.at(Level.SEVERE).log("SQLite schema bootstrap failed: " + ex.getMessage());
            }
        }

        NpcProfileRepository npcProfileRepository = new NpcProfileRepository(connectionManager, writeQueue);
        ApiProfileDataRepository apiProfileDataRepository = new ApiProfileDataRepository(connectionManager, writeQueue);
        CaptureRepository captureRepository = new CaptureRepository(connectionManager, writeQueue, npcProfileRepository);
        CoopLedgerRepository coopLedgerRepository = new CoopLedgerRepository(connectionManager, writeQueue, npcProfileRepository);
        DeathRepository deathRepository = new DeathRepository(connectionManager, writeQueue, npcProfileRepository);
        LostRepository lostRepository = new LostRepository(connectionManager, writeQueue, npcProfileRepository);

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
                npcProfileRepository,
                schemaMigrator,
                logger
        );

        runtime.runLegacyDatImport(logger);
        runtime.scheduleSnapshotPruning();
        runtime.scheduleDatabaseMaintenance();
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
    public NpcProfileRepository getNpcProfileRepository() {
        return npcProfileRepository;
    }

    @Nonnull
    public PersistenceWriteQueue.QueueMetrics getWriteQueueMetrics() {
        return writeQueue.getMetrics();
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
            logWarning("SQLite WAL checkpoint failed: " + ex.getMessage());
        }
    }

    private void runVacuum() {
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("VACUUM");
        } catch (Exception ex) {
            logWarning("SQLite VACUUM failed: " + ex.getMessage());
        }
    }

    private boolean scheduleMaintenanceTask(@Nonnull Runnable task, @Nonnull String rejectedReason) {
        try {
            maintenanceExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException ex) {
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

    private static void backupAndResetPreV2SqliteIfNeeded(@Nonnull Path sqlitePath,
                                                           @Nonnull SqliteConnectionManager connectionManager,
                                                           @Nonnull SqliteSchemaMigrator schemaMigrator) throws Exception {
        if (!Files.exists(sqlitePath)) {
            return;
        }
        boolean alreadyV2 = false;
        try (Connection connection = connectionManager.openConnection()) {
            alreadyV2 = schemaMigrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V2);
        } catch (Exception ignored) {
            alreadyV2 = false;
        }
        if (alreadyV2) {
            return;
        }

        String suffix = BACKUP_SUFFIX_FORMAT.format(Instant.now());
        Path backupPath = sqlitePath.resolveSibling("tamework_pre_v2_" + suffix + ".sqlite.bak");
        Files.copy(sqlitePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(sqlitePath);
        Files.deleteIfExists(sqlitePath.resolveSibling(sqlitePath.getFileName() + "-wal"));
        Files.deleteIfExists(sqlitePath.resolveSibling(sqlitePath.getFileName() + "-shm"));
    }

    @Override
    public void close() {
        maintenanceExecutor.shutdownNow();
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
}
