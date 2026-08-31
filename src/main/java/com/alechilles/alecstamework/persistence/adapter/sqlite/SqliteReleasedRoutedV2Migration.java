package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Upgrades the exact routed-read v2 schema shipped before Tamework 3.3.0. */
public final class SqliteReleasedRoutedV2Migration {
    private static final String RESOURCE =
            "/persistence/migration/released-routed-v2-to-v2.sql";
    private static final String APPLIED_AT_TOKEN = "__APPLIED_AT_MS__";
    private static final String HASH_TOKEN = "__V2_SCHEMA_HASH__";

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;
    private final String schemaHash;
    private final String script;

    public SqliteReleasedRoutedV2Migration(
            SqliteConnectionFactory connections,
            LongSupplier clock,
            String schemaHash
    ) {
        if (connections == null || clock == null
                || schemaHash == null || schemaHash.length() != 64) {
            throw new IllegalArgumentException(
                    "Schema migration dependencies are required"
            );
        }
        this.connections = connections;
        this.clock = clock;
        this.schemaHash = schemaHash;
        this.script = loadScript();
    }

    /** Creates a verified sibling backup and applies the migration. */
    public Path migrate() throws Exception {
        Path backup = createBackup();
        Connection connection = null;
        boolean committed = false;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            String expanded = script
                    .replace(APPLIED_AT_TOKEN,
                            Long.toString(clock.getAsLong()))
                    .replace(HASH_TOKEN, schemaHash);
            for (String sql : SqlScriptParser.statements(expanded)) {
                if (!sql.isBlank()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }
            SqliteSchemaV2ReadOnlyGateway.verify(connection);
            SqliteReleasedRoutedV2Gateway.verifyIntegrity(connection);
            try {
                connection.commit();
            } catch (SQLException failure) {
                throw new SqliteSchemaUpgradeCoordinator.OutcomeUnknownException(
                        failure
                );
            }
            committed = true;
            return backup;
        } catch (Exception failure) {
            if (connection != null && !committed) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    throw new SqliteSchemaUpgradeCoordinator
                            .OutcomeUnknownException(failure);
                }
            }
            throw failure;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // The transaction outcome is already known.
                }
            }
        }
    }

    private Path createBackup() throws Exception {
        Path source = connections.databasePath();
        Path parent = source.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Schema migration requires a parent directory"
            );
        }
        Files.createDirectories(parent);
        Path backup = source.resolveSibling(
                source.getFileName() + ".released-v2-backup."
                        + UUID.randomUUID() + ".sqlite"
        );
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + sqlLiteral(backup) + "'");
        }
        try (Connection connection = new SqliteConnectionFactory(backup)
                .openReadConnection()) {
            SqliteReleasedRoutedV2Gateway.verify(connection);
            return backup;
        } catch (Exception failure) {
            try {
                Files.deleteIfExists(backup);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static String sqlLiteral(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace("'", "''");
    }

    private static String loadScript() {
        try (InputStream stream = SqliteReleasedRoutedV2Migration.class
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing schema migration resource: " + RESOURCE
                );
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to load schema migration resource", failure
            );
        }
    }
}
