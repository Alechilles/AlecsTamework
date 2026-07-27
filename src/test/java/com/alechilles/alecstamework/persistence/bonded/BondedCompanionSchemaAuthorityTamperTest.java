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

/** Behavioral tamper tests for exact capture-source index authority. */
class BondedCompanionSchemaAuthorityTamperTest {
    private static final String PREFIX = """
            CREATE UNIQUE INDEX bonded_capture_source_once_idx
            ON bonded_companion_operation(%s)
            WHERE operation_type = 'CAPTURE'
              AND operation_state = 'SUCCEEDED'
              AND json_type(
                  result_json, '$.captureEvidence.sourceNpcUuid'
              ) = 'text'
            """;
    @TempDir Path tempDir;

    @Test
    void inertAdditionalPredicateInvalidatesSourceFence() throws Exception {
        assertRejected(PREFIX.formatted("json_extract(result_json, "
                + "'$.captureEvidence.sourceNpcUuid')") + " AND 0");
    }

    @Test
    void wrongIndexedJsonExpressionInvalidatesSourceFence() throws Exception {
        assertRejected(PREFIX.formatted("json_extract(result_json, "
                + "'$.captureEvidence.profileId')"));
    }

    @Test
    void missingSuccessPredicateInvalidatesSourceFence() throws Exception {
        assertRejected("""
                CREATE UNIQUE INDEX bonded_capture_source_once_idx
                ON bonded_companion_operation(
                    json_extract(result_json,
                                 '$.captureEvidence.sourceNpcUuid')
                )
                WHERE operation_type = 'CAPTURE'
                  AND json_type(
                      result_json, '$.captureEvidence.sourceNpcUuid'
                  ) = 'text'
                """);
    }

    private void assertRejected(String replacementSql) throws Exception {
        Path path = tempDir.resolve(UUIDName.next() + ".sqlite");
        BondedCompanionSchemaManager manager =
                new BondedCompanionSchemaManager(path, () -> 10L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "DELETE FROM bonded_schema_history WHERE version = 7"));
            statement.execute("DROP TABLE bonded_companion_capture_source");
            statement.execute(replacementSql);
        }

        BondedCompanionPersistenceReadiness readiness = manager.initialize();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-capture-source-fence-missing",
                readiness.diagnosticCode());
    }

    private static final class UUIDName {
        private static int value;

        private static synchronized String next() {
            return "tamper-" + value++;
        }
    }
}
