package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClassifier;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedPersistenceRecoveryCoordinatorTest {
    @TempDir
    Path tempDir;

    @Test
    void registeredVerifierPublishesThenAtomicallyResolvesIncidentAndFence() throws Exception {
        try (Harness harness = new Harness("resolve.sqlite")) {
            String incidentId = harness.openIncident();
            AtomicInteger publications = new AtomicInteger();
            harness.coordinator.register(harness.verifier(context -> {
                PersistenceQuarantineRecord fence = context.quarantines().getFirst();
                return new ScopedRecoveryVerification(
                        ScopedRecoveryResolution.RESOLVED_NEW_STATE, "canonical_state_verified",
                        Map.of(fence.quarantineId(), fence.evidenceHash()),
                        publications::incrementAndGet, null);
            }));

            ScopedPersistenceRecoveryCoordinator.RecoveryResult result = harness.coordinator
                    .request(incidentId, ScopedRecoveryTrigger.OPERATOR_REQUEST).get(5, TimeUnit.SECONDS);

            assertEquals(ScopedPersistenceRecoveryCoordinator.RecoveryStatus.RESOLVED, result.status());
            assertEquals(1, publications.get());
            assertEquals(0, harness.registry.size());
            assertEquals("RESOLVED", harness.text(
                    "SELECT status FROM persistence_incidents WHERE incident_id = ?", incidentId));
            assertEquals("CLEARED", harness.text(
                    "SELECT state FROM persistence_quarantines WHERE incident_id = ?", incidentId));
        }
    }

    @Test
    void unresolvedEvidenceRemainsFencedAndManualRetryCannotForceClear() throws Exception {
        try (Harness harness = new Harness("retain.sqlite")) {
            String incidentId = harness.openIncident();
            harness.coordinator.register(harness.verifier(context ->
                    ScopedRecoveryVerification.unresolved(
                            ScopedRecoveryResolution.STILL_AMBIGUOUS, "live_projection_ambiguous")));

            ScopedPersistenceRecoveryCoordinator.RecoveryResult result = harness.coordinator
                    .request(incidentId, ScopedRecoveryTrigger.OPERATOR_REQUEST).get(5, TimeUnit.SECONDS);

            assertEquals(ScopedPersistenceRecoveryCoordinator.RecoveryStatus.RETAINED, result.status());
            assertEquals(1, harness.registry.size());
            assertEquals("OPEN", harness.text(
                    "SELECT status FROM persistence_incidents WHERE incident_id = ?", incidentId));
            assertEquals(1L, harness.scalar(
                    "SELECT recovery_attempts FROM persistence_incidents WHERE incident_id = ?", incidentId));
        }
    }

    @Test
    void duplicateTriggersShareOneVerifierExecution() throws Exception {
        try (Harness harness = new Harness("coalesce.sqlite")) {
            String incidentId = harness.openIncident();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger executions = new AtomicInteger();
            harness.coordinator.register(harness.verifier(context -> {
                executions.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return ScopedRecoveryVerification.unresolved(
                        ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE, "world_not_ready");
            }));

            CompletableFuture<ScopedPersistenceRecoveryCoordinator.RecoveryResult> first =
                    harness.coordinator.request(incidentId, ScopedRecoveryTrigger.WORLD_READY);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<ScopedPersistenceRecoveryCoordinator.RecoveryResult> duplicate =
                    harness.coordinator.request(incidentId, ScopedRecoveryTrigger.CHUNK_READY);
            assertSame(first, duplicate);
            release.countDown();

            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, executions.get());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final SqliteConnectionManager connections;
        private final PersistenceWriteQueue queue;
        private final PersistenceQuarantineRegistry registry = new PersistenceQuarantineRegistry();
        private final PersistenceIncidentRepository incidents;
        private final PersistenceQuarantineRepository quarantines;
        private final PersistenceIncidentReporter reporter;
        private final ScopedPersistenceRecoveryCoordinator coordinator;

        private Harness(String filename) throws Exception {
            connections = new SqliteConnectionManager(Path.of(System.getProperty("java.io.tmpdir"))
                    .resolve("tamework-" + System.nanoTime() + "-" + filename));
            try (Connection connection = connections.openConnection()) {
                new SqliteSchemaMigrator().migrate(connection);
            }
            PersistenceStorageHealthService storage = new PersistenceStorageHealthService();
            queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(storage), null);
            incidents = new PersistenceIncidentRepository(connections);
            quarantines = new PersistenceQuarantineRepository(connections);
            reporter = new PersistenceIncidentReporter(
                    "boot-test", new PersistenceFailureClassifier(), incidents, quarantines,
                    registry, storage, queue);
            coordinator = new ScopedPersistenceRecoveryCoordinator(
                    incidents, quarantines, registry, new PersistenceFeatureCircuitRegistry(), queue);
        }

        private String openIncident() throws Exception {
            PersistenceScope scope = new PersistenceScope(
                    PersistenceScopeType.PROFILE, "profile-a", "scope-hash", "profile_catalog");
            PersistenceFailureContext context = new PersistenceFailureContext(
                    "publication_failed", PersistenceDomain.OWNER_MUTATION,
                    PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.COMMITTED,
                    List.of(scope), true, true, false, false, false,
                    false, false, true, "operation-a", new IllegalStateException("publish"));
            PersistenceIncidentReporter.ReportSubmission submission = reporter.report(context);
            assertTrue(submission.durableCompletion().get(5, TimeUnit.SECONDS));
            return submission.incidentId();
        }

        private ScopedPersistenceRecoveryVerifier verifier(VerificationFunction function) {
            return new ScopedPersistenceRecoveryVerifier() {
                @Override
                public PersistenceDomain domain() {
                    return PersistenceDomain.OWNER_MUTATION;
                }

                @Override
                public String verifierId() {
                    return "owner-mutation-test-v1";
                }

                @Override
                public ScopedRecoveryVerification verify(ScopedRecoveryContext context) throws Exception {
                    return function.verify(context);
                }
            };
        }

        private String text(String sql, String value) throws Exception {
            try (Connection connection = connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        }

        private long scalar(String sql, String value) throws Exception {
            try (Connection connection = connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            }
        }

        @Override
        public void close() {
            coordinator.close();
            queue.close();
        }
    }

    @FunctionalInterface
    private interface VerificationFunction {
        ScopedRecoveryVerification verify(ScopedRecoveryContext context) throws Exception;
    }
}
