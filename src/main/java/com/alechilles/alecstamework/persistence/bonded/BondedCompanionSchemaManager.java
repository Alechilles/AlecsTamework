package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Creates, upgrades, and verifies the standalone bonded-companion schema. */
public final class BondedCompanionSchemaManager {
    public static final int VERSION = BondedCompanionSchemaCatalog.VERSION;
    public static final String LINEAGE = "bonded-companions";
    private static final Set<String> LEGACY_TABLES = Set.of(
            "bonded_schema_history",
            "bonded_companion_profile",
            "bonded_companion_lease",
            "bonded_companion_extension_data",
            "bonded_companion_cleanup",
            "bonded_companion_operation"
    );
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "bonded_schema_history",
            "bonded_companion_profile",
            "bonded_companion_lease",
            "bonded_companion_extension_data",
            "bonded_companion_cleanup",
            "bonded_companion_operation",
            "bonded_companion_capture_source"
    );

    private final SqliteConnectionFactory connections;
    private final LongSupplier clock;
    private final BondedCompanionSchemaCatalog catalog;
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
        this.catalog = new BondedCompanionSchemaCatalog();
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
                } else {
                    initializeExisting(connection, existingTables);
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

    /** Returns the SHA-256 of the exact bundled current bonded schema. */
    @Nonnull
    public String schemaHash() {
        return catalog.hash(VERSION);
    }

    /** Returns the exact bonded-only user-table set. */
    @Nonnull
    public static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    private void createCurrent(Connection connection) throws Exception {
        executeScript(connection, catalog.script(1));
        insertHistory(connection, 1, catalog.hash(1));
        upgradeV1(connection);
    }

    private void initializeExisting(
            Connection connection,
            Set<String> existingTables
    ) throws Exception {
        int version = latestVersion(connection);
        Set<String> expected = version < VERSION
                ? LEGACY_TABLES : REQUIRED_TABLES;
        if (!existingTables.equals(expected)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        switch (version) {
            case 1 -> { verifyV1(connection); upgradeV1(connection); }
            case 2 -> { verifyV2(connection); upgradeV2(connection); }
            case 3 -> { verifyV3(connection); applyV4(connection); }
            case 4 -> { verifyV4(connection); applyV5(connection); }
            case 5 -> { verifyV5(connection); applyV6(connection); }
            case 6 -> { verifyV6(connection); applyV7(connection); }
            case VERSION -> verify(connection);
            default -> throw historyMismatch();
        }
    }

    private void upgradeV1(Connection connection) throws Exception {
        prepareV1OperationBackup(connection);
        executeScript(connection, catalog.script(2));
        insertHistory(connection, 2, catalog.hash(2));
        applyV3(connection);
    }

    private void upgradeV2(Connection connection) throws Exception {
        convertV2NoValueOperationTypes(connection);
        prepareEmptyOperationBackup(connection);
        applyV3(connection);
    }

    private void convertV2NoValueOperationTypes(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE bonded_companion_operation
                    SET result_json = json_set(
                        result_json,
                        '$.valueType',
                        CASE operation_type
                            WHEN 'SUMMON' THEN 'LEASE'
                            WHEN 'CLEANUP' THEN 'CLEANUP'
                            ELSE 'PROFILE'
                        END
                    )
                    WHERE operation_state <> 'PENDING'
                      AND json_type(result_json, '$.valueType') = 'text'
                      AND json_extract(result_json, '$.valueType') = 'NONE'
                      AND json_type(result_json, '$.value') = 'null'
                    """);
        }
    }

    private void applyV3(Connection connection) throws Exception {
        executeScript(connection, catalog.script(3));
        insertHistory(connection, 3, catalog.hash(3));
        applyV4(connection);
    }

    private void applyV4(Connection connection) throws Exception {
        executeScript(connection, catalog.script(4));
        insertHistory(connection, 4, catalog.hash(4));
        applyV5(connection);
    }

    private void applyV5(Connection connection) throws Exception {
        executeScript(connection, catalog.script(5));
        insertHistory(connection, 5, catalog.hash(5));
        applyV6(connection);
    }

    private void applyV6(Connection connection) throws Exception {
        executeScript(connection, catalog.script(6));
        insertHistory(connection, 6, catalog.hash(6));
        verifyV6(connection);
        applyV7(connection);
    }

    private void applyV7(Connection connection) throws Exception {
        executeScript(connection, catalog.script(7));
        insertHistory(connection, 7, catalog.hash(7));
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
        verifyHistory(connection, catalog.hashesThrough(VERSION));
        if (!BondedCompanionSchemaAuthorityVerifier
                .hasDurableCaptureSourceFence(connection)) {
            throw new VerificationFailure("bonded-capture-source-fence-missing");
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
        if (!tables(connection).equals(LEGACY_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        verifyHistory(connection, catalog.hashesThrough(1));
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) {
                throw new VerificationFailure("bonded-foreign-key-check-failed");
            }
        }
    }

    private void verifyV2(Connection connection) throws Exception {
        if (!tables(connection).equals(LEGACY_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        verifyHistory(connection, catalog.hashesThrough(2));
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) {
                throw new VerificationFailure("bonded-foreign-key-check-failed");
            }
        }
    }

    private void verifyV3(Connection connection) throws Exception {
        if (!tables(connection).equals(LEGACY_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        verifyHistory(connection, catalog.hashesThrough(3));
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
    }

    private void verifyV4(Connection connection) throws Exception {
        if (!tables(connection).equals(LEGACY_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        verifyHistory(connection, catalog.hashesThrough(4));
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
    }

    private void verifyV5(Connection connection) throws Exception {
        if (!tables(connection).equals(LEGACY_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        verifyHistory(connection, catalog.hashesThrough(5));
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
    }

    private void verifyV6(Connection connection) throws Exception {
        if (!tables(connection).equals(LEGACY_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        verifyHistory(connection, catalog.hashesThrough(6));
        if (!BondedCompanionSchemaAuthorityVerifier
                .hasLegacyCaptureSourceFence(connection)) {
            throw new VerificationFailure("bonded-capture-source-fence-missing");
        }
        assertSingleValue(connection, "PRAGMA integrity_check", "ok",
                "bonded-integrity-check-failed");
    }

    private void verifyHistory(Connection connection, String... hashes)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM bonded_schema_history ORDER BY version
                     """)) {
            for (int index = 0; index < hashes.length; index++) {
                verifyHistoryRow(rows, index + 1, hashes[index]);
            }
            if (rows.next()) throw historyMismatch();
        }
    }

    private void prepareV1OperationBackup(Connection connection) throws Exception {
        prepareEmptyOperationBackup(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO temp.bonded_v1_terminal_operation(
                        caller_namespace, idempotency_key, operation_type,
                        operation_state, result_json
                    )
                    SELECT caller_namespace, idempotency_key, operation_type,
                           operation_state, result_json
                    FROM bonded_companion_operation
                    WHERE operation_state <> 'PENDING'
                    """);
        }
    }

    private void prepareEmptyOperationBackup(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS temp.bonded_v1_terminal_operation");
            statement.execute("""
                    CREATE TEMP TABLE bonded_v1_terminal_operation(
                        caller_namespace TEXT NOT NULL,
                        idempotency_key TEXT NOT NULL,
                        operation_type TEXT NOT NULL,
                        operation_state TEXT NOT NULL,
                        result_json TEXT,
                        PRIMARY KEY(caller_namespace, idempotency_key)
                    )
                    """);
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

    /** Internal exact verification failure code. */
    private static final class VerificationFailure extends Exception {
        private VerificationFailure(String code) {
            super(code);
        }
    }
}
