package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
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
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionCaptureOperations;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionLifecycleStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEvidenceReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationPublisher;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionGateway;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteWriterConfiguration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves an external source receipt survives a rolled-back durable commit without double spend.
 */
class ExactSourceFinalizationTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"capturedAtMs\":-500}";

    @TempDir
    Path tempDir;

    @Test
    void receiptReadbackFinalizesOneExactSourceAfterDurableRollback()
            throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seedLiveProfile(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        AtomicInteger commits = new AtomicInteger();
        SqliteSingleWriter faultedWriter = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, operationId) -> {
                    if (OPERATION.equals(operationId)
                            && checkpoint
                            == com.alechilles.alecstamework.persistence.kernel
                            .PersistenceCheckpoint.BEFORE_COMMIT
                            && commits.incrementAndGet() == 3) {
                        throw new IllegalStateException(
                                "injected_after_live_before_durable"
                        );
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        ReceiptBoundary boundary = new ReceiptBoundary();
        try {
            OperationWorkflowResult interrupted = captureOperations(
                    faultedWriter,
                    reads
            ).submit(
                    OPERATION,
                    new IdempotencyKey("exact-source-capture"),
                    request(),
                    boundary
            ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                    interrupted.status()
            );
            assertEquals(1, boundary.sourceMutations.get());
            assertEquals(1, boundary.targetRetirements.get());
            assertEquals(OperationPhase.LIVE_APPLYING, operation(connections).phase());
            assertEquals(new LifecycleRevision(1), lifecycle(connections).revision());
        } finally {
            faultedWriter.shutdown(Duration.ofSeconds(5));
        }

        SqliteSingleWriter recoveryWriter = new SqliteSingleWriter(connections);
        try {
            OperationWorkflowResult recovered = captureOperations(
                    recoveryWriter,
                    reads
            ).submit(
                    OPERATION,
                    new IdempotencyKey("exact-source-capture"),
                    request(),
                    boundary
            ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(OperationWorkflowResult.Status.PUBLISHED, recovered.status());
            assertEquals(2, boundary.resolutions.get());
            assertEquals(1, boundary.sourceMutations.get());
            assertEquals(1, boundary.targetRetirements.get());
            assertEquals(LifecycleState.CAPTURED, lifecycle(connections).state());
            assertEquals(new LifecycleRevision(2), lifecycle(connections).revision());
            assertTrue(snapshotExists(connections));
        } finally {
            recoveryWriter.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private SqliteCompanionCaptureOperations captureOperations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads
    ) {
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(CompanionCaptureDefinition.INSTANCE)
                ),
                units
        );
        return new SqliteCompanionCaptureOperations(
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
                (claim, operation) -> LiveOperationResult.confirmed(
                        "refund_receipt_confirmed"
                ).completed(),
                List.of()
        );
    }

    private CompanionCaptureRequest request() {
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
                OwnerId.parse("30000000-0000-0000-0000-000000000001"),
                ALIAS,
                "world",
                snapshot,
                CapturedArtifact.create(
                        "capture-device-filled",
                        1,
                        0.0D,
                        0.0D,
                        "{\"Tamework.CaptureSnapshotId\":\""
                                + snapshot.snapshotId() + "\"}"
                ),
                new CaptureSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        "capture-device",
                        1,
                        Sha256Hash.ofUtf8("source-before"),
                        snapshot.snapshotId().toString()
                ),
                -600
        );
    }

    private void seedLiveProfile(SqliteConnectionFactory connections)
            throws Exception {
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

    private OperationEnvelope operation(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteOperationStore(connection)
                    .find(OPERATION)
                    .orElseThrow();
        }
    }

    private CompanionLifecycle lifecycle(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private boolean snapshotExists(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqlitePersistenceTransactionContext(connection)
                    .snapshots()
                    .findById(request().snapshot().snapshotId())
                    .isPresent();
        }
    }

    private static final class ReceiptBoundary
            implements CompanionCaptureLiveBoundary {
        private final AtomicInteger resolutions = new AtomicInteger();
        private final AtomicInteger sourceMutations = new AtomicInteger();
        private final AtomicInteger targetRetirements = new AtomicInteger();
        private boolean receiptPresent;
        private boolean targetRetired;

        @Override
        public java.util.concurrent.CompletionStage<LiveOperationResult>
        applyOrResolve(
                CompanionCaptureRequest capture,
                OperationEnvelope operation
        ) {
            resolutions.incrementAndGet();
            if (!receiptPresent) {
                sourceMutations.incrementAndGet();
                receiptPresent = true;
            }
            if (!targetRetired) {
                targetRetirements.incrementAndGet();
                targetRetired = true;
            }
            return LiveOperationResult.confirmed(
                    "capture_receipt_and_target_retirement_confirmed"
            ).completed();
        }
    }
}
