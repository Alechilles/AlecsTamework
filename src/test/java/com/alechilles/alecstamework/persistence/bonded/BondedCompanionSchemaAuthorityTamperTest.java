package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavioral tamper tests for durable capture-source index authority. */
class BondedCompanionSchemaAuthorityTamperTest {
    @TempDir Path tempDir;

    @Test
    void missingDurableSourceFenceIsRejected() throws Exception {
        assertRejected("DROP INDEX bonded_capture_source_uuid_uq",
                "bonded-schema-ddl-mismatch");
    }

    @Test
    void operationTableWithoutTerminalTypeAndStateChecksIsRejected()
            throws Exception {
        assertRejected("""
                DROP TABLE bonded_companion_operation;
                CREATE TABLE bonded_companion_operation (
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    roster_id TEXT NOT NULL,
                    profile_id TEXT,
                    operation_type TEXT NOT NULL,
                    request_hash TEXT NOT NULL,
                    operation_state TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    expected_revision INTEGER,
                    PRIMARY KEY(caller_namespace, idempotency_key)
                )
                """, "bonded-schema-ddl-mismatch");
    }

    @Test
    void operationTableWithoutRetentionIndexIsRejected() throws Exception {
        assertRejected("DROP INDEX bonded_operation_retention_idx",
                "bonded-schema-ddl-mismatch");
    }

    private void assertRejected(String tamperSql, String expectedCode) throws Exception {
        Path path = tempDir.resolve(UUIDName.next() + ".sqlite");
        BondedCompanionSchemaManager manager =
                new BondedCompanionSchemaManager(path, () -> 10L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : tamperSql.split(";\\s*(?:\\R|\\z)")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }

        BondedCompanionPersistenceReadiness readiness = manager.initialize();

        assertFalse(readiness.availability().available());
        assertEquals(expectedCode, readiness.diagnosticCode());
    }

    private static final class UUIDName {
        private static int value;

        private static synchronized String next() {
            return "tamper-" + value++;
        }
    }
}
