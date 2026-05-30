package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Creates SQLite connections with Tamework defaults suitable for concurrent read + single-writer use.
 */
public final class SqliteConnectionManager {
    private static final Object DRIVER_LOCK = new Object();
    private static volatile boolean driverLoaded;

    private final Path databasePath;
    private final String jdbcUrl;

    public SqliteConnectionManager(@Nonnull Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.databasePath;
    }

    @Nonnull
    public Path getDatabasePath() {
        return databasePath;
    }

    @Nonnull
    public Connection openConnection() throws SQLException {
        ensureDriverLoaded();
        ensureParentDirectory();
        Connection connection;
        try {
            ensureDriverLoaded();
            connection = DriverManager.getConnection(jdbcUrl);
        } catch (LinkageError error) {
            throw new SQLException("sqlite_native_unavailable", error);
        }
        initializeSessionPragmas(connection);
        return connection;
    }

    private void ensureDriverLoaded() throws SQLException {
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
            } catch (ClassNotFoundException ex) {
                throw new SQLException("sqlite_jdbc_driver_missing", ex);
            } catch (LinkageError error) {
                throw new SQLException("sqlite_native_unavailable", error);
            }
        }
    }

    private void ensureParentDirectory() throws SQLException {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            throw new SQLException("Failed to create SQLite parent directory", ex);
        }
    }

    private void initializeSessionPragmas(@Nonnull Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }
}
