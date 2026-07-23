package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Opens replacement SQLite connections with one verified session policy.
 *
 * <p>Read connections are query-only. Only writer connections create the parent directory or
 * configure WAL, so this class can later be reused by non-mutating source classification.</p>
 */
public final class SqliteConnectionFactory {
    private static final Object DRIVER_LOCK = new Object();
    private static volatile boolean driverLoaded;

    private final Path databasePath;
    private final String jdbcUrl;
    private final int busyTimeoutMs;

    public SqliteConnectionFactory(@Nonnull Path databasePath) {
        this(databasePath, 5_000);
    }

    public SqliteConnectionFactory(@Nonnull Path databasePath, int busyTimeoutMs) {
        if (databasePath == null) {
            throw new IllegalArgumentException("SQLite database path is required");
        }
        if (busyTimeoutMs < 0) {
            throw new IllegalArgumentException("SQLite busy timeout cannot be negative");
        }
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.databasePath;
        this.busyTimeoutMs = busyTimeoutMs;
    }

    /** Returns the normalized target path. */
    @Nonnull
    public Path databasePath() {
        return databasePath;
    }

    /** Opens a connection permitted to mutate the replacement database. */
    @Nonnull
    public Connection openWriterConnection() throws SQLException {
        ensureDriverLoaded();
        ensureParentDirectory();
        Connection connection = open();
        try {
            configureWriter(connection);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            closeAfterConfigurationFailure(connection, failure);
            throw failure;
        }
    }

    /** Opens a query-only connection to an existing replacement database. */
    @Nonnull
    public Connection openReadConnection() throws SQLException {
        ensureDriverLoaded();
        if (!Files.isRegularFile(databasePath)) {
            throw new SQLException("sqlite_database_missing");
        }
        Connection connection = open();
        try {
            configureRead(connection);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            closeAfterConfigurationFailure(connection, failure);
            throw failure;
        }
    }

    private Connection open() throws SQLException {
        try {
            return DriverManager.getConnection(jdbcUrl);
        } catch (LinkageError error) {
            throw new SQLException("sqlite_native_unavailable", error);
        }
    }

    private void configureWriter(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertTextPragma(statement, "PRAGMA journal_mode=WAL", "wal", "journal_mode");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            statement.execute("PRAGMA wal_autocheckpoint=1000");
        }
        assertIntegerPragma(connection, "PRAGMA foreign_keys", 1, "foreign_keys");
    }

    private void configureRead(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            statement.execute("PRAGMA query_only=ON");
        }
        assertIntegerPragma(connection, "PRAGMA foreign_keys", 1, "foreign_keys");
        assertIntegerPragma(connection, "PRAGMA query_only", 1, "query_only");
    }

    private void assertTextPragma(Statement statement,
                                  String sql,
                                  String expected,
                                  String label) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next() || !expected.equalsIgnoreCase(resultSet.getString(1))) {
                throw new SQLException("sqlite_pragma_" + label + "_not_applied");
            }
        }
    }

    private void assertIntegerPragma(Connection connection,
                                     String sql,
                                     int expected,
                                     String label) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next() || resultSet.getInt(1) != expected) {
                throw new SQLException("sqlite_pragma_" + label + "_not_applied");
            }
        }
    }

    private void ensureParentDirectory() throws SQLException {
        Path parent = databasePath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception failure) {
            throw new SQLException("sqlite_parent_directory_unavailable", failure);
        }
    }

    private static void ensureDriverLoaded() throws SQLException {
        if (driverLoaded) {
            return;
        }
        synchronized (DRIVER_LOCK) {
            if (driverLoaded) {
                return;
            }
            try {
                Class.forName("org.sqlite.JDBC");
                driverLoaded = true;
            } catch (ClassNotFoundException failure) {
                throw new SQLException("sqlite_jdbc_driver_missing", failure);
            } catch (LinkageError error) {
                throw new SQLException("sqlite_native_unavailable", error);
            }
        }
    }

    private void closeAfterConfigurationFailure(Connection connection, Throwable original) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
