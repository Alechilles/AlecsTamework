package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical success, retry, and economic convergence tests for paid revival. */
class SqlitePaidRevivalOperationsTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqlitePaidRevivalOperations revivals;
    private AtomicInteger refunds;
    private AtomicInteger releases;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("paid-revival.db")
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
        refunds = new AtomicInteger();
        releases = new AtomicInteger();
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
                (claim, operation) -> {
                    refunds.incrementAndGet();
                    assertEquals(
                            OperationPhase.COMPENSATING,
                            operation.phase()
                    );
                    return LiveOperationResult.confirmed(
                            "refund-delivered"
                    ).completed();
                },
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
    void exactChargeAndSpawnCommitOneCanonicalRevival() throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                1,
                (payload, operation) -> {
                    liveCalls.incrementAndGet();
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase()
                    );
                    assertEquals(
                            1,
                            reservationCountUnchecked(
                                    operation.operationId()
                            )
                    );
                    return PaidRevivalLiveResult.confirmed(
                            "charge-and-spawn-receipts"
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, liveCalls.get());
        assertEquals(3, result.events().size());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertEquals(new LifecycleRevision(2), lifecycle().revision());
        assertEquals(
                LifecycleLocation.liveEntity(
                        PaidRevivalTestSupport.ALIAS.toString(), "world"
                ),
                lifecycle().location()
        );
        assertEquals(
                CompanionAlias.State.CURRENT, alias().state()
        );
        assertFalse(snapshot().current());
        assertEquals(0, reservationCount(operationId(1)));
        assertTrue(refund(operationId(1)).isEmpty());

        OperationWorkflowResult replay = submit(
                1,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return PaidRevivalLiveResult.unknown(
                            "must-not-run", null
                    ).completed();
                }
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, liveCalls.get());
    }

    @Test
    void provenNoChargeCompensatesWithoutCreatingARefund() throws Exception {
        OperationWorkflowResult result = submit(
                2,
                (request, operation) -> PaidRevivalLiveResult.noCharge(
                        "no-charge-no-spawn"
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                result.status()
        );
        assertEquals(1, releases.get());
        assertEquals(0, refunds.get());
        assertTrue(refund(operationId(2)).isEmpty());
        assertCompensatedDead(operationId(2));
    }

    @Test
    void provenChargeAndSpawnAbsenceRefundsTheWholeRecipeOnce()
            throws Exception {
        OperationWorkflowResult result = submit(
                3,
                (request, operation) ->
                        PaidRevivalLiveResult.refundRequired(
                                "charge-without-spawn"
                        ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                result.status()
        );
        RefundClaim claim = refund(operationId(3)).orElseThrow();
        assertTrue(claim.delivered());
        assertEquals(2, claim.items().size());
        assertEquals("life-essence", claim.items().get(0).itemId());
        assertEquals(3, claim.items().get(0).quantity());
        assertEquals("gold-bar", claim.items().get(1).itemId());
        assertEquals(2, claim.items().get(1).quantity());
        assertEquals(1, refunds.get());
        assertEquals(0, releases.get());
        assertCompensatedDead(operationId(3));

        OperationWorkflowResult replay = submit(
                3,
                (request, operation) ->
                        PaidRevivalLiveResult.confirmed("must-not-run")
                                .completed()
        );
        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                replay.status()
        );
        assertEquals(1, refunds.get());
    }

    @Test
    void retryableReceiptResolutionKeepsOneFenceThenSucceeds()
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var boundary =
                (com.alechilles.alecstamework.companion.revival
                        .PaidRevivalLiveBoundary) (request, operation) ->
                        attempts.incrementAndGet() == 1
                                ? PaidRevivalLiveResult.retryable(
                                        "world-not-ready", null
                                ).completed()
                                : PaidRevivalLiveResult.confirmed(
                                        "both-receipts"
                                ).completed();

        OperationWorkflowResult first = submit(4, boundary);
        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                first.status()
        );
        assertEquals(1, reservationCount(operationId(4)));
        assertEquals(CompanionAlias.State.LEASED, alias().state());

        OperationWorkflowResult second = submit(4, boundary);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, second.status());
        assertEquals(2, attempts.get());
        assertEquals(0, reservationCount(operationId(4)));
    }

    @Test
    void successfulRevivalReplacesTheExactPreviousTimedLease()
            throws Exception {
        TimedSummonLease before = previousLease();
        TimedSummonLease after = activeLease(before);
        seedLease(before);

        OperationWorkflowResult result = submit(
                5,
                PaidRevivalTestSupport.request(
                        new TimedSummonActivation(
                                PaidRevivalTestSupport.FAMILY,
                                PaidRevivalTestSupport.SLOT,
                                1,
                                before,
                                after
                        )
                ),
                (request, operation) ->
                        PaidRevivalLiveResult.confirmed(
                                "timed-charge-and-spawn"
                        ).completed()
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(4, result.events().size());
        assertEquals(after, timedLease());
    }

    private OperationWorkflowResult submit(
            int number,
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalLiveBoundary boundary
    ) throws Exception {
        return submit(
                number,
                PaidRevivalTestSupport.request(),
                boundary
        );
    }

    private OperationWorkflowResult submit(
            int number,
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalRequest request,
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalLiveBoundary boundary
    ) throws Exception {
        return revivals.submit(
                operationId(number),
                new IdempotencyKey("paid-revival-" + number),
                request,
                boundary,
                (payload, operation) -> {
                    releases.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "holds-released-no-receipts"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(
                10, TimeUnit.SECONDS
        );
    }

    private TimedSummonLease previousLease() {
        return new TimedSummonLease(
                PaidRevivalTestSupport.PROFILE,
                1,
                null,
                null,
                null,
                new TimedSummonPolicy(
                        "timed-config",
                        4L,
                        10_000,
                        2_000,
                        true,
                        List.of(5_000L)
                ),
                Set.of(),
                null,
                -5_000,
                -3_000
        );
    }

    private TimedSummonLease activeLease(TimedSummonLease before) {
        return new TimedSummonLease(
                before.profileId(),
                2,
                new TimedSummonSessionId(
                        UUID.fromString(
                                "70000000-0000-0000-0000-000000000301"
                        )
                ),
                10_000L,
                null,
                before.policy(),
                Set.of(),
                PaidRevivalTestSupport.REQUESTED_AT,
                before.createdAtMs(),
                PaidRevivalTestSupport.REQUESTED_AT
        );
    }

    private void seedLease(TimedSummonLease lease) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            assertTrue(new SqliteTimedSummonLeaseStore(connection)
                    .replace(null, lease).applied());
            connection.commit();
        }
    }

    private TimedSummonLease timedLease() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteTimedSummonLeaseStore(connection)
                    .find(PaidRevivalTestSupport.PROFILE)
                    .orElseThrow();
        }
    }

    private void assertCompensatedDead(OperationId operationId)
            throws Exception {
        assertEquals(LifecycleState.DEAD_REVIVABLE, lifecycle().state());
        assertEquals(new LifecycleRevision(2), lifecycle().revision());
        assertEquals(LifecycleLocation.none(), lifecycle().location());
        assertEquals(CompanionAlias.State.RETIRED, alias().state());
        assertTrue(snapshot().current());
        assertEquals(0, reservationCount(operationId));
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PaidRevivalTestSupport.PROFILE)
                    .orElseThrow();
        }
    }

    private CompanionAlias alias() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(PaidRevivalTestSupport.ALIAS)
                    .orElseThrow();
        }
    }

    private com.alechilles.alecstamework.companion.snapshot
            .CompanionSnapshot snapshot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findById(PaidRevivalTestSupport.SNAPSHOT)
                    .orElseThrow();
        }
    }

    private java.util.Optional<RefundClaim> refund(
            OperationId operationId
    ) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteRefundClaimStore(connection)
                    .findByOperation(operationId);
        }
    }

    private int reservationCount(OperationId operationId)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqlitePopulationGroupStore(connection)
                    .findReservations(operationId).size();
        }
    }

    private int reservationCountUnchecked(OperationId operationId) {
        try {
            return reservationCount(operationId);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d", number
        ));
    }
}
