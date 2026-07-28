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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            "provisioning_record",
            "projection_outbox",
            "projection_checkpoint",
            "feature_circuit",
            "coop_slot",
            "coop_residency",
            "refund_claim",
            "refund_claim_item",
            "import_manifest"
    );
    private static final Set<String> REQUIRED_INDEXES = Set.of(
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
    private static final Set<SchemaObject> REQUIRED_OBJECTS = requiredObjects();
    private static final Pattern SCHEMA_DEFINITION = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(UNIQUE)\\s+)?(TABLE|INDEX)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b"
    );

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;
    private final String script;
    private final String scriptHash;
    private final Map<SchemaObject, String> requiredDefinitions;

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
        this.requiredDefinitions = requiredDefinitions(script);
    }

    @Override
    public int targetVersion() {
        return VERSION;
    }

    @Override
    @Nonnull
    public PersistenceTransactionResult<PersistenceSchemaStatus> initialize() {
        boolean existingSchema = false;
        try (Connection probe = connections.openImmutableSchemaProbeConnection()) {
            if (probe != null) {
                SchemaInspection inspection = inspectSchema(probe);
                if (!inspection.objects().isEmpty() && !inspection.objects().equals(REQUIRED_OBJECTS)) {
                    return schemaRejected("replacement_schema_objects_present", "initialize_schema_v1");
                }
                if (!inspection.objects().isEmpty()
                        && !inspection.definitions().equals(requiredDefinitions)) {
                    return schemaRejected("replacement_schema_definition_mismatch", "initialize_schema_v1");
                }
                existingSchema = !inspection.objects().isEmpty();
            }
        } catch (Exception failure) {
            return new PersistenceTransactionResult.RolledBack<>(
                    SqliteFailureClassifier.classify(failure, "initialize_schema_v1")
            );
        }
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            try {
                if (existingSchema) {
                    verifyConnection(connection);
                } else {
                    createSchema(connection);
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
        SchemaInspection inspection = inspectSchema(connection);
        if (!inspection.objects().equals(REQUIRED_OBJECTS)) {
            throw new SchemaVerificationException("replacement_schema_object_mismatch");
        }
        if (!inspection.definitions().equals(requiredDefinitions)) {
            throw new SchemaVerificationException("replacement_schema_definition_mismatch");
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

    private SchemaInspection inspectSchema(Connection connection) throws Exception {
        java.util.HashSet<SchemaObject> objects = new java.util.HashSet<>();
        Map<SchemaObject, String> definitions = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT type, name, sql FROM sqlite_master
                     WHERE name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                SchemaObject object = new SchemaObject(rows.getString("type"), rows.getString("name"));
                objects.add(object);
                if ("table".equals(object.type()) || "index".equals(object.type())) {
                    String sql = rows.getString("sql");
                    if (sql != null) {
                        definitions.put(object, normalizeDefinition(sql));
                    }
                }
            }
        }
        return new SchemaInspection(Set.copyOf(objects), Map.copyOf(definitions));
    }

    private PersistenceTransactionResult<PersistenceSchemaStatus> schemaRejected(
            String code,
            String operation
    ) {
        return new PersistenceTransactionResult.RolledBack<>(
                schemaFailure(new SchemaVerificationException(code), operation)
        );
    }

    private static Set<SchemaObject> requiredObjects() {
        java.util.HashSet<SchemaObject> objects = new java.util.HashSet<>();
        for (String table : REQUIRED_TABLES) {
            objects.add(new SchemaObject("table", table));
        }
        for (String index : REQUIRED_INDEXES) {
            objects.add(new SchemaObject("index", index));
        }
        return Set.copyOf(objects);
    }

    private static Map<SchemaObject, String> requiredDefinitions(String script) {
        Map<SchemaObject, String> definitions = new HashMap<>();
        for (String statement : SqlScriptParser.statements(script)) {
            Matcher matcher = SCHEMA_DEFINITION.matcher(statement);
            if (!matcher.find()) {
                continue;
            }
            String type = matcher.group(2).toLowerCase(Locale.ROOT);
            String name = matcher.group(3);
            SchemaObject object = new SchemaObject(type, name);
            if (!REQUIRED_OBJECTS.contains(object)) {
                continue;
            }
            String previous = definitions.put(object, normalizeDefinition(statement));
            if (previous != null) {
                throw new IllegalStateException("Duplicate replacement schema definition: " + object.name());
            }
        }
        if (!definitions.keySet().equals(REQUIRED_OBJECTS)) {
            throw new IllegalStateException("Replacement schema definitions do not match required objects");
        }
        return Map.copyOf(definitions);
    }

    private static String normalizeDefinition(String sql) {
        StringBuilder normalized = new StringBuilder(sql.length());
        char closingQuote = 0;
        boolean pendingWhitespace = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (closingQuote != 0) {
                normalized.append(character);
                if (character == closingQuote) {
                    if (closingQuote != ']' && index + 1 < sql.length()
                            && sql.charAt(index + 1) == closingQuote) {
                        normalized.append(sql.charAt(++index));
                    } else {
                        closingQuote = 0;
                    }
                }
                continue;
            }
            if (Character.isWhitespace(character)) {
                pendingWhitespace = normalized.length() > 0;
                continue;
            }
            if (pendingWhitespace) {
                normalized.append(' ');
                pendingWhitespace = false;
            }
            normalized.append(Character.toLowerCase(character));
            closingQuote = switch (character) {
                case '\'', '"', '`' -> character;
                case '[' -> ']';
                default -> 0;
            };
        }
        return normalized.toString();
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

    private record SchemaObject(String type, String name) {
    }

    private record SchemaInspection(Set<SchemaObject> objects,
                                    Map<SchemaObject, String> definitions) {
    }
}
