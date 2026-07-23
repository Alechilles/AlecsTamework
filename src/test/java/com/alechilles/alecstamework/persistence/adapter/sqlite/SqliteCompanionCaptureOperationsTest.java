package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureOutcome;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
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
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end capture fencing, live resolution, atomic commit, and replay tests. */
class SqliteCompanionCaptureOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"capturedAtMs\":-500}";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionCaptureOperations captures;
    private AtomicInteger refundDeliveries;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seedLiveProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        refundDeliveries = new AtomicInteger();
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(CompanionCaptureDefinition.INSTANCE)
                ),
                units
        );
        captures = new SqliteCompanionCaptureOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -400
                        ),
                        () -> -400
                ),
                () -> -400,
                (claim, operation) -> {
                    refundDeliveries.incrementAndGet();
                    assertFalse(claim.delivered());
                    assertEquals(
                            OperationPhase.COMPENSATING,
                            operation.phase()
                    );
                    return LiveOperationResult.confirmed(
                            "refund_receipt_confirmed"
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
    void durableFencePrecedesLiveBoundaryAndCommitIsAtomic() throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                1,
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    assertEquals(OperationPhase.LIVE_APPLYING, operation.phase());
                    CompanionLifecycle fenced = lifecycle();
                    assertEquals(new LifecycleRevision(1), fenced.revision());
                    assertEquals(operation.operationId(), fenced.activeOperationId());
                    assertTrue(snapshot().isEmpty());
                    return LiveOperationResult.confirmed(
                            "capture_receipt_and_target_retirement_confirmed"
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, liveCalls.get());
        assertEquals(3, result.events().size());
        CompanionCaptureOutcome outcome = CompanionCaptureEventCodec.decode(
                result.events().getFirst().payloadVersion(),
                result.events().getFirst().payloadJson()
        );
        CompanionProfileProjectionChange change =
                CompanionProfileProjectionChangeCodec.decode(
                        result.events().get(1).payloadVersion(),
                        result.events().get(1).payloadJson()
                );
        CompanionLifecycle captured = lifecycle();
        assertEquals(LifecycleState.CAPTURED, captured.state());
        assertEquals(new LifecycleRevision(2), captured.revision());
        assertEquals(OWNER, captured.ownerId());
        assertNull(captured.activeOperationId());
        assertEquals(outcome.snapshotId().toString(), captured.location().key());
        assertEquals(captureRequest().snapshot(), snapshot().orElseThrow());
        assertEquals(
                CompanionProfileProjectionChange.Source.LIFECYCLE,
                change.source()
        );
        assertEquals(
                java.util.Set.of(CompanionCaptureRequest.SNAPSHOT_KIND),
                change.after().activeSnapshotKinds()
        );
    }

    @Test
    void retryAndPublishedReplayNeverDuplicateExactLiveMutation() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();

        var boundary =
                (com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary)
                        (capture, operation) -> {
                            int resolution = resolutions.incrementAndGet();
                            if (resolution == 1) {
                                return LiveOperationResult.retryable(
                                        "capture_world_temporarily_unavailable",
                                        null
                                ).completed();
                            }
                            mutations.incrementAndGet();
                            return LiveOperationResult.confirmed(
                                    "capture_receipt_and_target_retirement_confirmed"
                            ).completed();
                        };

        OperationWorkflowResult first = submit(2, boundary);
        OperationWorkflowResult second = submit(2, boundary);
        OperationWorkflowResult replay = submit(2, boundary);

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                first.status()
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, second.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(2, resolutions.get());
        assertEquals(1, mutations.get());
    }

    @Test
    void staleSecondCaptureCannotCrossItsLiveBoundary() throws Exception {
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submit(3, (capture, operation) ->
                        LiveOperationResult.confirmed("capture_confirmed")
                                .completed()
                ).status()
        );
        AtomicInteger calls = new AtomicInteger();

        OperationWorkflowResult duplicate = submit(
                4,
                (capture, operation) -> {
                    calls.incrementAndGet();
                    return LiveOperationResult.confirmed("should_not_run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                duplicate.status()
        );
        assertEquals(0, calls.get());
        assertEquals(LifecycleState.CAPTURED, lifecycle().state());
    }

    @Test
    void exactRefundCompensatesOnceAndRestoresFencedLiveLifecycle()
            throws Exception {
        AtomicInteger captureResolutions = new AtomicInteger();

        OperationWorkflowResult first = submit(
                5,
                (capture, operation) -> {
                    captureResolutions.incrementAndGet();
                    return LiveOperationResult.compensate(
                            "source_spent_target_proven_live",
                            null
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.COMPENSATED, first.status());
        assertEquals(OperationPhase.COMPENSATED, first.operation().phase());
        assertTrue(first.events().isEmpty());
        assertEquals(1, captureResolutions.get());
        assertEquals(1, refundDeliveries.get());
        RefundClaim delivered = refundClaim(operationId(5));
        assertTrue(delivered.delivered());
        assertEquals("capture-device", delivered.items().getFirst().itemId());
        assertEquals(1, delivered.items().getFirst().quantity());
        assertEquals(
                "refund_receipt_confirmed",
                delivered.deliveryEvidence()
        );
        CompanionLifecycle restored = lifecycle();
        assertEquals(LifecycleState.ACTIVE, restored.state());
        assertEquals(new LifecycleRevision(2), restored.revision());
        assertNull(restored.activeOperationId());
        assertEquals(
                LifecycleLocation.liveEntity(ALIAS.toString(), "world"),
                restored.location()
        );
        assertTrue(snapshot().isEmpty());

        OperationWorkflowResult replay = submit(
                5,
                (capture, operation) -> {
                    captureResolutions.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                replay.status()
        );
        assertEquals(1, captureResolutions.get());
        assertEquals(1, refundDeliveries.get());
        assertEquals(delivered, refundClaim(operationId(5)));
    }

    private OperationWorkflowResult submit(
            int number,
            com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary boundary
    ) throws Exception {
        SqliteCompanionCaptureOperations.Submission submission = captures.submit(
                operationId(number),
                new IdempotencyKey("capture-" + number),
                captureRequest(),
                boundary
        );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private CompanionCaptureRequest captureRequest() {
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                1,
                SNAPSHOT_JSON,
                Sha256Hash.ofUtf8(SNAPSHOT_JSON),
                new LifecycleRevision(1),
                true,
                -500
        );
        return new CompanionCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                OWNER,
                ALIAS,
                "world",
                snapshot,
                new CaptureSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        "capture-device",
                        1,
                        "before",
                        "after",
                        "capture-receipt"
                ),
                -600
        );
    }

    private void seedLiveProfile() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Companion",
                    "role",
                    null,
                    null,
                    "world",
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    null,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(ALIAS.toString(), "world"),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    ReconciliationGeneration.INITIAL,
                    null
            ));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO companion_alias(
                        npc_uuid, profile_id, alias_generation, alias_state,
                        lease_operation_id, mapped_at_ms, retired_at_ms
                    ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                    """)) {
                statement.setString(1, ALIAS.toString());
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -10_000);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private java.util.Optional<CompanionSnapshot> snapshot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(
                            PROFILE,
                            CompanionCaptureRequest.SNAPSHOT_KIND
                    );
        }
    }

    private RefundClaim refundClaim(OperationId operationId) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteRefundClaimStore(connection)
                    .findByOperation(operationId)
                    .orElseThrow();
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d",
                number
        ));
    }
}
