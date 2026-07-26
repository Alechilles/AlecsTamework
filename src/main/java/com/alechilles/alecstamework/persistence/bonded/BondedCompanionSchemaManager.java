package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Creates and verifies only the standalone bonded-companion schema lineage v1. */
public final class BondedCompanionSchemaManager {
    public static final int VERSION = 2;
    public static final String LINEAGE = "bonded-companions";
    private static final String V1_RESOURCE = "/persistence/bonded/v1.sql";
    private static final String V2_RESOURCE = "/persistence/bonded/v2.sql";
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
    private final String v1Script;
    private final String v2Script;
    private final String v1Hash;
    private final String v2Hash;

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
        v1Script = loadScript(V1_RESOURCE);
        v2Script = loadScript(V2_RESOURCE);
        v1Hash = sha256(v1Script);
        v2Hash = sha256(v2Script);
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
            setForeignKeys(connection, false);
            connection.setAutoCommit(false);
            try {
                Set<String> existingTables = tables(connection);
                if (existingTables.isEmpty()) {
                    createCurrent(connection);
                } else if (!existingTables.equals(REQUIRED_TABLES)) {
                    throw new VerificationFailure("bonded-schema-table-mismatch");
                } else if (latestVersion(connection) == 1) {
                    verifyV1(connection);
                    upgradeV1(connection);
                } else {
                    verify(connection);
                }
                connection.commit();
                connection.setAutoCommit(true);
                setForeignKeys(connection, true);
                return BondedCompanionPersistenceReadiness.ready();
            } catch (Exception failure) {
                connection.rollback();
                connection.setAutoCommit(true);
                setForeignKeys(connection, true);
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
        return v2Hash;
    }

    /** Returns the exact bonded-only user-table set. */
    @Nonnull
    public static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    private void createCurrent(Connection connection) throws Exception {
        executeScript(connection, v1Script);
        insertHistory(connection, 1, v1Hash);
        upgradeV1(connection);
    }

    private void upgradeV1(Connection connection) throws Exception {
        executeScript(connection, v2Script);
        insertHistory(connection, 2, v2Hash);
        verify(connection);
    }

    private void insertHistory(Connection connection, int version, String hash)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_schema_history(
                    version, lineage, applied_at_ms, schema_hash
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, version);
            statement.setString(2, LINEAGE);
            statement.setLong(3, clock.getAsLong());
            statement.setString(4, hash);
            statement.executeUpdate();
        }
    }

    private void verify(Connection connection) throws Exception {
        if (!tables(connection).equals(REQUIRED_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM bonded_schema_history ORDER BY version
                     """)) {
            verifyHistoryRow(rows, 1, v1Hash);
            verifyHistoryRow(rows, 2, v2Hash);
            if (rows.next()) throw historyMismatch();
        }
        try {
            new BondedCompanionStoredRowValidator().verify(connection);
        } catch (BondedCompanionStoredRowValidator.InvalidRecordException failure) {
            throw new VerificationFailure("bonded-stored-record-invalid");
        }
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

    private void verifyV1(Connection connection) throws Exception {
        if (!tables(connection).equals(REQUIRED_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM bonded_schema_history ORDER BY version
                     """)) {
            verifyHistoryRow(rows, 1, v1Hash);
            if (rows.next()) throw historyMismatch();
        }
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) {
                throw new VerificationFailure("bonded-foreign-key-check-failed");
            }
        }
    }

    private void verifyHistoryRow(ResultSet rows, int version, String hash)
            throws Exception {
        if (!rows.next() || rows.getInt("version") != version
                || !LINEAGE.equals(rows.getString("lineage"))
                || !hash.equals(rows.getString("schema_hash"))) {
            throw historyMismatch();
        }
    }

    private VerificationFailure historyMismatch() {
        return new VerificationFailure("bonded-schema-history-mismatch");
    }

    private int latestVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT MAX(version) FROM bonded_schema_history")) {
            if (!row.next()) throw historyMismatch();
            return row.getInt(1);
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

    private void executeScript(Connection connection, String script)
            throws Exception {
        for (String sql : script.split(";\\s*(?:\\R|\\z)")) {
            if (!sql.isBlank()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private void setForeignKeys(Connection connection, boolean enabled)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=" + (enabled ? "ON" : "OFF"));
        }
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

    private String loadScript(String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing bonded schema resource: " + resource
                );
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to load bonded schema resource " + resource, failure);
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
