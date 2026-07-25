package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-source, immutable-quote, and atomic rollback gates for paid revival. */
class SqlitePaidRevivalEvidenceTest {
    private static final OperationId OPERATION = OperationId.parse(
            "60000000-0000-0000-0000-000000000399"
    );
    private static final IdempotencyKey IDEMPOTENCY =
            new IdempotencyKey("paid-revival-evidence");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqlitePaidRevivalOperations revivals;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("paid-revival-evidence.db")
        );
        new SqliteSchemaV1Manager(
                connections, () -> -10_000
        ).initialize();
        PaidRevivalTestSupport.seed(connections);
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units =
                new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(PaidRevivalDefinition.INSTANCE)
                ),
                units
        );
        revivals = new SqlitePaidRevivalOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> PaidRevivalTestSupport.CLOCK
                        ),
                        () -> PaidRevivalTestSupport.CLOCK
                ),
                reads,
                () -> PaidRevivalTestSupport.CLOCK,
                (claim, operation) ->
                        LiveOperationResult.confirmed("refund").completed(),
                List.of()
        );
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown(Duration.ofSeconds(5));
        }
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void staleDeathSnapshotFailsBeforeLiveWork() throws Exception {
        executeSql("""
                UPDATE companion_snapshot
                SET is_current = 0
                WHERE snapshot_id = '%s'
                """.formatted(PaidRevivalTestSupport.SNAPSHOT));
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                PaidRevivalTestSupport.request(),
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed("must-not-run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
        assertUnprepared();
    }

    @Test
    void staleCommandMembershipFailsBeforeLiveWork() throws Exception {
        executeSql("""
                DELETE FROM command_roster_membership
                WHERE profile_id = '%s'
                """.formatted(PaidRevivalTestSupport.PROFILE));
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                PaidRevivalTestSupport.request(),
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed("must-not-run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
        assertUnprepared();
    }

    @Test
    void staleProfileRevisionFailsBeforeLiveWork() throws Exception {
        executeSql("""
                UPDATE companion_profile
                SET metadata_revision = metadata_revision + 1
                WHERE profile_id = '%s'
                """.formatted(PaidRevivalTestSupport.PROFILE));
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                PaidRevivalTestSupport.request(),
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed("must-not-run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
        assertUnprepared();
    }

    @Test
    void preparedQuoteRejectsChangedConfigAndEconomy() throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        PaidRevivalRequest original = PaidRevivalTestSupport.request();
        OperationWorkflowResult retryable = submit(
                original,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.retryable(
                            "world-not-ready", null
                    ).completed();
                }
        );
        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                retryable.status()
        );

        assertRejectedReplay(
                changed(original, "config-revision", "config-revision-2"),
                liveCalls
        );
        assertRejectedReplay(
                changed(original, "life-essence", "other-essence"),
                liveCalls
        );

        OperationWorkflowResult resumed = submit(
                original,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed(
                            "exact-charge-and-spawn"
                    ).completed();
                }
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED, resumed.status()
        );
        assertEquals(2, liveCalls.get());
    }

    @Test
    void durableFailureRollsBackEveryAuthorityAndResumes()
            throws Exception {
        executeSql("""
                CREATE TRIGGER fail_paid_snapshot_retirement
                BEFORE UPDATE OF is_current ON companion_snapshot
                WHEN NEW.is_current = 0
                BEGIN
                    SELECT RAISE(
                        ABORT, 'injected_paid_snapshot_retirement_failure'
                    );
                END
                """);
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult failed = submit(
                PaidRevivalTestSupport.request(),
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed(
                            "exact-charge-and-spawn"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                failed.status()
        );
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertEquals(
                    CompanionAlias.State.LEASED,
                    transaction.identities()
                            .resolveAlias(PaidRevivalTestSupport.ALIAS)
                            .orElseThrow().state()
            );
            assertTrue(transaction.snapshots()
                    .findById(PaidRevivalTestSupport.SNAPSHOT)
                    .orElseThrow().current());
            var lifecycle = transaction.lifecycles()
                    .findByProfile(PaidRevivalTestSupport.PROFILE)
                    .orElseThrow();
            assertEquals(new LifecycleRevision(1), lifecycle.revision());
            assertEquals(OPERATION, lifecycle.activeOperationId());
            assertEquals(1, transaction.populationGroups()
                    .findReservations(OPERATION).size());
            assertTrue(transaction.outbox()
                    .findByOperation(OPERATION).isEmpty());
        }

        executeSql("DROP TRIGGER fail_paid_snapshot_retirement");
        OperationWorkflowResult resumed = submit(
                PaidRevivalTestSupport.request(),
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed(
                            "exact-receipts-reconfirmed"
                    ).completed();
                }
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED, resumed.status()
        );
        assertEquals(2, liveCalls.get());
    }

    private void assertRejectedReplay(
            PaidRevivalRequest changed,
            AtomicInteger liveCalls
    ) throws Exception {
        OperationWorkflowResult result = submit(
                changed,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.confirmed("must-not-run")
                            .completed();
                }
        );
        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(1, liveCalls.get());
    }

    private PaidRevivalRequest changed(
            PaidRevivalRequest request,
            String before,
            String after
    ) {
        String encoded = PaidRevivalDefinition.INSTANCE.encode(request);
        assertTrue(encoded.contains(before));
        return PaidRevivalDefinition.INSTANCE.decode(
                encoded.replace(before, after)
        );
    }

    private OperationWorkflowResult submit(
            PaidRevivalRequest request,
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalLiveBoundary boundary
    ) throws Exception {
        return revivals.submit(
                OPERATION,
                IDEMPOTENCY,
                request,
                boundary,
                (payload, operation) ->
                        LiveOperationResult.confirmed("release").completed()
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void assertUnprepared() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertFalse(transaction.identities()
                    .resolveAlias(PaidRevivalTestSupport.ALIAS).isPresent());
            assertEquals(
                    PaidRevivalTestSupport.before(),
                    transaction.lifecycles()
                            .findByProfile(PaidRevivalTestSupport.PROFILE)
                            .orElseThrow()
            );
            assertTrue(transaction.populationGroups()
                    .findReservations(OPERATION).isEmpty());
        }
    }

    private void executeSql(String sql) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
