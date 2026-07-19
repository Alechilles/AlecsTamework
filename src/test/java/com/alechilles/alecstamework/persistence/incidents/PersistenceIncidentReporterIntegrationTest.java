package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageState;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceIncidentReporterIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsQuarantineBeforeReportingDurableAndCoalescesEquivalentRepeats() throws Exception {
        try (Harness harness = new Harness(tempDir.resolve("incidents.sqlite"))) {
            PersistenceFailureContext first = scopedContext("profile-a");
            PersistenceIncidentReporter.ReportSubmission opened = harness.reporter.report(first);

            assertEquals(1, harness.registry.size(), "process-local denial opens before SQLite completion");
            assertTrue(opened.durableCompletion().get(5, TimeUnit.SECONDS));
            assertTrue(harness.queue.awaitIdle(5_000L));

            PersistenceIncidentReporter.ReportSubmission repeated = harness.reporter.report(first);
            assertEquals(opened.incidentId(), repeated.incidentId());
            assertTrue(repeated.durableCompletion().get(5, TimeUnit.SECONDS));
            assertEquals(2L, harness.scalar("SELECT occurrence_count FROM persistence_incidents"));
            assertEquals(1L, harness.scalar("SELECT COUNT(*) FROM persistence_quarantines WHERE state = 'ACTIVE'"));

            PersistenceIncidentReporter.ReportSubmission unrelated = harness.reporter.report(scopedContext("profile-b"));
            assertNotEquals(opened.incidentId(), unrelated.incidentId());
            assertTrue(unrelated.durableCompletion().get(5, TimeUnit.SECONDS));
            assertEquals(2L, harness.scalar("SELECT COUNT(*) FROM persistence_incidents"));
            assertEquals(2, harness.registry.size());
        }
    }

    @Test
    void unknownTransactionOutcomeEntersReadOnlyWithoutTrustingSqliteForIncidentDurability() throws Exception {
        try (Harness harness = new Harness(tempDir.resolve("unknown.sqlite"))) {
            PersistenceFailureContext context = new PersistenceFailureContext(
                    "commit_outcome_unknown", PersistenceDomain.STORAGE, PersistenceOperationPhase.COMMIT,
                    PersistenceTransactionOutcome.UNKNOWN, List.of(), false, false,
                    false, false, false, false, false, true, "op-unknown", new IllegalStateException("unknown"));

            PersistenceIncidentReporter.ReportSubmission submission = harness.reporter.report(context);

            assertEquals(PersistenceStorageState.READ_ONLY, harness.storage.getState().status());
            assertEquals(submission.incidentId(), harness.storage.getState().incidentId());
            assertEquals(false, submission.durableCompletion().get(1, TimeUnit.SECONDS));
            assertEquals(0L, harness.scalar("SELECT COUNT(*) FROM persistence_incidents"));
        }
    }

    private PersistenceFailureContext scopedContext(String profileId) {
        return new PersistenceFailureContext(
                "publication_failed", PersistenceDomain.OWNER_MUTATION, PersistenceOperationPhase.PUBLICATION,
                PersistenceTransactionOutcome.COMMITTED,
                List.of(new PersistenceScope(PersistenceScopeType.PROFILE, profileId, "hash-" + profileId, null)),
                true, true, false, false, false, false, false, true,
                "operation-" + profileId, new IllegalStateException("publication failed")
        );
    }

    private static final class Harness implements AutoCloseable {
        private final SqliteConnectionManager connections;
        private final PersistenceWriteQueue queue;
        private final PersistenceStorageHealthService storage = new PersistenceStorageHealthService();
        private final PersistenceQuarantineRegistry registry = new PersistenceQuarantineRegistry();
        private final PersistenceIncidentReporter reporter;

        private Harness(Path database) throws Exception {
            connections = new SqliteConnectionManager(database);
            try (Connection connection = connections.openConnection()) {
                new SqliteSchemaMigrator().migrate(connection);
            }
            queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
            PersistenceIncidentRepository incidents = new PersistenceIncidentRepository(connections);
            PersistenceQuarantineRepository quarantines = new PersistenceQuarantineRepository(connections);
            reporter = new PersistenceIncidentReporter(
                    "boot-test", new PersistenceFailureClassifier(), incidents, quarantines,
                    registry, storage, queue
            );
        }

        private long scalar(String sql) throws Exception {
            try (Connection connection = connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }

        @Override
        public void close() {
            queue.close();
        }
    }
}
