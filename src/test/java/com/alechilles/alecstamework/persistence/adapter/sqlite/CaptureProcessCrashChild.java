package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
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
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at capture-specific database and external-effect seams. */
final class CaptureProcessCrashChild {
    static final int HALT_EXIT_CODE = 89;
    static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000001");

    private CaptureProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path haltMarker = Path.of(args[2]).toAbsolutePath().normalize();
        Path captureReceipt = Path.of(args[3]).toAbsolutePath().normalize();
        Path refundReceipt = Path.of(args[4]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seed(connections);
        AtomicInteger commits = new AtomicInteger();
        SqliteSingleWriter writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, operationId) -> {
                    if (!OPERATION.equals(operationId)
                            || checkpoint != PersistenceCheckpoint.BEFORE_COMMIT
                            && checkpoint != PersistenceCheckpoint.COMMIT_RETURNED) {
                        return;
                    }
                    if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT) {
                        int commit = commits.incrementAndGet();
                        if ((boundary == Boundary.CAPTURE_DURABLE_UNCOMMITTED
                                && commit == 3)
                                || (boundary
                                == Boundary.REFUND_DURABLE_UNCOMMITTED
                                && commit == 4)) {
                            halt(haltMarker, boundary);
                        }
                    } else {
                        int commit = commits.get();
                        if ((boundary == Boundary.CAPTURE_DURABLE_COMMITTED
                                && commit == 3)
                                || (boundary
                                == Boundary.REFUND_CLAIM_COMMITTED
                                && commit == 3)
                                || (boundary
                                == Boundary.REFUND_DURABLE_COMMITTED
                                && commit == 4)) {
                            halt(haltMarker, boundary);
                        }
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        SqliteCompanionCaptureOperations captures = operations(
                writer,
                reads,
                () -> {
                    writeReceipt(refundReceipt, "refund");
                    return LiveOperationResult.confirmed(
                            "refund_receipt_confirmed"
                    );
                }
        );
        captures.submit(
                OPERATION,
                new IdempotencyKey("capture-process-crash"),
                request(),
                (capture, operation) -> {
                    writeReceipt(captureReceipt, "capture");
                    return boundary.compensating()
                            ? LiveOperationResult.compensate(
                                    "source_spent_target_proven_live",
                                    null
                            ).completed()
                            : LiveOperationResult.confirmed(
                                    "capture_receipt_and_target_retirement_confirmed"
                            ).completed();
                }
        ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        throw new IllegalStateException("Capture crash boundary was not reached");
    }

    static CompanionCaptureRequest request() {
        String snapshotJson = "{\"capturedAtMs\":-500}";
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse(
                        "50000000-0000-0000-0000-000000000001"
                ),
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                1,
                snapshotJson,
                Sha256Hash.ofUtf8(snapshotJson),
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
                        Sha256Hash.ofUtf8("before"),
                        snapshot.snapshotId().toString()
                ),
                -600
        );
    }

    static SqliteCompanionCaptureOperations operations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads,
            RefundEffect refund
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
                (claim, operation) -> refund.apply().completed(),
                List.of()
        );
    }

    private static void seed(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Crash Companion",
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

    private static void writeReceipt(Path path, String evidence)
            throws Exception {
        Files.writeString(path, evidence);
    }

    private static void halt(Path marker, Boundary boundary)
            throws Exception {
        Files.writeString(marker, boundary.name());
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    @FunctionalInterface
    interface RefundEffect {
        LiveOperationResult apply() throws Exception;
    }

    enum Boundary {
        CAPTURE_DURABLE_UNCOMMITTED(false),
        CAPTURE_DURABLE_COMMITTED(false),
        REFUND_CLAIM_COMMITTED(true),
        REFUND_DURABLE_UNCOMMITTED(true),
        REFUND_DURABLE_COMMITTED(true);

        private final boolean compensating;

        Boundary(boolean compensating) {
            this.compensating = compensating;
        }

        boolean compensating() {
            return compensating;
        }
    }
}
