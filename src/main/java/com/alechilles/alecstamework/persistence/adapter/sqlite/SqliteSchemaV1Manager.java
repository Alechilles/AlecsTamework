package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaManager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Creates and verifies only the fresh Tamework replacement schema lineage version 1. */
public final class SqliteSchemaV1Manager implements PersistenceSchemaManager {
    public static final int VERSION = 1;
    public static final String LINEAGE = "tamework-state";
    private static final String RESOURCE = "/persistence/schema/v1.sql";
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "schema_history",
            "companion_profile",
            "operation_envelope",
            "persistence_incident",
            "persistence_quarantine",
            "companion_alias",
            "companion_lifecycle",
            "companion_snapshot",
            "companion_tool_link",
            "profile_extension_data",
            "operation_participant",
            "owner_population_reservation",
            "population_evidence_batch",
            "population_evidence_observation",
            "population_group_classification",
            "population_group_membership",
            "population_group_reservation",
            "command_family",
            "command_roster_membership",
            "timed_summon_lease",
            "projection_outbox",
            "projection_checkpoint",
            "feature_circuit",
            "coop_slot",
            "coop_residency",
            "refund_claim",
            "refund_claim_item",
            "import_manifest"
    );

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;
    private final String script;
    private final String scriptHash;

    public SqliteSchemaV1Manager(@Nonnull SqliteConnectionFactory connections) {
        this(connections, System::currentTimeMillis);
    }

    public SqliteSchemaV1Manager(@Nonnull SqliteConnectionFactory connections,
                                 @Nonnull LongSupplier clock) {
        if (connections == null || clock == null) {
            throw new IllegalArgumentException("Schema connection factory and clock are required");
        }
        this.connections = connections;
        this.clock = clock;
        this.script = loadScript();
        this.scriptHash = sha256(script);
    }

    @Override
    public int targetVersion() {
        return VERSION;
    }

    @Override
    @Nonnull
    public PersistenceTransactionResult<PersistenceSchemaStatus> initialize() {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            try {
                Set<String> tables = userTables(connection);
                if (tables.isEmpty()) {
                    createSchema(connection);
                } else {
                    verifyConnection(connection);
                }
            } catch (Exception failure) {
                return rollback(connection, failure);
            }
            return commit(connection);
        } catch (Exception failure) {
            return new PersistenceTransactionResult.RolledBack<>(
                    SqliteFailureClassifier.classify(failure, "initialize_schema_v1")
            );
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    @Nonnull
    public PersistenceReadResult<PersistenceSchemaStatus> verify() {
        try (Connection connection = connections.openReadConnection()) {
            verifyConnection(connection);
            return PersistenceReadResult.found(
                    new PersistenceSchemaStatus(VERSION, true),
                    VERSION
            );
        } catch (Exception failure) {
            return PersistenceReadResult.failed(schemaFailure(failure, "verify_schema_v1"));
        }
    }

    /** Returns the immutable bundled schema hash stored in every replacement target. */
    @Nonnull
    public String schemaHash() {
        return scriptHash;
    }

    /** Returns the exact required user-table set for diagnostics and architecture tests. */
    @Nonnull
    public static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    private void createSchema(Connection connection) throws Exception {
        for (String sql : SqlScriptParser.statements(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schema_history(version, lineage, applied_at_ms, schema_hash)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, VERSION);
            statement.setString(2, LINEAGE);
            statement.setLong(3, clock.getAsLong());
            statement.setString(4, scriptHash);
            statement.executeUpdate();
        }
        verifyConnection(connection);
    }

    private void verifyConnection(Connection connection) throws Exception {
        Set<String> tables = userTables(connection);
        if (!tables.equals(REQUIRED_TABLES)) {
            throw new SchemaVerificationException("replacement_schema_table_mismatch");
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT version, lineage, schema_hash FROM schema_history"
             )) {
            if (!row.next()
                    || row.getInt("version") != VERSION
                    || !LINEAGE.equals(row.getString("lineage"))
                    || !scriptHash.equals(row.getString("schema_hash"))
                    || row.next()) {
                throw new SchemaVerificationException("replacement_schema_history_mismatch");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("PRAGMA integrity_check")) {
            if (!row.next() || !"ok".equalsIgnoreCase(row.getString(1)) || row.next()) {
                throw new SchemaVerificationException("replacement_integrity_check_failed");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (violations.next()) {
                throw new SchemaVerificationException("replacement_foreign_key_check_failed");
            }
        }
    }

    private Set<String> userTables(Connection connection) throws Exception {
        java.util.HashSet<String> tables = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return Set.copyOf(tables);
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> rollback(
            Connection connection,
            Exception failure
    ) {
        try {
            connection.rollback();
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(failure, "initialize_schema_v1")
            );
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return new PersistenceTransactionResult.Unknown<>(
                    new StorageFailure(
                            StorageFailureKind.UNKNOWN,
                            "schema_initialization_outcome_unknown",
                            "initialize_schema_v1",
                            false,
                            failure
                    )
            );
        }
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> commit(Connection connection) {
        try {
            connection.commit();
            return new PersistenceTransactionResult.Committed<>(
                    new PersistenceSchemaStatus(VERSION, true)
            );
        } catch (SQLException failure) {
            return new PersistenceTransactionResult.Unknown<>(
                    new StorageFailure(
                            StorageFailureKind.UNKNOWN,
                            "schema_initialization_commit_unknown",
                            "initialize_schema_v1",
                            false,
                            failure
                    )
            );
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing a session cannot change an already-known transaction outcome.
        }
    }

    private StorageFailure schemaFailure(Throwable failure, String operation) {
        if (failure instanceof SchemaVerificationException verification) {
            return new StorageFailure(
                    StorageFailureKind.SCHEMA,
                    verification.getMessage(),
                    operation,
                    false,
                    failure
            );
        }
        return SqliteFailureClassifier.classify(failure, operation);
    }

    private String loadScript() {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing replacement schema resource: " + RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to load replacement schema v1", failure);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static final class SchemaVerificationException extends Exception {
        private SchemaVerificationException(String code) {
            super(code);
        }
    }
}
