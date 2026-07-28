package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Initializes and verifies the final fresh-world bonded-companion schema. */
public final class BondedCompanionSchemaManager {
    public static final int VERSION = BondedCompanionSchemaCatalog.VERSION;
    public static final String LINEAGE = "bonded-companions";
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

    BondedCompanionSchemaManager(@Nonnull SqliteConnectionFactory connections) {
        this(connections, System::currentTimeMillis);
    }

    BondedCompanionSchemaManager(
            @Nonnull SqliteConnectionFactory connections,
            @Nonnull LongSupplier clock
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        catalog = new BondedCompanionSchemaCatalog();
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

    /** Initializes only an empty database; nonempty legacy databases fail closed. */
    @Nonnull
    public BondedCompanionPersistenceReadiness initialize() {
        try {
            if (hasExistingContent()) {
                try (Connection connection = openImmutableReadConnection()) {
                    if (!tables(connection).isEmpty()) {
                        verify(connection);
                        return BondedCompanionPersistenceReadiness.ready();
                    }
                }
            }
        } catch (Exception failure) {
            return failure(failure);
        }
        try (Connection connection = connections.openWriterConnection()) {
            setForeignKeys(connection, false);
            connection.setAutoCommit(false);
            try {
                if (tables(connection).isEmpty()) {
                    createCurrent(connection);
                } else {
                    verify(connection);
                }
                connection.commit();
                return BondedCompanionPersistenceReadiness.ready();
            } catch (Exception failure) {
                connection.rollback();
                return failure(failure);
            } finally {
                connection.setAutoCommit(true);
                setForeignKeys(connection, true);
            }
        } catch (Exception failure) {
            return failure(failure);
        }
    }

    /** Verifies the final schema, history, integrity, and persisted row contracts. */
    @Nonnull
    public BondedCompanionPersistenceReadiness verify() {
        try (Connection connection = connections.openReadConnection()) {
            verify(connection);
            return BondedCompanionPersistenceReadiness.ready();
        } catch (Exception failure) {
            return failure(failure);
        }
    }

    /** Returns the SHA-256 of the exact bundled final bonded schema. */
    @Nonnull
    public String schemaHash() {
        return catalog.hash();
    }

    /** Returns the exact bonded-only user-table set. */
    @Nonnull
    public static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    private void createCurrent(Connection connection) throws Exception {
        executeScript(connection, catalog.script());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_schema_history(
                    version, lineage, applied_at_ms, schema_hash
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, VERSION);
            statement.setString(2, LINEAGE);
            statement.setLong(3, clock.getAsLong());
            statement.setString(4, catalog.hash());
            statement.executeUpdate();
        }
        verify(connection);
    }

    private void verify(Connection connection) throws Exception {
        if (!tables(connection).equals(REQUIRED_TABLES)) {
            throw new VerificationFailure("bonded-schema-table-mismatch");
        }
        if (!BondedCompanionSchemaAuthorityVerifier.hasExactFinalSchema(
                connection, catalog.script())) {
            throw new VerificationFailure("bonded-schema-ddl-mismatch");
        }
        verifyHistory(connection);
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
                throw new VerificationFailure("bonded-foreign-key-check-failed");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT p.profile_id
                     FROM bonded_companion_profile p
                     LEFT JOIN bonded_companion_lease l ON l.profile_id = p.profile_id
                     WHERE (p.state = 'ACTIVE' AND l.profile_id IS NULL)
                        OR (p.state <> 'ACTIVE' AND l.profile_id IS NOT NULL)
                     LIMIT 1
                     """)) {
            if (rows.next()) {
                throw new VerificationFailure("bonded-orphaned-active-lease");
            }
        }
    }

    private void verifyHistory(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM bonded_schema_history
                     """)) {
            if (!rows.next() || rows.getInt("version") != VERSION
                    || !LINEAGE.equals(rows.getString("lineage"))
                    || !catalog.hash().equals(rows.getString("schema_hash"))
                    || rows.next()) {
                throw new VerificationFailure("bonded-schema-history-mismatch");
            }
        }
    }

    private Set<String> tables(Connection connection) throws Exception {
        HashSet<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return Set.copyOf(names);
    }

    /**
     * Classifies existing databases without acquiring write locks, changing journal mode,
     * or creating WAL shared-memory sidecars. Only a missing or table-empty target may proceed
     * to the writer used for fresh v1 creation.
     */
    private boolean hasExistingContent() throws Exception {
        Path databasePath = connections.databasePath();
        return Files.isRegularFile(databasePath) && Files.size(databasePath) > 0;
    }

    private Connection openImmutableReadConnection() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String uri = connections.databasePath().toUri() + "?mode=ro&immutable=1";
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + uri);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private void executeScript(Connection connection, String script) throws Exception {
        for (String sql : script.split(";\\s*(?:\\R|\\z)")) {
            if (!sql.isBlank()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private void setForeignKeys(Connection connection, boolean enabled) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=" + (enabled ? "ON" : "OFF"));
        }
    }

    private void assertSingleValue(
            Connection connection, String sql, String expected, String failureCode
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
                ? failure.getMessage() : "bonded-persistence-startup-failed";
        return BondedCompanionPersistenceReadiness.failed(code);
    }

    /** Internal exact verification failure code. */
    private static final class VerificationFailure extends Exception {
        private VerificationFailure(String code) {
            super(code);
        }
    }
}
