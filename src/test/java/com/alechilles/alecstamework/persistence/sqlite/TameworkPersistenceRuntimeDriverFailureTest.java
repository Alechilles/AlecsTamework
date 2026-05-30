package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies SQLite driver startup failures stay recoverable.
 */
class TameworkPersistenceRuntimeDriverFailureTest {
    @Test
    void detectsWrappedSqliteDriverLinkageFailures() {
        LinkageError linkageError = new LinkageError("loader constraint violation");
        SQLException exception = new SQLException("sqlite_native_unavailable", linkageError);

        assertTrue(TameworkPersistenceRuntime.isSqliteDriverUnavailable(exception));
    }

    @Test
    void ignoresOrdinarySchemaFailures() {
        SQLException exception = new SQLException("no such table: schema_migrations");

        assertFalse(TameworkPersistenceRuntime.isSqliteDriverUnavailable(exception));
    }
}
