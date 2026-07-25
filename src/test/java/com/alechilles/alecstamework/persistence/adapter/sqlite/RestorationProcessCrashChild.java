package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
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
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at restoration's durable commit boundaries. */
final class RestorationProcessCrashChild {
    static final int HALT_EXIT_CODE = 90;
    static final ProvisioningOrigin PROVISIONING =
            new ProvisioningOrigin("hydragon", "crash-companion");
    static final ProfileId PROFILE =
            PROVISIONING.profileId();
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

    private RestorationProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path haltMarker = Path.of(args[2]).toAbsolutePath().normalize();
        Path spawnReceipt = Path.of(args[3]).toAbsolutePath().normalize();
        boolean dormant = args.length > 4
                && "DORMANT".equals(args[4]);
        Files.createDirectories(database.getParent());

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seed(connections, dormant);
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
                new IdempotencyKey("restoration-process-crash"),
                dormant ? dormantRequest() : request(),
                (restoration, operation) -> {
                    Files.writeString(spawnReceipt, "spawn");
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        throw new IllegalStateException(
                "Restoration crash boundary was not reached"
        );
    }

    static SqliteCompanionRestorationOperations operations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads
    ) {
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionRestorationDefinition.INSTANCE
                )),
                units
        );
        return new SqliteCompanionRestorationOperations(
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

    static CompanionRestorationRequest request() {
        return new CompanionRestorationRequest(
                PROFILE,
                new LifecycleRevision(1),
                LifecycleState.DEAD_REVIVABLE,
                snapshot(true),
                projection(),
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "spawn-process-receipt",
                -600
        );
    }

    static CompanionRestorationRequest dormantRequest() {
        return CompanionRestorationRequest.reviveProvisionedDormant(
                PROFILE,
                new LifecycleRevision(1),
                snapshot(true),
                -600
        );
    }

    private static RestorationProjection projection() {
        String payload = "{\"state\":\"frozen\"}";
        return new RestorationProjection(
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        payload,
                        Sha256Hash.ofUtf8(payload)
                )
        );
    }

    private static void seed(
            SqliteConnectionFactory connections,
            boolean provisioned
    )
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
            transaction.identities().retireAlias(SOURCE_ALIAS, -9_500);
            transaction.lifecycles().transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL,
                    null,
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            LifecycleState.DEAD_REVIVABLE,
                            LifecycleLocation.none(),
                            new LifecycleRevision(1),
                            null,
                            -9_500,
                            ReconciliationGeneration.INITIAL,
                            null
                    )
            ));
            if (provisioned) {
                OperationId creation = OperationId.parse(
                        "60000000-0000-0000-0000-000000000090"
                );
                transaction.operations().prepare(new PreparedOperation(
                        creation,
                        PROVISIONING.operationKey(),
                        CompanionProvisioningDefinition.KIND,
                        1,
                        "{}",
                        SqliteCompanionProvisioningOperations.FEATURE_SCOPE,
                        null,
                        List.of(OperationScope.profile(PROFILE)),
                        -9_000
                ));
                transaction.provisioning().create(
                        new ProvisioningRecord(
                                PROFILE,
                                PROVISIONING,
                                null,
                                1,
                                creation,
                                -9_000
                        )
                );
            }
            connection.commit();
        }
    }

    private static CompanionSnapshot snapshot(boolean current) {
        String payload = "{\"health\":100}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind(),
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                current,
                -9_500
        );
    }

    private static void halt(Path marker, Boundary boundary)
            throws Exception {
        Files.writeString(marker, boundary.name());
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    enum Boundary {
        DURABLE_UNCOMMITTED,
        DURABLE_COMMITTED
    }
}
