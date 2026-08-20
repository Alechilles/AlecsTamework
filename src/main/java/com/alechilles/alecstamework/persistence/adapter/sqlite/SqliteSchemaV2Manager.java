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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Creates, upgrades, and verifies the Tamework replacement schema lineage version 2. */
public final class SqliteSchemaV2Manager implements PersistenceSchemaManager {
    public static final int VERSION = 2;
    public static final String LINEAGE = "tamework-state";
    private static final String RESOURCE = "/persistence/schema/v2.sql";
    private static final Set<String> V1_TABLES = SqliteSchemaV1Manager.requiredTables();
    private static final Set<String> NEW_TABLES = Set.of(
            "population_domain_reservation",
            "companion_output_claim",
            "companion_output_claim_item"
    );
    private static final Set<String> REQUIRED_TABLES = union(V1_TABLES, NEW_TABLES);
    private static final Set<String> V1_INDEXES = Set.of(
            "uq_companion_alias_current_profile",
            "idx_companion_alias_profile_generation",
            "idx_companion_lifecycle_owner_state",
            "idx_companion_lifecycle_location",
            "idx_companion_lifecycle_active_operation",
            "idx_owner_population_reservation_scope",
            "idx_population_evidence_profile",
            "idx_population_group_membership_group",
            "idx_population_group_reservation_scope",
            "idx_command_roster_family",
            "uq_companion_snapshot_current_kind",
            "idx_companion_snapshot_profile_created",
            "idx_companion_tool_link_tool",
            "idx_profile_extension_namespace",
            "idx_operation_participant_scope",
            "idx_projection_outbox_aggregate"
    );
    private static final Set<String> REQUIRED_INDEXES = union(
            V1_INDEXES,
            Set.of(
                    "idx_population_domain_reservation_scope",
                    "idx_companion_output_claim_owner"
            )
    );
    private static final Set<SqliteSchemaDefinitionCatalog.SchemaObject> V1_OBJECTS =
            SqliteSchemaDefinitionCatalog.requiredObjects(
            V1_TABLES, V1_INDEXES
    );
    private static final Set<SqliteSchemaDefinitionCatalog.SchemaObject> REQUIRED_OBJECTS =
            SqliteSchemaDefinitionCatalog.requiredObjects(
            REQUIRED_TABLES, REQUIRED_INDEXES
    );

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;
    private final String script;
    private final String scriptHash;
    private final String v1ScriptHash;
    private final Map<SqliteSchemaDefinitionCatalog.SchemaObject, String>
            requiredDefinitions;

    public SqliteSchemaV2Manager(@Nonnull SqliteConnectionFactory connections) {
        this(connections, System::currentTimeMillis);
    }

    public SqliteSchemaV2Manager(@Nonnull SqliteConnectionFactory connections,
                                 @Nonnull LongSupplier clock) {
        if (connections == null || clock == null) {
            throw new IllegalArgumentException(
                    "Schema connection factory and clock are required"
            );
        }
        this.connections = connections;
        this.clock = clock;
        this.script = loadScript();
        this.scriptHash = sha256(script);
        this.v1ScriptHash = new SqliteSchemaV1Manager(connections).schemaHash();
        this.requiredDefinitions = SqliteSchemaDefinitionCatalog
                .requiredDefinitions(script, REQUIRED_OBJECTS);
    }

    @Override
    public int targetVersion() {
        return VERSION;
    }

    @Override
    @Nonnull
    public PersistenceTransactionResult<PersistenceSchemaStatus> initialize() {
        SqliteSchemaDefinitionCatalog.Inspection inspection;
        try (Connection probe = connections.openImmutableSchemaProbeConnection()) {
            if (probe == null) {
                inspection = new SqliteSchemaDefinitionCatalog.Inspection(
                        Set.of(), Map.of()
                );
            } else {
                inspection = inspectSchema(probe);
            }
        } catch (Exception failure) {
            return new PersistenceTransactionResult.RolledBack<>(
                    SqliteFailureClassifier.classify(
                            failure, "initialize_schema_v2"
                    )
            );
        }

        if (inspection.objects().isEmpty()) {
            return initializeFresh();
        }
        if (inspection.objects().equals(REQUIRED_OBJECTS)) {
            return initializeExisting();
        }
        if (inspection.objects().equals(V1_OBJECTS)
                && validV1Schema()) {
            return upgradeV1();
        }
        return schemaRejected(
                "replacement_schema_objects_present",
                "initialize_schema_v2"
        );
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
            return PersistenceReadResult.failed(
                    schemaFailure(failure, "verify_schema_v2")
            );
        }
    }

    /** Returns the immutable bundled v2 schema hash stored in new targets. */
    @Nonnull
    public String schemaHash() {
        return scriptHash;
    }

    /** Returns the exact managed table set for diagnostics and tests. */
    @Nonnull
    public static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    /** Returns the exact named managed index set for diagnostics and tests. */
    @Nonnull
    public static Set<String> requiredIndexes() {
        return REQUIRED_INDEXES;
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus>
    initializeFresh() {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            try {
                createSchema(connection);
            } catch (Exception failure) {
                return rollback(connection, failure, "initialize_schema_v2");
            }
            return commit(connection, "initialize_schema_v2");
        } catch (Exception failure) {
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(failure, "initialize_schema_v2")
            );
        } finally {
            closeQuietly(connection);
        }
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus>
    initializeExisting() {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            try {
                verifyConnection(connection);
            } catch (Exception failure) {
                return rollback(connection, failure, "initialize_schema_v2");
            }
            return commit(connection, "initialize_schema_v2");
        } catch (Exception failure) {
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(failure, "initialize_schema_v2")
            );
        } finally {
            closeQuietly(connection);
        }
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> upgradeV1() {
        try {
            new SqliteSchemaV2Migration(
                    connections, clock, scriptHash
            ).migrate();
            PersistenceReadResult<PersistenceSchemaStatus> verified = verify();
            if (verified instanceof PersistenceReadResult.Found<PersistenceSchemaStatus> found) {
                return new PersistenceTransactionResult.Committed<>(found.value());
            }
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(
                            new SchemaVerificationException(
                                    "replacement_schema_upgrade_verification_failed"
                            ),
                            "upgrade_schema_v1_to_v2"
                    )
            );
        } catch (Exception failure) {
            PersistenceReadResult<PersistenceSchemaStatus> afterFailure = verify();
            if (afterFailure instanceof PersistenceReadResult.Found<PersistenceSchemaStatus> found) {
                return new PersistenceTransactionResult.Committed<>(found.value());
            }
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(failure, "upgrade_schema_v1_to_v2")
            );
        }
    }

    private boolean validV1Schema() {
        return new SqliteSchemaV1Manager(connections, clock).verify()
                instanceof PersistenceReadResult.Found<?>;
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
        SqliteSchemaDefinitionCatalog.Inspection inspection =
                inspectSchema(connection);
        if (!inspection.objects().equals(REQUIRED_OBJECTS)) {
            throw new SchemaVerificationException(
                    "replacement_schema_object_mismatch"
            );
        }
        if (!inspection.definitions().equals(requiredDefinitions)) {
            throw new SchemaVerificationException(
                    "replacement_schema_definition_mismatch"
            );
        }
        verifyHistory(connection);
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("PRAGMA integrity_check")) {
            if (!row.next()
                    || !"ok".equalsIgnoreCase(row.getString(1))
                    || row.next()) {
                throw new SchemaVerificationException(
                        "replacement_integrity_check_failed"
                );
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet violations = statement.executeQuery(
                     "PRAGMA foreign_key_check"
             )) {
            if (violations.next()) {
                throw new SchemaVerificationException(
                        "replacement_foreign_key_check_failed"
                );
            }
        }
    }

    private void verifyHistory(Connection connection) throws Exception {
        int count = 0;
        int previous = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, applied_at_ms, schema_hash
                     FROM schema_history
                     ORDER BY rowid
                     """)) {
            while (rows.next()) {
                count++;
                int version = rows.getInt("version");
                String lineage = rows.getString("lineage");
                String hash = rows.getString("schema_hash");
                rows.getLong("applied_at_ms");
                boolean appliedAtNull = rows.wasNull();
                if (!LINEAGE.equals(lineage)
                        || appliedAtNull) {
                    throw new SchemaVerificationException(
                            "replacement_schema_history_mismatch"
                    );
                }
                if (version == 1) {
                    if (count != 1 || !v1ScriptHash.equals(hash)) {
                        throw new SchemaVerificationException(
                                "replacement_schema_history_mismatch"
                        );
                    }
                } else if (version == VERSION) {
                    if (count > 2 || !scriptHash.equals(hash)
                            || (count == 2 && previous != 1)) {
                        throw new SchemaVerificationException(
                                "replacement_schema_history_mismatch"
                        );
                    }
                } else {
                    throw new SchemaVerificationException(
                            "replacement_schema_history_mismatch"
                    );
                }
                previous = version;
            }
        }
        if (count == 0 || count > 2 || previous != VERSION) {
            throw new SchemaVerificationException(
                    "replacement_schema_history_mismatch"
            );
        }
    }

    private SqliteSchemaDefinitionCatalog.Inspection inspectSchema(
            Connection connection
    ) throws Exception {
        return SqliteSchemaDefinitionCatalog.inspect(connection);
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus>
    schemaRejected(String code, String operation) {
        return new PersistenceTransactionResult.RolledBack<>(
                schemaFailure(new SchemaVerificationException(code), operation)
        );
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> rollback(
            Connection connection, Exception failure, String operation
    ) {
        try {
            connection.rollback();
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(failure, operation)
            );
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return new PersistenceTransactionResult.Unknown<>(
                    new StorageFailure(
                            StorageFailureKind.UNKNOWN,
                            "schema_initialization_outcome_unknown",
                            operation,
                            false,
                            failure
                    )
            );
        }
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> commit(
            Connection connection, String operation
    ) {
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
                            operation,
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
                throw new IllegalStateException(
                        "Missing replacement schema resource: " + RESOURCE
                );
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to load replacement schema v2", failure
            );
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

    private static Set<String> union(Set<String> first, Set<String> second) {
        HashSet<String> result = new HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static final class SchemaVerificationException extends Exception {
        private SchemaVerificationException(String code) {
            super(code);
        }
    }

}
