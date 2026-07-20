package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.metrics.TameworkPersistenceTelemetry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageState;
import com.alechilles.alecstamework.persistence.diagnostics.CompositePersistenceIncidentSink;
import com.alechilles.alecstamework.persistence.diagnostics.CoalescingPersistenceIncidentSink;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentJournal;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentSink;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceResilienceRuntime;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.recovery.ScopedPersistenceRecoveryCoordinator;
import com.alechilles.alecstamework.persistence.recovery.StorageRecoveryCoordinator;
import com.alechilles.alecstamework.persistence.recovery.StorageRecoveryProbe;
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
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns SQLite runtime dependencies for linked-NPC and population persistence domains. */
public final class TameworkPersistenceRuntime implements AutoCloseable {
    public static final String SQLITE_FILENAME = "tamework.sqlite";
    private static final String LEGACY_MIGRATION_MARKER_FILE =
            "tamework.sqlite.legacy-dat-import-v2.marker";
    private static final DateTimeFormatter BACKUP_SUFFIX_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path runtimeDataDirectory;
    private final Path sqlitePath;
    private final String bootId;
    private final PersistenceStorageHealthService storageHealthService;
    private final PersistenceHealthService healthService;
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final SqliteMaintenanceService maintenanceService;
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
    private final CompanionPopulationRepository companionPopulationRepository;
    private final CompanionPopulationCoverageRepository companionPopulationCoverageRepository;
    private final CompanionIdentityRepository companionIdentityRepository;
    private final CompanionPopulationReconciliationPersistence populationReconciliationPersistence;
    private final SqliteSchemaMigrator schemaMigrator;
    private final PersistenceResilienceRuntime resilienceRuntime;
    private final StorageRecoveryCoordinator storageRecoveryCoordinator;
    private final PersistenceIncidentJournal incidentJournal;

    private TameworkPersistenceRuntime(
            @Nonnull Path runtimeDataDirectory,
            @Nonnull Path sqlitePath,
            @Nonnull String bootId,
            @Nonnull PersistenceStorageHealthService storageHealthService,
            @Nonnull PersistenceHealthService healthService,
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull PersistenceWriteQueue writeQueue,
            @Nonnull SqliteMaintenanceService maintenanceService,
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
            @Nonnull CompanionPopulationRepository companionPopulationRepository,
            @Nonnull CompanionPopulationCoverageRepository companionPopulationCoverageRepository,
            @Nonnull CompanionIdentityRepository companionIdentityRepository,
            @Nonnull CompanionPopulationReconciliationPersistence populationReconciliationPersistence,
            @Nonnull SqliteSchemaMigrator schemaMigrator,
            @Nonnull PersistenceResilienceRuntime resilienceRuntime,
            @Nonnull StorageRecoveryCoordinator storageRecoveryCoordinator,
            @Nonnull PersistenceIncidentJournal incidentJournal) {
        this.runtimeDataDirectory = runtimeDataDirectory;
        this.sqlitePath = sqlitePath;
        this.bootId = bootId;
        this.storageHealthService = storageHealthService;
        this.healthService = healthService;
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.maintenanceService = maintenanceService;
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
        this.companionPopulationRepository = companionPopulationRepository;
        this.companionPopulationCoverageRepository = companionPopulationCoverageRepository;
        this.companionIdentityRepository = companionIdentityRepository;
        this.populationReconciliationPersistence = populationReconciliationPersistence;
        this.schemaMigrator = schemaMigrator;
        this.resilienceRuntime = resilienceRuntime;
        this.storageRecoveryCoordinator = storageRecoveryCoordinator;
        this.incidentJournal = incidentJournal;
    }

    @Nonnull
    public static TameworkPersistenceRuntime initialize(@Nonnull Path runtimeDataDirectory,
                                                        @Nullable HytaleLogger logger) {
        Path normalizedDataDir = runtimeDataDirectory.toAbsolutePath().normalize();
        Path sqlitePath = normalizedDataDir.resolve(SQLITE_FILENAME);
        String bootId = UUID.randomUUID().toString();
        PersistenceStorageHealthService storageHealth = new PersistenceStorageHealthService(state -> {
            if (logger != null && state.status() == PersistenceStorageState.READ_ONLY) {
                logger.at(Level.WARNING).log(
                        "Tamework persistence entered read-only state: " + state.reason()
                );
            }
        });
        PersistenceHealthService health = new PersistenceHealthService(storageHealth);
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        SqliteSchemaMigrator schemaMigrator = new SqliteSchemaMigrator();
        SqliteMigrationBackupService backupService = new SqliteMigrationBackupService();
        try {
            backupService.backupBeforeVersion(
                    sqlitePath,
                    connectionManager,
                    schemaMigrator,
                    SqliteSchemaMigrator.SCHEMA_VERSION_V7
            );
            backupAndResetPreV2SqliteIfNeeded(sqlitePath, connectionManager, schemaMigrator);
            try (Connection connection = connectionManager.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    schemaMigrator.migrate(connection);
                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (Exception exception) {
            String healthReason = isSqliteDriverUnavailable(exception)
                    ? "sqlite_native_unavailable"
                    : "sqlite_schema_bootstrap_failed";
            storageHealth.enterReadOnly(healthReason, null);
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_schema_bootstrap_failed",
                    exception,
                    TameworkTelemetryContext.persistence(
                            "runtime",
                            "schema_bootstrap",
                            healthReason,
                            "SQLite schema bootstrap failed."
                    ).build()
            );
            if (logger != null) {
                logger.at(Level.SEVERE).log(
                        "SQLite schema bootstrap failed: " + exception.getMessage()
                );
            }
        }

        PersistenceWriteQueue writeQueue = new PersistenceWriteQueue(connectionManager, health, logger);
        PersistenceIncidentJournal incidentJournal = new PersistenceIncidentJournal(
                normalizedDataDir.resolve("Diagnostics").resolve("Persistence"), bootId, logger);
        PersistenceIncidentSink incidentSink = new CoalescingPersistenceIncidentSink(
                new CompositePersistenceIncidentSink(List.of(
                        incidentJournal, new TameworkPersistenceTelemetry())));
        PersistenceScopeFactory scopeFactory;
        try {
            scopeFactory = PersistenceScopeFactory.loadOrCreate(
                    incidentJournal.directory().resolve("identity-salt.bin"));
        } catch (Exception failure) {
            scopeFactory = PersistenceScopeFactory.ephemeral();
            if (logger != null) {
                logger.at(Level.WARNING).log(
                        "Persistence diagnostic identity salt is unavailable; using a boot-local salt.");
            }
        }
        PersistenceResilienceRuntime resilienceRuntime = PersistenceResilienceRuntime.initialize(
                bootId, connectionManager, writeQueue, storageHealth, logger,
                incidentSink, scopeFactory);
        NpcProfileRepository npcProfileRepository = new NpcProfileRepository(connectionManager, writeQueue);
        ApiProfileDataRepository apiProfileDataRepository =
                new ApiProfileDataRepository(connectionManager, writeQueue);
        CaptureRepository captureRepository =
                new CaptureRepository(connectionManager, writeQueue, npcProfileRepository);
        CoopLedgerRepository coopLedgerRepository =
                new CoopLedgerRepository(connectionManager, writeQueue, npcProfileRepository);
        DeathRepository deathRepository =
                new DeathRepository(connectionManager, writeQueue, npcProfileRepository);
        LostRepository lostRepository =
                new LostRepository(connectionManager, writeQueue, npcProfileRepository);
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
        PersistenceIntegrityService integrityService = new PersistenceIntegrityService(connectionManager);
        CompanionPopulationRepository companionPopulationRepository =
                new CompanionPopulationRepository(
                        connectionManager,
                        writeQueue,
                        managedCoopServices.lifecycleRepository()
                );
        CompanionPopulationCoverageRepository companionPopulationCoverageRepository =
                new CompanionPopulationCoverageRepository(connectionManager, writeQueue);
        CompanionIdentityRepository companionIdentityRepository =
                new CompanionIdentityRepository(connectionManager);
        CompanionPopulationReconciliationPersistence populationReconciliationPersistence =
                new CompanionPopulationReconciliationPersistence(connectionManager, writeQueue);
        SqliteMaintenanceService maintenanceService =
                new SqliteMaintenanceService(connectionManager, npcProfileRepository, logger);
        StorageRecoveryProbe storageRecoveryProbe = new StorageRecoveryProbe(
                bootId, connectionManager, schemaMigrator, integrityService, storageHealth,
                resilienceRuntime.quarantineRepository(), resilienceRuntime.quarantines(),
                resilienceRuntime.circuitRepository(), resilienceRuntime.circuits(),
                List.of(managedCoopServices.compositeIndexRefreshService()::refresh));
        StorageRecoveryCoordinator storageRecoveryCoordinator =
                new StorageRecoveryCoordinator(storageHealth, storageRecoveryProbe, bootId, incidentSink);

        TameworkPersistenceRuntime runtime = new TameworkPersistenceRuntime(
                normalizedDataDir,
                sqlitePath,
                bootId,
                storageHealth,
                health,
                connectionManager,
                writeQueue,
                maintenanceService,
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
                companionPopulationRepository,
                companionPopulationCoverageRepository,
                companionIdentityRepository,
                populationReconciliationPersistence,
                schemaMigrator,
                resilienceRuntime,
                storageRecoveryCoordinator,
                incidentJournal
        );

        if (health.isHealthy()) {
            runtime.runLegacyDatImport(logger);
            boolean coopRepairReady = runtime.reconcileStaleManagedCoopResidents(logger);
            if (health.isHealthy()) {
                if (coopRepairReady) runtime.publishManagedCoopIndexCoverage();
                maintenanceService.start();
            }
        }
        resilienceRuntime.scopedRecovery().scheduleOpenIncidentsAfterStartup();
        storageRecoveryCoordinator.start();
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
    public String getBootId() {
        return bootId;
    }

    @Nonnull
    public PersistenceStorageHealthService getStorageHealthService() {
        return storageHealthService;
    }

    @Nonnull
    public PersistenceHealthService getHealthService() {
        return healthService;
    }

    @Nonnull
    public PersistenceIncidentRepository getIncidentRepository() {
        return resilienceRuntime.incidents();
    }

    @Nonnull
    public PersistenceQuarantineRepository getQuarantineRepository() {
        return resilienceRuntime.quarantineRepository();
    }

    @Nonnull
    public PersistenceQuarantineRegistry getQuarantineRegistry() {
        return resilienceRuntime.quarantines();
    }

    @Nonnull
    public PersistenceFeatureCircuitRepository getFeatureCircuitRepository() {
        return resilienceRuntime.circuitRepository();
    }

    @Nonnull
    public PersistenceFeatureCircuitRegistry getFeatureCircuitRegistry() {
        return resilienceRuntime.circuits();
    }

    @Nonnull
    public PersistenceIncidentReporter getIncidentReporter() {
        return resilienceRuntime.reporter();
    }

    @Nonnull
    public PersistenceMutationAvailabilityService getMutationAvailabilityService() {
        return resilienceRuntime.availability();
    }

    @Nonnull
    public ScopedPersistenceRecoveryCoordinator getScopedRecoveryCoordinator() {
        return resilienceRuntime.scopedRecovery();
    }

    @Nonnull
    public StorageRecoveryCoordinator getStorageRecoveryCoordinator() {
        return storageRecoveryCoordinator;
    }

    @Nonnull
    public PersistenceIncidentJournal getIncidentJournal() {
        return incidentJournal;
    }

    @Nonnull
    public PersistenceScopeFactory getPersistenceScopeFactory() {
        return resilienceRuntime.scopeFactory();
    }

    @Nonnull
    public PersistenceCoverageRegistry getPersistenceCoverageRegistry() {
        return resilienceRuntime.coverage();
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
    public ManagedCoopImportRepository getManagedCoopImportRepository() {
        return managedCoopServices.importRepository();
    }

    @Nonnull
    public ManagedCoopRuntimeServices getManagedCoopServices() {
        return managedCoopServices;
    }

    /** Creates a stateless read-only audit facade over the current managed-coop database/indexes. */
    @Nonnull
    public ManagedCoopDiagnosticsService getManagedCoopDiagnosticsService() {
        return new ManagedCoopDiagnosticsService(connectionManager, managedCoopServices);
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
    public CompanionPopulationReconciliationRepository
    getCompanionPopulationReconciliationRepository() {
        return populationReconciliationPersistence.reconciliationRepository();
    }

    @Nonnull
    public CompanionPopulationRepairRepository getCompanionPopulationRepairRepository() {
        return populationReconciliationPersistence.repairRepository();
    }

    @Nonnull
    public CompanionPopulationLegacyEvidenceRepository
    getCompanionPopulationLegacyEvidenceRepository() {
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

    /** Restart-recovery view published only after a content-stable persisted-world scan. */
    @Nonnull
    public CompanionPersistedProjectionEvidenceRegistry
    getCompanionPersistedProjectionEvidenceRegistry() {
        return populationReconciliationPersistence.projectionEvidenceRegistry();
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
            LegacyDatImporter importer =
                    new LegacyDatImporter(runtimeDataDirectory, connectionManager, schemaMigrator);
            boolean imported = importer.importAll(
                    captureRepository,
                    coopLedgerRepository,
                    deathRepository,
                    lostRepository
            );
            if (imported) {
                Files.writeString(markerPath, "ok");
            }
        } catch (Exception exception) {
            healthService.markDegraded("legacy_dat_import_failed");
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_legacy_import_failed",
                    exception,
                    TameworkTelemetryContext.persistence(
                            "runtime",
                            "legacy_import",
                            "legacy_dat_import_failed",
                            "Legacy .dat migration failed."
                    ).build()
            );
            if (logger != null) {
                logger.at(Level.SEVERE).log(
                        "Legacy .dat migration failed: " + exception.getMessage()
                );
            }
        }
    }

    /** Repairs canonically disproved managed-coop residents before indexes become visible. */
    private boolean reconcileStaleManagedCoopResidents(@Nullable HytaleLogger logger) {
        if (!healthService.isHealthy()) {
            return false;
        }
        try {
            ManagedCoopStaleResidentReconciler.RepairResult result =
                    managedCoopServices.reconcileStaleResidents();
            if (logger != null && result.repairedCount() > 0) {
                logger.at(Level.INFO).log(
                        "Repaired stale managed-coop residents: " + result.repairedCount()
                );
            }
            return true;
        } catch (Exception exception) {
            String reason = "managed_coop_stale_resident_repair_failed";
            reportManagedCoopCoverageFailure(reason, exception);
            if (logger != null) {
                logger.at(Level.WARNING).log(
                        "Managed-coop startup reconciliation failed: " + exception.getMessage()
                );
            }
            return false;
        }
    }

    private void publishManagedCoopIndexCoverage() {
        var refresh = managedCoopServices.compositeIndexRefreshService().refresh();
        String reason = refresh.refreshed() ? "loaded" : refresh.detail();
        resilienceRuntime.coverage().publish(PersistenceEvidenceDimension.MANAGED_COOP_CATALOG,
                refresh.refreshed(), reason, System.currentTimeMillis());
        if (!refresh.refreshed()) {
            reportManagedCoopCoverageFailure("managed_coop_index_refresh_failed", null);
        }
    }

    private void reportManagedCoopCoverageFailure(String reason, @Nullable Throwable failure) {
        resilienceRuntime.coverage().publish(PersistenceEvidenceDimension.MANAGED_COOP_CATALOG,
                false, reason, System.currentTimeMillis());
        resilienceRuntime.reporter().report(new PersistenceFailureContext(
                normalizeReason(reason), PersistenceDomain.MANAGED_COOP_AUTOMATION,
                PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.ROLLED_BACK,
                List.of(resilienceRuntime.scopeFactory().featureDomain(
                        PersistenceDomain.MANAGED_COOP_AUTOMATION,
                        PersistenceEvidenceDimension.MANAGED_COOP_CATALOG.key())),
                true, true, false, false, false,
                false, true, false, null, failure));
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "managed_coop_coverage_unavailable";
        return reason.trim().replace('-', '_').replace(':', '_').replace(';', '_');
    }

    private long safeSize(@Nonnull Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static void backupAndResetPreV2SqliteIfNeeded(
            @Nonnull Path sqlitePath,
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull SqliteSchemaMigrator schemaMigrator) throws Exception {
        if (!Files.exists(sqlitePath)) {
            return;
        }
        boolean alreadyV2;
        try (Connection connection = connectionManager.openConnection()) {
            alreadyV2 = schemaMigrator.isVersionApplied(
                    connection,
                    SqliteSchemaMigrator.SCHEMA_VERSION_V2
            );
        } catch (Exception exception) {
            if (isSqliteDriverUnavailable(exception)) {
                throw exception;
            }
            alreadyV2 = false;
        }
        if (alreadyV2) {
            return;
        }

        String suffix = BACKUP_SUFFIX_FORMAT.format(Instant.now());
        Path backupPath = sqlitePath.resolveSibling(
                "tamework_pre_v2_" + suffix + ".sqlite.bak"
        );
        Files.copy(sqlitePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(sqlitePath);
        Files.deleteIfExists(sqlitePath.resolveSibling(sqlitePath.getFileName() + "-wal"));
        Files.deleteIfExists(sqlitePath.resolveSibling(sqlitePath.getFileName() + "-shm"));
    }

    @Override
    public void close() {
        maintenanceService.close();
        storageRecoveryCoordinator.close();
        resilienceRuntime.close();
        writeQueue.close();
        incidentJournal.close();
        storageHealthService.close();
    }

    public record PersistenceDiagnostics(
            @Nonnull Path databasePath,
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
