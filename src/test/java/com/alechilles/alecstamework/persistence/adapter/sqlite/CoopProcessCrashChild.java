package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at coop capture and release durable commit boundaries. */
final class CoopProcessCrashChild {
    static final int HALT_EXIT_CODE = 91;
    static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000001");
    static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop", 10, 64, 20, 0);
    private static final String PAYLOAD = "{\"health\":100}";

    private CoopProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path haltMarker = Path.of(args[2]).toAbsolutePath().normalize();
        Path liveReceipt = Path.of(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seed(connections, boundary.kind());
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
                        if (!boundary.committed() && commit == 3) {
                            halt(haltMarker, boundary);
                        }
                    } else if (boundary.committed() && commits.get() == 3) {
                        halt(haltMarker, boundary);
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        Operations operations = operations(writer, reads);
        if (boundary.kind() == Kind.CAPTURE) {
            operations.capture().submit(
                    OPERATION,
                    new IdempotencyKey("coop-capture-process-crash"),
                    captureRequest(),
                    (request, operation) -> {
                        Files.writeString(liveReceipt, "capture");
                        return LiveOperationResult.confirmed(
                                "retirement_receipt_confirmed"
                        ).completed();
                    }
            ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } else {
            operations.release().submit(
                    OPERATION,
                    new IdempotencyKey("coop-release-process-crash"),
                    releaseRequest(),
                    (request, operation) -> {
                        Files.writeString(liveReceipt, "release");
                        return LiveOperationResult.confirmed(
                                "spawn_receipt_confirmed"
                        ).completed();
                    }
            ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
        throw new IllegalStateException("Coop crash boundary was not reached");
    }

    static Operations operations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads
    ) {
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionCoopCaptureDefinition.INSTANCE,
                        CompanionCoopReleaseDefinition.INSTANCE
                )),
                units
        );
        SqliteOperationPublisher publisher = new SqliteOperationPublisher(
                engine,
                new SqliteOperationEvidenceReader(reads),
                new ProjectionCoordinator(
                        new SqliteProjectionGateway(reads, units),
                        ProjectionRetryPolicy.DEFAULT,
                        () -> -400
                ),
                () -> -400
        );
        return new Operations(
                new SqliteCompanionCoopCaptureOperations(
                        engine, publisher, () -> -400, List.of()
                ),
                new SqliteCompanionCoopReleaseOperations(
                        engine, publisher, () -> -400, List.of()
                )
        );
    }

    static CompanionCoopCaptureRequest captureRequest() {
        return new CompanionCoopCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                SLOT,
                snapshot(true),
                new CoopCaptureSourceEvidence(
                        SOURCE_ALIAS, "world", "capture-process-receipt"
                ),
                -600
        );
    }

    static CompanionCoopReleaseRequest releaseRequest() {
        return new CompanionCoopReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                residency(),
                snapshot(true),
                TARGET_ALIAS,
                "world-two",
                "release-process-receipt",
                -600
        );
    }

    private static void seed(
            SqliteConnectionFactory connections,
            Kind kind
    ) throws Exception {
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
            insertCurrentAlias(connection);
            transaction.coops().registerSlot(CoopSlot.unoccupied(SLOT));
            if (kind == Kind.RELEASE) {
                seedResidency(connection, transaction);
            }
            connection.commit();
        }
    }

    private static void seedResidency(
            Connection connection,
            SqlitePersistenceTransactionContext transaction
    ) throws Exception {
        CompanionLifecycle unloaded = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                new LifecycleRevision(1),
                null,
                -9_500,
                ReconciliationGeneration.INITIAL,
                null
        );
        transaction.lifecycles().transition(new LifecycleTransition(
                LifecycleRevision.INITIAL, null, unloaded
        ));
        transaction.snapshots().replaceCurrent(snapshot(true));
        transaction.lifecycles().transition(new LifecycleTransition(
                unloaded.revision(),
                null,
                new CompanionLifecycle(
                        PROFILE,
                        OWNER,
                        LifecycleState.COOP,
                        LifecycleLocation.keyed(
                                com.alechilles.alecstamework.companion.lifecycle
                                        .LifecycleLocationKind.COOP_SLOT,
                                SLOT.toString()
                        ),
                        new LifecycleRevision(2),
                        null,
                        -9_000,
                        ReconciliationGeneration.INITIAL,
                        null
                )
        ));
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_residency(
                    coop_key, profile_id, housed_npc_uuid, snapshot_id,
                    captured_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, -9000, -9000)
                """)) {
            statement.setString(1, SLOT.toString());
            statement.setString(2, PROFILE.toString());
            statement.setString(3, SOURCE_ALIAS.toString());
            statement.setString(4, SNAPSHOT.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_slot SET residency_revision = 1 WHERE coop_key = ?
                """)) {
            statement.setString(1, SLOT.toString());
            statement.executeUpdate();
        }
    }

    private static void insertCurrentAlias(Connection connection)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_alias(
                    npc_uuid, profile_id, alias_generation, alias_state,
                    lease_operation_id, mapped_at_ms, retired_at_ms
                ) VALUES (?, ?, 0, 'CURRENT', NULL, -10000, NULL)
                """)) {
            statement.setString(1, SOURCE_ALIAS.toString());
            statement.setString(2, PROFILE.toString());
            statement.executeUpdate();
        }
    }

    private static CoopResidency residency() {
        return new CoopResidency(
                SLOT, PROFILE, SOURCE_ALIAS, SNAPSHOT, -9_000, -9_000
        );
    }

    private static CompanionSnapshot snapshot(boolean current) {
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                1,
                PAYLOAD,
                Sha256Hash.ofUtf8(PAYLOAD),
                new LifecycleRevision(1),
                current,
                -9_500
        );
    }

    private static void halt(Path marker, Boundary boundary)
            throws Exception {
        Files.writeString(marker, boundary.name());
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    record Operations(
            SqliteCompanionCoopCaptureOperations capture,
            SqliteCompanionCoopReleaseOperations release
    ) {
    }

    enum Kind {
        CAPTURE,
        RELEASE
    }

    enum Boundary {
        CAPTURE_DURABLE_UNCOMMITTED(Kind.CAPTURE, false),
        CAPTURE_DURABLE_COMMITTED(Kind.CAPTURE, true),
        RELEASE_DURABLE_UNCOMMITTED(Kind.RELEASE, false),
        RELEASE_DURABLE_COMMITTED(Kind.RELEASE, true);

        private final Kind kind;
        private final boolean committed;

        Boundary(Kind kind, boolean committed) {
            this.kind = kind;
            this.committed = committed;
        }

        Kind kind() {
            return kind;
        }

        boolean committed() {
            return committed;
        }
    }
}
