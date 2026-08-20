package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaManager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Creates, upgrades, and verifies the routed-read replacement schema version 2. */
public final class SqliteSchemaV2Manager implements PersistenceSchemaManager {
    public static final int VERSION = 2;
    public static final String LINEAGE = SqliteSchemaV1Manager.LINEAGE;
    public static final String ROUTED_READ_INDEX =
            "idx_projection_outbox_type_sequence";
    private static final String RESOURCE = "/persistence/schema/v2.sql";
    private static final SqliteSchemaInspector.SchemaObject ROUTED_INDEX_OBJECT =
            new SqliteSchemaInspector.SchemaObject("index", ROUTED_READ_INDEX);
    private static final String V1_SCRIPT = SqliteSchemaV1Manager.bundledScript();
    private static final String V1_HASH = SqliteSchemaV1Manager.bundledHash();
    private static final byte[] MIGRATION_BYTES = loadMigrationBytes();
    private static final String MIGRATION_SCRIPT = new String(
            MIGRATION_BYTES, StandardCharsets.UTF_8
    );
    private static final Set<SqliteSchemaInspector.SchemaObject> V1_OBJECTS =
            SqliteSchemaV1Manager.requiredSchemaObjects();
    private static final Set<SqliteSchemaInspector.SchemaObject> V2_OBJECTS =
            addObject(V1_OBJECTS, ROUTED_INDEX_OBJECT);
    private static final Map<SqliteSchemaInspector.SchemaObject, String> V1_DEFINITIONS =
            SqliteSchemaV1Manager.requiredSchemaDefinitions();
    private static final SqliteSchemaInspector.SchemaObject HISTORY_OBJECT =
            new SqliteSchemaInspector.SchemaObject("table", "schema_history");
    private static final Map<SqliteSchemaInspector.SchemaObject, String> V2_DEFINITIONS =
            combinedDefinitions();
    private static final String SCHEMA_HASH = SqliteSchemaInspector.sha256(
            hashInput()
    );

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;

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
    }

    @Override
    public int targetVersion() {
        return VERSION;
    }

    @Override
    @Nonnull
    public PersistenceTransactionResult<PersistenceSchemaStatus> initialize() {
        ExistingSchema existing;
        try (Connection probe = connections.openImmutableSchemaProbeConnection()) {
            if (probe == null) {
                existing = ExistingSchema.EMPTY;
            } else {
                existing = classify(SqliteSchemaInspector.inspect(probe));
                if (existing == ExistingSchema.REJECTED) {
                    return schemaRejected(
                            "replacement_schema_objects_present",
                            "initialize_schema_v2"
                    );
                }
            }
        } catch (Exception failure) {
            return rolledBack(failure, "initialize_schema_v2");
        }

        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            try {
                switch (existing) {
                    case EMPTY -> createSchema(connection);
                    case V1 -> upgradeV1(connection);
                    case V2 -> verifyConnection(connection);
                    case REJECTED -> throw new SchemaVerificationException(
                            "replacement_schema_objects_present"
                    );
                }
            } catch (Exception failure) {
                return rollback(connection, failure);
            }
            return commit(connection);
        } catch (Exception failure) {
            return rolledBack(failure, "initialize_schema_v2");
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
                    new PersistenceSchemaStatus(VERSION, true), VERSION
            );
        } catch (Exception failure) {
            return PersistenceReadResult.failed(
                    schemaFailure(failure, "verify_schema_v2")
            );
        }
    }

    /** Returns the immutable version-2 hash derived from version 1 and the migration bytes. */
    @Nonnull
    public String schemaHash() {
        return SCHEMA_HASH;
    }

    /** Returns the exact required user-table set shared by both replacement versions. */
    @Nonnull
    public static Set<String> requiredTables() {
        return SqliteSchemaV1Manager.requiredTables();
    }

    static Set<SqliteSchemaInspector.SchemaObject> requiredSchemaObjects() {
        return V2_OBJECTS;
    }

    static Map<SqliteSchemaInspector.SchemaObject, String> requiredSchemaDefinitions() {
        return V2_DEFINITIONS;
    }

    static String bundledHash() {
        return SCHEMA_HASH;
    }

    static String migrationScript() {
        return MIGRATION_SCRIPT;
    }

    private void createSchema(Connection connection) throws Exception {
        executeScript(connection, V1_SCRIPT);
        executeScript(connection, MIGRATION_SCRIPT);
        insertV1History(connection, clock.getAsLong());
        migrateHistoryTable(connection);
        verifyConnection(connection);
    }

    private void upgradeV1(Connection connection) throws Exception {
        verifyV1Connection(connection);
        executeScript(connection, MIGRATION_SCRIPT);
        migrateHistoryTable(connection);
        verifyConnection(connection);
    }

    private void executeScript(Connection connection, String script)
            throws Exception {
        for (String sql : SqlScriptParser.statements(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private void insertV1History(Connection connection, long appliedAtMs)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schema_history(
                    version, lineage, applied_at_ms, schema_hash
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, SqliteSchemaV1Manager.VERSION);
            statement.setString(2, LINEAGE);
            statement.setLong(3, appliedAtMs);
            statement.setString(4, V1_HASH);
            statement.executeUpdate();
        }
    }

    private void migrateHistoryTable(Connection connection) throws Exception {
        final String oldTable = "schema_history_v1_migration";
        executeScript(connection, "ALTER TABLE schema_history RENAME TO " + oldTable);
        executeScript(connection, """
                CREATE TABLE schema_history (
                    version INTEGER PRIMARY KEY CHECK (version IN (1, 2)),
                    lineage TEXT NOT NULL CHECK (lineage = 'tamework-state'),
                    applied_at_ms INTEGER NOT NULL,
                    schema_hash TEXT NOT NULL CHECK (length(schema_hash) = 64)
                )
                """);
        long appliedAtMs;
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT applied_at_ms FROM " + oldTable
             )) {
            if (!row.next()) {
                throw new SchemaVerificationException(
                        "replacement_schema_history_mismatch"
                );
            }
            appliedAtMs = row.getLong(1);
            if (row.next()) {
                throw new SchemaVerificationException(
                        "replacement_schema_history_mismatch"
                );
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schema_history(
                    version, lineage, applied_at_ms, schema_hash
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, VERSION);
            statement.setString(2, LINEAGE);
            statement.setLong(3, appliedAtMs);
            statement.setString(4, SCHEMA_HASH);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + oldTable);
        }
    }

    private void verifyV1Connection(Connection connection) throws Exception {
        verifyObjects(connection, V1_OBJECTS, V1_DEFINITIONS);
        verifyHistory(connection, SqliteSchemaV1Manager.VERSION, V1_HASH);
        verifyIntegrity(connection);
    }

    private void verifyConnection(Connection connection) throws Exception {
        verifyObjects(connection, V2_OBJECTS, V2_DEFINITIONS);
        verifyHistory(connection, VERSION, SCHEMA_HASH);
        verifyIntegrity(connection);
    }

    private void verifyObjects(
            Connection connection,
            Set<SqliteSchemaInspector.SchemaObject> expectedObjects,
            Map<SqliteSchemaInspector.SchemaObject, String> expectedDefinitions
    ) throws Exception {
        SqliteSchemaInspector.SchemaInspection inspection =
                SqliteSchemaInspector.inspect(connection);
        if (!inspection.objects().equals(expectedObjects)) {
            throw new SchemaVerificationException(
                    "replacement_schema_object_mismatch"
            );
        }
        if (!inspection.definitions().equals(expectedDefinitions)) {
            throw new SchemaVerificationException(
                    "replacement_schema_definition_mismatch"
            );
        }
    }

    private void verifyHistory(Connection connection, int version, String hash)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT version, lineage, schema_hash FROM schema_history"
             )) {
            if (!row.next()
                    || row.getInt("version") != version
                    || !LINEAGE.equals(row.getString("lineage"))
                    || !hash.equals(row.getString("schema_hash"))
                    || row.next()) {
                throw new SchemaVerificationException(
                        "replacement_schema_history_mismatch"
                );
            }
        }
    }

    private void verifyIntegrity(Connection connection) throws Exception {
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

    private ExistingSchema classify(
            SqliteSchemaInspector.SchemaInspection inspection
    ) {
        if (inspection.objects().isEmpty()) {
            return ExistingSchema.EMPTY;
        }
        if (inspection.objects().equals(V1_OBJECTS)
                && inspection.definitions().equals(V1_DEFINITIONS)) {
            return ExistingSchema.V1;
        }
        if (inspection.objects().equals(V2_OBJECTS)
                && inspection.definitions().equals(V2_DEFINITIONS)) {
            return ExistingSchema.V2;
        }
        return ExistingSchema.REJECTED;
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> schemaRejected(
            String code,
            String operation
    ) {
        return new PersistenceTransactionResult.RolledBack<>(
                schemaFailure(new SchemaVerificationException(code), operation)
        );
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> rollback(
            Connection connection,
            Exception failure
    ) {
        try {
            connection.rollback();
            return new PersistenceTransactionResult.RolledBack<>(
                    schemaFailure(failure, "initialize_schema_v2")
            );
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return new PersistenceTransactionResult.Unknown<>(
                    new StorageFailure(
                            StorageFailureKind.UNKNOWN,
                            "schema_initialization_outcome_unknown",
                            "initialize_schema_v2",
                            false,
                            failure
                    )
            );
        }
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> commit(
            Connection connection
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
                            "initialize_schema_v2",
                            false,
                            failure
                    )
            );
        }
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> rolledBack(
            Throwable failure,
            String operation
    ) {
        return new PersistenceTransactionResult.RolledBack<>(
                schemaFailure(failure, operation)
        );
    }

    private StorageFailure schemaFailure(Throwable failure, String operation) {
        if (failure instanceof SchemaVerificationException verification) {
            return new StorageFailure(
                    StorageFailureKind.SCHEMA,
                    verification.getMessage(), operation, false, failure
            );
        }
        return SqliteFailureClassifier.classify(failure, operation);
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

    private static Set<SqliteSchemaInspector.SchemaObject> addObject(
            Set<SqliteSchemaInspector.SchemaObject> objects,
            SqliteSchemaInspector.SchemaObject object
    ) {
        HashSet<SqliteSchemaInspector.SchemaObject> copy = new HashSet<>(objects);
        copy.add(object);
        return Set.copyOf(copy);
    }

    private static Map<SqliteSchemaInspector.SchemaObject, String>
    combinedDefinitions() {
        Map<SqliteSchemaInspector.SchemaObject, String> definitions =
                new HashMap<>(V1_DEFINITIONS);
        definitions.put(
                HISTORY_OBJECT,
                V1_DEFINITIONS.get(HISTORY_OBJECT)
                        .replace("check (version = 1)",
                                "check (version in (1, 2))")
        );
        definitions.putAll(SqliteSchemaInspector.definitionsForScript(
                MIGRATION_SCRIPT, Set.of(ROUTED_INDEX_OBJECT)
        ));
        return Map.copyOf(definitions);
    }

    private static byte[] hashInput() {
        byte[] prefix = (V1_HASH + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[prefix.length + MIGRATION_BYTES.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(MIGRATION_BYTES, 0, input, prefix.length,
                MIGRATION_BYTES.length);
        return input;
    }

    private static byte[] loadMigrationBytes() {
        try {
            return SqliteSchemaInspector.resourceBytes(
                    SqliteSchemaV2Manager.class, RESOURCE
            );
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to load replacement schema v2", failure
            );
        }
    }

    private enum ExistingSchema {
        EMPTY,
        V1,
        V2,
        REJECTED
    }

    private static final class SchemaVerificationException extends Exception {
        private SchemaVerificationException(String code) {
            super(code);
        }
    }
}
