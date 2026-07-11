package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Creates a transactionally consistent SQLite snapshot before a schema upgrade.
 */
public final class SqliteMigrationBackupService {
    private static final DateTimeFormatter SUFFIX_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Nonnull
    public Optional<Path> backupBeforeVersion(@Nonnull Path sqlitePath,
                                              @Nonnull SqliteConnectionManager connectionManager,
                                              @Nonnull SqliteSchemaMigrator schemaMigrator,
                                              int targetVersion) throws Exception {
        if (!Files.exists(sqlitePath) || Files.size(sqlitePath) == 0L) {
            return Optional.empty();
        }
        try (Connection connection = connectionManager.openConnection()) {
            if (isApplied(connection, schemaMigrator, targetVersion)) {
                return Optional.empty();
            }
            Path backupPath = uniqueBackupPath(sqlitePath, targetVersion);
            String escapedPath = backupPath.toAbsolutePath().normalize().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }
            if (!Files.exists(backupPath) || Files.size(backupPath) == 0L) {
                throw new IllegalStateException("SQLite migration backup was not created: " + backupPath);
            }
            return Optional.of(backupPath);
        }
    }

    private boolean isApplied(@Nonnull Connection connection,
                              @Nonnull SqliteSchemaMigrator schemaMigrator,
                              int targetVersion) {
        try {
            return schemaMigrator.isVersionApplied(connection, targetVersion);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nonnull
    private Path uniqueBackupPath(@Nonnull Path sqlitePath, int targetVersion) {
        String baseName = "tamework_pre_v" + targetVersion + "_" + SUFFIX_FORMAT.format(Instant.now());
        Path parent = sqlitePath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Path candidate = parent.resolve(baseName + ".sqlite.bak");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(baseName + "-" + suffix + ".sqlite.bak");
            suffix++;
        }
        return candidate;
    }
}
