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

    LegacyDatImporter(@Nonnull Path runtimeDataDirectory,
                      @Nonnull SqliteConnectionManager connectionManager) {
        this.runtimeDataDirectory = runtimeDataDirectory;
        this.connectionManager = connectionManager;
    }

    boolean importAll(@Nonnull String migrationId,
                      @Nonnull CaptureRepository captureRepository,
                      @Nonnull CoopLedgerRepository coopRepository,
                      @Nonnull DeathRepository deathRepository,
                      @Nonnull LostRepository lostRepository) throws Exception {
        if (isMigrationApplied(migrationId)) {
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
                captureRepository.replaceAllInTransaction(connection, captureRows);
                coopRepository.replaceAllInTransaction(connection, coopRows);
                deathRepository.replaceAllInTransaction(connection, deathRows);
                lostRepository.replaceAllInTransaction(connection, lostRows);
                recordMigration(connection, migrationId);
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

    private boolean isMigrationApplied(@Nonnull String migrationId) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM schema_migrations WHERE migration_id = ? LIMIT 1"
             )) {
            statement.setString(1, migrationId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void recordMigration(@Nonnull Connection connection, @Nonnull String migrationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations (migration_id, applied_at_ms) VALUES (?, ?)"
        )) {
            statement.setString(1, migrationId);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
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
