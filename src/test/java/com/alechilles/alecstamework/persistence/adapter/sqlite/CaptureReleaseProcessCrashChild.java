package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
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

/** Forked JVM that halts across capture release's two live receipts and durable commit. */
final class CaptureReleaseProcessCrashChild {
    static final int HALT_EXIT_CODE = 91;
    static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000011");
    static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000011");
    static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000012");
    static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000011");
    static final UUID ACTOR =
            UUID.fromString("40000000-0000-0000-0000-000000000011");
    static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000011");
    static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000011");

    private CaptureReleaseProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path haltMarker = Path.of(args[2]).toAbsolutePath().normalize();
        Path inventoryReceipt =
                Path.of(args[3]).toAbsolutePath().normalize();
        Path spawnReceipt = Path.of(args[4]).toAbsolutePath().normalize();
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
                            && checkpoint
                            != PersistenceCheckpoint.COMMIT_RETURNED) {
                        return;
                    }
                    if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT) {
                        int commit = commits.incrementAndGet();
                        if (boundary == Boundary.DURABLE_UNCOMMITTED
                                && commit == 3) {
                            halt(haltMarker, boundary);
                        }
                    } else if (boundary == Boundary.DURABLE_COMMITTED
                            && commits.get() == 3) {
                        halt(haltMarker, boundary);
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        operations(writer, reads).submit(
                OPERATION,
                new IdempotencyKey("capture-release-process-crash"),
                request(),
                (release, operation) -> {
                    Files.writeString(inventoryReceipt, "inventory");
                    if (boundary == Boundary.AFTER_INVENTORY_RECEIPT) {
                        halt(haltMarker, boundary);
                    }
                    Files.writeString(spawnReceipt, "spawn");
                    return LiveOperationResult.confirmed(
                            "capture_release_both_receipts_confirmed"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        throw new IllegalStateException(
                "Capture release crash boundary was not reached"
        );
    }

    static SqliteCompanionCaptureReleaseOperations operations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads
    ) {
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionCaptureReleaseDefinition.INSTANCE
                )),
                units
        );
        return new SqliteCompanionCaptureReleaseOperations(
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
                List.of()
        );
    }

    static CompanionCaptureReleaseRequest request() {
        String projection = "{\"state\":\"frozen\"}";
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(1),
                snapshot(true),
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        projection,
                        Sha256Hash.ofUtf8(projection)
                ),
                new CaptureReleaseSourceEvidence(
                        ACTOR,
                        "world-two",
                        2,
                        sourceArtifact(),
                        receiptArtifact()
                ),
                TARGET_ALIAS,
                null,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "inventory-process-receipt",
                "spawn-process-receipt",
                -600
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
                    "Captured Crash Companion",
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
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            SOURCE_ALIAS.toString(), "world"
                    ),
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
                statement.setString(1, SOURCE_ALIAS.toString());
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -10_000);
                statement.executeUpdate();
            }
            transaction.snapshots().replaceCurrent(snapshot(true));
            transaction.lifecycles().transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL,
                    null,
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            LifecycleState.CAPTURED,
                            LifecycleLocation.keyed(
                                    LifecycleLocationKind.CAPTURE_ITEM,
                                    SNAPSHOT.toString()
                            ),
                            new LifecycleRevision(1),
                            null,
                            -9_500,
                            ReconciliationGeneration.INITIAL,
                            null,
                            "world"
                    )
            ));
            connection.commit();
        }
    }

    private static CompanionSnapshot snapshot(boolean current) {
        String payload = "{\"capture\":\"envelope\"}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                current,
                -9_500
        );
    }

    private static CapturedArtifact sourceArtifact() {
        return artifact(
                "capture-device-filled",
                "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                        + "\":\"" + SNAPSHOT + "\","
                        + "\"" + TameworkMetadataKeys.COMPANION_PROFILE_ID
                        + "\":\"" + PROFILE + "\","
                        + "\"" + TameworkMetadataKeys.TARGET_UUID
                        + "\":\"" + SOURCE_ALIAS + "\""
        );
    }

    private static CapturedArtifact receiptArtifact() {
        return artifact(
                "capture-device-empty",
                "\"" + TameworkMetadataKeys.CAPTURE_RELEASE_RECEIPT
                        + "\":\"inventory-process-receipt\""
        );
    }

    private static CapturedArtifact artifact(
            String itemId,
            String metadata
    ) {
        return CapturedArtifact.create(
                itemId,
                1,
                0.0D,
                0.0D,
                "{" + metadata + "}"
        );
    }

    private static void halt(Path marker, Boundary boundary)
            throws Exception {
        Files.writeString(marker, boundary.name());
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    enum Boundary {
        AFTER_INVENTORY_RECEIPT,
        DURABLE_UNCOMMITTED,
        DURABLE_COMMITTED
    }
}
