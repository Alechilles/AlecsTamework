package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Creates and verifies only the standalone bonded-companion schema lineage v1. */
public final class BondedCompanionSchemaManager {
    public static final int VERSION = 1;
    public static final String LINEAGE = "bonded-companions";
    private static final String RESOURCE = "/persistence/bonded/v1.sql";
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "bonded_schema_history",
            "bonded_companion_profile",
            "bonded_companion_lease",
            "bonded_companion_extension_data",
            "bonded_companion_cleanup",
            "bonded_companion_operation"
    );

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;
    private final String script;
    private final String scriptHash;

    BondedCompanionSchemaManager(
            @Nonnull SqliteConnectionFactory connections
    ) {
        this(connections, System::currentTimeMillis);
    }

    BondedCompanionSchemaManager(
            @Nonnull SqliteConnectionFactory connections,
            @Nonnull LongSupplier clock
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        script = loadScript();
        scriptHash = sha256(script);
    }

    /** Creates a manager that owns connections to the supplied database path. */
    public BondedCompanionSchemaManager(@Nonnull Path databasePath) {
        this(databasePath, System::currentTimeMillis);
    }

    /** Creates a path-owned manager with an injectable signed timestamp source. */
    public BondedCompanionSchemaManager(
            @Nonnull Path databasePath,
            @Nonnull LongSupplier clock
    ) {
        this(new SqliteConnectionFactory(
                Objects.requireNonNull(databasePath, "databasePath")), clock);
    }

    /** Initializes an empty bonded database or verifies the existing exact lineage. */
    @Nonnull
    public BondedCompanionPersistenceReadiness initialize() {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            try {
                if (tables(connection).isEmpty()) {
                    create(connection);
                } else {
                    verify(connection);
                }
                connection.commit();
                return BondedCompanionPersistenceReadiness.ready();
            } catch (Exception failure) {
                connection.rollback();
                return failure(failure);
            }
        } catch (Exception failure) {
            return failure(failure);
        }
    }

    /** Verifies schema history, table isolation, integrity, and lease/profile consistency. */
    @Nonnull
    public BondedCompanionPersistenceReadiness verify() {
        try (Connection connection = connections.openReadConnection()) {
            verify(connection);
            return BondedCompanionPersistenceReadiness.ready();
        } catch (Exception failure) {
            return failure(failure);
        }
    }

    /** Returns the SHA-256 of the exact bundled bonded v1 schema. */
    @Nonnull
    public String schemaHash() {
        return scriptHash;
    }

    /** Returns the exact bonded-only user-table set. */
    @Nonnull
    public static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    private void create(Connection connection) throws Exception {
        for (String sql : script.split(";\\s*(?:\\R|\\z)")) {
            if (!sql.isBlank()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_schema_history(
                    version, lineage, applied_at_ms, schema_hash
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, VERSION);
            statement.setString(2, LINEAGE);
            statement.setLong(3, clock.getAsLong());
            statement.setString(4, scriptHash);
            statement.executeUpdate();
        }
        verify(connection);
    }

    private void verify(Connection connection) throws Exception {
        if (!tables(connection).equals(REQUIRED_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM bonded_schema_history
                     """)) {
            if (!row.next()
                    || row.getInt("version") != VERSION
                    || !LINEAGE.equals(row.getString("lineage"))
                    || !scriptHash.equals(row.getString("schema_hash"))
                    || row.next()) {
                throw new VerificationFailure(
                        "bonded-schema-history-mismatch"
                );
            }
        }
        verifyStoredRows(connection);
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) {
                throw new VerificationFailure(
                        "bonded-foreign-key-check-failed"
                );
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT p.profile_id
                     FROM bonded_companion_profile p
                     LEFT JOIN bonded_companion_lease l
                       ON l.profile_id = p.profile_id
                     WHERE (p.state = 'ACTIVE' AND l.profile_id IS NULL)
                        OR (p.state <> 'ACTIVE' AND l.profile_id IS NOT NULL)
                     LIMIT 1
                     """)) {
            if (rows.next()) {
                throw new VerificationFailure(
                        "bonded-orphaned-active-lease"
                );
            }
        }
    }

    private void verifyStoredRows(Connection connection) throws Exception {
        verifyProfiles(connection);
        verifyLeases(connection);
        verifyCleanup(connection);
        verifyOperations(connection);
    }

    private void verifyProfiles(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT owner_uuid, state, snapshot_json, policy_json
                     FROM bonded_companion_profile
                     """)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                com.alechilles.alecstamework.companion.bonded.BondedCompanionState
                        .valueOf(rows.getString(2));
                var snapshot = JsonParser.parseString(rows.getString(3));
                if (!snapshot.isJsonObject()) throw invalidRecord();
                var object = snapshot.getAsJsonObject();
                if (!object.has("encoding")
                        || !"base64".equals(object.get("encoding").getAsString())
                        || !object.has("payload")) throw invalidRecord();
                if (Base64.getDecoder().decode(object.get("payload").getAsString()).length == 0)
                    throw invalidRecord();
                if (!JsonParser.parseString(rows.getString(4)).isJsonObject())
                    throw invalidRecord();
            }
        } catch (VerificationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalidRecord();
        }
    }

    private void verifyLeases(Connection connection) throws Exception {
        verifyUuidAndVocabulary(connection, """
                SELECT live_npc_uuid, projection_state FROM bonded_companion_lease
                """, Set.of("PENDING", "LIVE", "REMOVE_PENDING"));
    }

    private void verifyCleanup(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT owner_uuid, target_npc_uuid, target_kind,
                            cleanup_state, retained_until_ms
                     FROM bonded_companion_cleanup
                     """)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                requireUuid(rows.getString(2));
                if (!Set.of("SOURCE", "PROJECTION").contains(rows.getString(3))
                        || !Set.of("PENDING", "COMPLETED", "ABANDONED").contains(rows.getString(4))
                        || rows.getLong(5) == 0) throw invalidRecord();
            }
        } catch (VerificationFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw invalidRecord(); }
    }

    private void verifyOperations(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT owner_uuid, operation_type, operation_state,
                            request_hash, expires_at_ms
                     FROM bonded_companion_operation
                     """)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                if (!Set.of("CAPTURE", "PROVISION", "SUMMON", "STORE", "REVIVE", "CLEANUP")
                        .contains(rows.getString(2))
                        || !Set.of("PENDING", "SUCCEEDED", "REJECTED", "FAILED")
                        .contains(rows.getString(3))
                        || !rows.getString(4).matches("[0-9a-f]{64}")
                        || rows.getLong(5) == 0) throw invalidRecord();
            }
        } catch (VerificationFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw invalidRecord(); }
    }

    private void verifyUuidAndVocabulary(Connection connection, String sql,
                                         Set<String> vocabulary) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                if (!vocabulary.contains(rows.getString(2))) throw invalidRecord();
            }
        } catch (VerificationFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw invalidRecord(); }
    }

    private VerificationFailure invalidRecord() {
        return new VerificationFailure("bonded-stored-record-invalid");
    }

    private void requireUuid(String value) throws VerificationFailure {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw invalidRecord();
            }
        } catch (IllegalArgumentException failure) {
            throw invalidRecord();
        }
    }

    private Set<String> tables(Connection connection) throws Exception {
        HashSet<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return Set.copyOf(names);
    }

    private void assertSingleValue(
            Connection connection,
            String sql,
            String expected,
            String failureCode
    ) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next() || !expected.equalsIgnoreCase(row.getString(1))
                    || row.next()) {
                throw new VerificationFailure(failureCode);
            }
        }
    }

    private BondedCompanionPersistenceReadiness failure(Throwable failure) {
        String code = failure instanceof VerificationFailure
                ? failure.getMessage()
                : "bonded-persistence-startup-failed";
        return BondedCompanionPersistenceReadiness.failed(code);
    }

    private String loadScript() {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing bonded schema resource: " + RESOURCE
                );
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to load bonded schema v1", failure);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /** Internal exact verification failure code. */
    private static final class VerificationFailure extends Exception {
        private VerificationFailure(String code) {
            super(code);
        }
    }
}
