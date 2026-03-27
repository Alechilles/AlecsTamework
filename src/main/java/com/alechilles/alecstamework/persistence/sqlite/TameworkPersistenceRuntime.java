package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns SQLite runtime dependencies for linked-NPC persistence domains.
 */
public final class TameworkPersistenceRuntime implements AutoCloseable {
    public static final String SQLITE_FILENAME = "tamework.sqlite";
    private static final String LEGACY_MIGRATION_MARKER_FILE = "tamework.sqlite.legacy-dat-import-v1.marker";
    private static final String LEGACY_MIGRATION_ID = "legacy_dat_import_v1";

    private final Path runtimeDataDirectory;
    private final Path sqlitePath;
    private final PersistenceHealthService healthService;
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final CaptureRepository captureRepository;
    private final CoopLedgerRepository coopLedgerRepository;
    private final DeathRepository deathRepository;
    private final LostRepository lostRepository;
    private final NpcProfileRepository npcProfileRepository;

    private TameworkPersistenceRuntime(@Nonnull Path runtimeDataDirectory,
                                       @Nonnull Path sqlitePath,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nonnull SqliteConnectionManager connectionManager,
                                       @Nonnull PersistenceWriteQueue writeQueue,
                                       @Nonnull CaptureRepository captureRepository,
                                       @Nonnull CoopLedgerRepository coopLedgerRepository,
                                       @Nonnull DeathRepository deathRepository,
                                       @Nonnull LostRepository lostRepository,
                                       @Nonnull NpcProfileRepository npcProfileRepository) {
        this.runtimeDataDirectory = runtimeDataDirectory;
        this.sqlitePath = sqlitePath;
        this.healthService = healthService;
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.captureRepository = captureRepository;
        this.coopLedgerRepository = coopLedgerRepository;
        this.deathRepository = deathRepository;
        this.lostRepository = lostRepository;
        this.npcProfileRepository = npcProfileRepository;
    }

    @Nonnull
    public static TameworkPersistenceRuntime initialize(@Nonnull Path runtimeDataDirectory,
                                                        @Nullable HytaleLogger logger) {
        Path normalizedDataDir = runtimeDataDirectory.toAbsolutePath().normalize();
        Path sqlitePath = normalizedDataDir.resolve(SQLITE_FILENAME);
        PersistenceHealthService health = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        PersistenceWriteQueue writeQueue = new PersistenceWriteQueue(connectionManager, health, logger);

        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                new SqliteSchemaMigrator().migrate(connection);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ex) {
            health.markDegraded("sqlite_schema_bootstrap_failed");
            if (logger != null) {
                logger.at(Level.SEVERE).log("SQLite schema bootstrap failed: " + ex.getMessage());
            }
        }

        CaptureRepository captureRepository = new CaptureRepository(connectionManager, writeQueue);
        CoopLedgerRepository coopLedgerRepository = new CoopLedgerRepository(connectionManager, writeQueue);
        DeathRepository deathRepository = new DeathRepository(connectionManager, writeQueue);
        LostRepository lostRepository = new LostRepository(connectionManager, writeQueue);
        NpcProfileRepository npcProfileRepository = new NpcProfileRepository(writeQueue);

        TameworkPersistenceRuntime runtime = new TameworkPersistenceRuntime(
                normalizedDataDir,
                sqlitePath,
                health,
                connectionManager,
                writeQueue,
                captureRepository,
                coopLedgerRepository,
                deathRepository,
                lostRepository,
                npcProfileRepository
        );

        runtime.runLegacyDatImport(logger);
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

    private void runLegacyDatImport(@Nullable HytaleLogger logger) {
        if (!healthService.isHealthy()) {
            return;
        }
        Path markerPath = runtimeDataDirectory.resolve(LEGACY_MIGRATION_MARKER_FILE);
        if (Files.exists(markerPath)) {
            return;
        }
        try {
            LegacyDatImporter importer = new LegacyDatImporter(runtimeDataDirectory, connectionManager);
            boolean imported = importer.importAll(
                    LEGACY_MIGRATION_ID,
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

    @Override
    public void close() {
        writeQueue.close();
    }
}
