package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Creates a transactionally consistent SQLite snapshot before a schema upgrade.
 */
public final class SqliteMigrationBackupService {
    private static final DateTimeFormatter SUFFIX_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

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
            int sourceVersion = readCurrentSchemaVersion(connection);
            Path backupPath = uniqueBackupPath(sqlitePath, targetVersion);
            String escapedPath = backupPath.toAbsolutePath().normalize().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }
            try {
                verifySnapshot(backupPath);
                writeManifest(sqlitePath, backupPath, sourceVersion, targetVersion);
            } catch (Exception invalidSnapshot) {
                Files.deleteIfExists(manifestPath(backupPath));
                Files.deleteIfExists(backupPath);
                throw invalidSnapshot;
            }
            return Optional.of(backupPath);
        }
    }

    private void verifySnapshot(Path backupPath) throws Exception {
        if (!Files.exists(backupPath) || Files.size(backupPath) == 0L) {
            throw new IllegalStateException("SQLite migration snapshot was not created: " + backupPath);
        }
        try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath.toAbsolutePath());
             Statement statement = backup.createStatement();
             ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
            if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                throw new IllegalStateException("SQLite migration snapshot failed integrity verification.");
            }
        }
    }

    private void writeManifest(Path sqlitePath,
                               Path backupPath,
                               int sourceVersion,
                               int targetVersion) throws Exception {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", 1);
        manifest.addProperty("createdAt", Instant.now().toString());
        manifest.addProperty("sourceFile", sqlitePath.getFileName().toString());
        manifest.addProperty("snapshotFile", backupPath.getFileName().toString());
        manifest.addProperty("sourceSchemaVersion", sourceVersion);
        manifest.addProperty("targetSchemaVersion", targetVersion);
        manifest.addProperty("snapshotSizeBytes", Files.size(backupPath));
        manifest.addProperty("snapshotSha256", sha256(backupPath));
        manifest.addProperty("scope", "tamework_sqlite_only");
        manifest.addProperty("hytaleSaveBackupOwnedBy", "hytale_server_operator");

        Path manifestPath = manifestPath(backupPath);
        Path temporary = manifestPath.resolveSibling(manifestPath.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(manifest) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        installManifest(temporary, manifestPath);
    }

    private int readCurrentSchemaVersion(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_migrations'")) {
            if (!tables.next()) return 0;
        } catch (Exception unavailable) {
            return 0;
        }
        try (Statement statement = connection.createStatement();
             ResultSet versions = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return versions.next() ? versions.getInt(1) : 0;
        } catch (Exception unavailable) {
            return 0;
        }
    }

    @Nonnull
    static Path manifestPath(@Nonnull Path backupPath) {
        return backupPath.resolveSibling(backupPath.getFileName() + ".manifest.json");
    }

    @Nonnull
    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void installManifest(Path temporary, Path destination) throws Exception {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination);
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
