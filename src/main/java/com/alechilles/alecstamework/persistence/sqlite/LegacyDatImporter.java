package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * One-time importer for legacy .dat files into SQLite storage.
 */
final class LegacyDatImporter {
    private static final DateTimeFormatter BACKUP_SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path runtimeDataDirectory;
    private final SqliteConnectionManager connectionManager;
    private final SqliteSchemaMigrator schemaMigrator;

    LegacyDatImporter(@Nonnull Path runtimeDataDirectory,
                      @Nonnull SqliteConnectionManager connectionManager,
                      @Nonnull SqliteSchemaMigrator schemaMigrator) {
        this.runtimeDataDirectory = runtimeDataDirectory;
        this.connectionManager = connectionManager;
        this.schemaMigrator = schemaMigrator;
    }

    boolean importAll(@Nonnull CaptureRepository captureRepository,
                      @Nonnull CoopLedgerRepository coopRepository,
                      @Nonnull DeathRepository deathRepository,
                      @Nonnull LostRepository lostRepository) throws Exception {
        if (isMigrationApplied()) {
            return false;
        }

        Path capturesDat = runtimeDataDirectory.resolve("CommandLinkedNpcCaptures.dat");
        Path coopsDat = runtimeDataDirectory.resolve("CommandLinkedNpcCoops.dat");
        Path deathsDat = runtimeDataDirectory.resolve("CommandLinkedNpcDeaths.dat");
        Path lostDat = runtimeDataDirectory.resolve("CommandLinkedNpcLost.dat");

        List<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> captureRows =
                CommandLinkedNpcCaptureService.loadLegacySnapshots(capturesDat);
        List<CoopLedgerRow> coopRows = CommandLinkedNpcCoopService.loadLegacyLedgerRows(coopsDat);
        List<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> deathRows =
                CommandLinkedNpcDeathService.loadLegacySnapshots(deathsDat);
        List<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> lostRows =
                CommandLinkedNpcLostService.loadLegacySnapshots(lostDat);

        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot row : captureRows) {
                    captureRepository.upsertInTransaction(connection, row);
                }
                for (CoopLedgerRow row : coopRows) {
                    coopRepository.upsertSlotInTransaction(connection, row);
                }
                for (CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot row : deathRows) {
                    deathRepository.upsertInTransaction(connection, row);
                }
                for (CommandLinkedNpcLostService.LostLinkedNpcSnapshot row : lostRows) {
                    lostRepository.upsertInTransaction(connection, row);
                }
                schemaMigrator.reconcileSchemaV5Data(connection);
                schemaMigrator.recordMigration(
                        connection,
                        SqliteSchemaMigrator.MIGRATION_VERSION_LEGACY_DAT_IMPORT_V2,
                        SqliteSchemaMigrator.MIGRATION_NAME_LEGACY_DAT_IMPORT_V2
                );
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        Path backupRoot = runtimeDataDirectory
                .resolve("LegacyDatBackup")
                .resolve(LocalDateTime.now().format(BACKUP_SUFFIX_FORMAT));
        Files.createDirectories(backupRoot);
        backupIfExists(capturesDat, backupRoot);
        backupIfExists(coopsDat, backupRoot);
        backupIfExists(deathsDat, backupRoot);
        backupIfExists(lostDat, backupRoot);
        backupIfExists(runtimeDataDirectory.resolve("CoopResidentSnapshots.dat"), backupRoot);
        return true;
    }

    private boolean isMigrationApplied() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM schema_migrations WHERE version = ? LIMIT 1"
             )) {
            statement.setInt(1, SqliteSchemaMigrator.MIGRATION_VERSION_LEGACY_DAT_IMPORT_V2);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void backupIfExists(@Nonnull Path source, @Nonnull Path backupDirectory) throws Exception {
        if (!Files.exists(source)) {
            return;
        }
        Files.move(
                source,
                backupDirectory.resolve(source.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING
        );
    }
}
