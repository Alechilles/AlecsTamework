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
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns SQLite runtime dependencies for linked-NPC persistence domains.
 */
public final class TameworkPersistenceRuntime implements AutoCloseable {
    public static final String SQLITE_FILENAME = "tamework.sqlite";
    private static final String LEGACY_MIGRATION_MARKER_FILE = "tamework.sqlite.legacy-dat-import-v2.marker";
    private static final DateTimeFormatter BACKUP_SUFFIX_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path runtimeDataDirectory;
    private final Path sqlitePath;
    private final PersistenceHealthService healthService;
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final SqliteMaintenanceService maintenanceService;
    private final ApiProfileDataRepository apiProfileDataRepository;
    private final CaptureRepository captureRepository;
    private final CoopLedgerRepository coopLedgerRepository;
    private final DeathRepository deathRepository;
    private final LostRepository lostRepository;
    private final NpcProfileRepository npcProfileRepository;
    private final CompanionPopulationRepository companionPopulationRepository;
    private final CompanionPopulationCoverageRepository companionPopulationCoverageRepository;
    private final CompanionIdentityRepository companionIdentityRepository;
    private final CompanionPopulationReconciliationPersistence populationReconciliationPersistence;
    private final SqliteSchemaMigrator schemaMigrator;

    private TameworkPersistenceRuntime(@Nonnull Path runtimeDataDirectory,
                                       @Nonnull Path sqlitePath,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nonnull SqliteConnectionManager connectionManager,
                                       @Nonnull PersistenceWriteQueue writeQueue,
                                       @Nonnull SqliteMaintenanceService maintenanceService,
                                       @Nonnull ApiProfileDataRepository apiProfileDataRepository,
                                       @Nonnull CaptureRepository captureRepository,
                                       @Nonnull CoopLedgerRepository coopLedgerRepository,
                                       @Nonnull DeathRepository deathRepository,
                                       @Nonnull LostRepository lostRepository,
                                       @Nonnull NpcProfileRepository npcProfileRepository,
                                       @Nonnull CompanionPopulationRepository companionPopulationRepository,
                                       @Nonnull CompanionPopulationCoverageRepository companionPopulationCoverageRepository,
                                       @Nonnull CompanionIdentityRepository companionIdentityRepository,
                                       @Nonnull CompanionPopulationReconciliationPersistence populationReconciliationPersistence,
                                       @Nonnull SqliteSchemaMigrator schemaMigrator) {
        this.runtimeDataDirectory = runtimeDataDirectory;
        this.sqlitePath = sqlitePath;
        this.healthService = healthService;
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.maintenanceService = maintenanceService;
        this.apiProfileDataRepository = apiProfileDataRepository;
        this.captureRepository = captureRepository;
        this.coopLedgerRepository = coopLedgerRepository;
        this.deathRepository = deathRepository;
        this.lostRepository = lostRepository;
        this.npcProfileRepository = npcProfileRepository;
        this.companionPopulationRepository = companionPopulationRepository;
        this.companionPopulationCoverageRepository = companionPopulationCoverageRepository;
        this.companionIdentityRepository = companionIdentityRepository;
        this.populationReconciliationPersistence = populationReconciliationPersistence;
        this.schemaMigrator = schemaMigrator;
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
        try {
            backupService.backupBeforeVersion(
                    sqlitePath,
                    connectionManager,
                    schemaMigrator,
                    SqliteSchemaMigrator.SCHEMA_VERSION_V6
            );
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
        CompanionPopulationRepository companionPopulationRepository =
                new CompanionPopulationRepository(connectionManager, writeQueue);
        CompanionPopulationCoverageRepository companionPopulationCoverageRepository =
                new CompanionPopulationCoverageRepository(connectionManager, writeQueue);
        CompanionIdentityRepository companionIdentityRepository =
                new CompanionIdentityRepository(connectionManager);
        CompanionPopulationReconciliationPersistence populationReconciliationPersistence =
                new CompanionPopulationReconciliationPersistence(connectionManager, writeQueue);
        ApiProfileDataRepository apiProfileDataRepository = new ApiProfileDataRepository(connectionManager, writeQueue);
        CaptureRepository captureRepository = new CaptureRepository(connectionManager, writeQueue, npcProfileRepository);
        CoopLedgerRepository coopLedgerRepository = new CoopLedgerRepository(connectionManager, writeQueue, npcProfileRepository);
        DeathRepository deathRepository = new DeathRepository(connectionManager, writeQueue, npcProfileRepository);
        LostRepository lostRepository = new LostRepository(connectionManager, writeQueue, npcProfileRepository);
        SqliteMaintenanceService maintenanceService =
                new SqliteMaintenanceService(connectionManager, npcProfileRepository, logger);

        TameworkPersistenceRuntime runtime = new TameworkPersistenceRuntime(
                normalizedDataDir,
                sqlitePath,
                health,
                connectionManager,
                writeQueue,
                maintenanceService,
                apiProfileDataRepository,
                captureRepository,
                coopLedgerRepository,
                deathRepository,
                lostRepository,
                npcProfileRepository,
                companionPopulationRepository,
                companionPopulationCoverageRepository,
                companionIdentityRepository,
                populationReconciliationPersistence,
                schemaMigrator
        );

        if (health.isHealthy()) {
            runtime.runLegacyDatImport(logger);
            maintenanceService.start();
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
    public NpcProfileRepository getNpcProfileRepository() {
        return npcProfileRepository;
    }

    @Nonnull
    public CompanionPopulationRepository getCompanionPopulationRepository() {
        return companionPopulationRepository;
    }

    @Nonnull
    public CompanionPopulationCoverageRepository getCompanionPopulationCoverageRepository() {
        return companionPopulationCoverageRepository;
    }

    @Nonnull
    public CompanionIdentityRepository getCompanionIdentityRepository() {
        return companionIdentityRepository;
    }

    @Nonnull
    public CompanionPopulationReconciliationRepository getCompanionPopulationReconciliationRepository() {
        return populationReconciliationPersistence.reconciliationRepository();
    }

    @Nonnull
    public CompanionPopulationRepairRepository getCompanionPopulationRepairRepository() {
        return populationReconciliationPersistence.repairRepository();
    }

    @Nonnull
    public CompanionPopulationLegacyEvidenceRepository getCompanionPopulationLegacyEvidenceRepository() {
        return populationReconciliationPersistence.legacyEvidenceRepository();
    }

    @Nonnull
    public CompanionPopulationObservationRepository getCompanionPopulationObservationRepository() {
        return populationReconciliationPersistence.observationRepository();
    }

    @Nonnull
    public CompanionPopulationScanSessionRepository getCompanionPopulationScanSessionRepository() {
        return populationReconciliationPersistence.scanSessionRepository();
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
        return maintenanceService.requestWalCheckpoint();
    }

    public boolean requestVacuum() {
        return maintenanceService.requestVacuum();
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

    private long safeSize(@Nonnull Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
            return 0L;
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
        } catch (Exception ex) {
            if (isSqliteDriverUnavailable(ex)) {
                throw ex;
            }
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
        maintenanceService.close();
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
