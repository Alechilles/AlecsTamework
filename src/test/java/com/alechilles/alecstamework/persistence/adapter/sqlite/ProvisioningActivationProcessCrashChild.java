package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at each activation protocol commit boundary. */
final class ProvisioningActivationProcessCrashChild {
    static final int HALT_EXIT_CODE = 93;
    static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("test:process-crash", "profile");
    static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000099");
    static final NpcAlias ALIAS =
            NpcAlias.parse("10000000-0000-0000-0000-000000000099");
    static final OperationId CREATION_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000098");
    static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000099");
    static final long GRANTED_AT = -5_000;
    static final long ACTIVATED_AT = -4_000;

    private ProvisioningActivationProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path marker = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(
                connections, () -> -10_000
        ).initialize();
        seed(connections);

        AtomicInteger commitNumber = new AtomicInteger();
        SqliteSingleWriter writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, operationId) -> {
                    if (!OPERATION.equals(operationId)) {
                        return;
                    }
                    if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT) {
                        int number = commitNumber.incrementAndGet();
                        if (boundary == Boundary.DURABLE_UNCOMMITTED
                                && number == 3) {
                            halt(marker, boundary);
                        }
                    }
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED) {
                        int number = commitNumber.get();
                        if (boundary == Boundary.PREPARE_COMMITTED
                                && number == 1
                                || boundary
                                == Boundary.LIVE_APPLYING_COMMITTED
                                && number == 2
                                || boundary == Boundary.DURABLE_COMMITTED
                                && number == 3) {
                            halt(marker, boundary);
                        }
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units =
                new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        ProvisioningActivationDefinition.INSTANCE
                )),
                units
        );
        ProjectionCoordinator projections = new ProjectionCoordinator(
                new SqliteProjectionGateway(reads, units),
                ProjectionRetryPolicy.DEFAULT,
                () -> ACTIVATED_AT
        );
        SqliteProvisioningActivationOperations operations =
                new SqliteProvisioningActivationOperations(
                        engine,
                        new SqliteOperationPublisher(
                                engine,
                                new SqliteOperationEvidenceReader(reads),
                                projections,
                                () -> ACTIVATED_AT
                        ),
                        () -> ACTIVATED_AT,
                        List.of()
                );
        var result = operations.submit(
                OPERATION,
                request(),
                (request, operation) ->
                        LiveOperationResult.confirmed(
                                request.spawnReceiptKey()
                        ).completed()
        ).completion().toCompletableFuture().get(
                20, TimeUnit.SECONDS
        );
        throw new IllegalStateException(
                "Activation crash boundary was not reached: "
                        + result.status(),
                result.failure()
        );
    }

    static ProvisioningActivationRequest request() {
        CompanionLifecycle before = dormant();
        CompanionLifecycle after = new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                ),
                new LifecycleRevision(1),
                null,
                ACTIVATED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
        return new ProvisioningActivationRequest(
                ORIGIN,
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        7,
                        List.of(policy()),
                        ACTIVATED_AT
                ),
                ALIAS,
                "world-a",
                "spawn:process-crash",
                null,
                ACTIVATED_AT
        );
    }

    static PopulationGroupPolicy policy() {
        return new PopulationGroupPolicy(
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                2,
                2,
                7
        );
    }

    static CompanionLifecycle dormant() {
        return new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        ORIGIN.stableKey()
                ),
                LifecycleRevision.INITIAL,
                null,
                GRANTED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private static void seed(SqliteConnectionFactory connections)
            throws Exception {
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.operations().prepare(new PreparedOperation(
                    CREATION_OPERATION,
                    new IdempotencyKey("provisioning-process-seed"),
                    new OperationKind("provisioning_seed"),
                    1,
                    "{}",
                    "provisioning",
                    null,
                    List.of(OperationScope.profile(
                            ORIGIN.profileId()
                    )),
                    GRANTED_AT
            ));
            try (var statement = connection.prepareStatement("""
                    UPDATE operation_envelope
                    SET phase = 'PUBLISHED',
                        updated_at_ms = ?,
                        durable_at_ms = ?,
                        published_at_ms = ?,
                        terminal_at_ms = ?
                    WHERE operation_id = ?
                    """)) {
                statement.setLong(1, GRANTED_AT);
                statement.setLong(2, GRANTED_AT);
                statement.setLong(3, GRANTED_AT);
                statement.setLong(4, GRANTED_AT);
                statement.setString(
                        5, CREATION_OPERATION.toString()
                );
                statement.executeUpdate();
            }
            transaction.identities().createProfile(
                    new CompanionIdentity(
                            ORIGIN.profileId(),
                            "Crash Companion",
                            "Mini",
                            null,
                            null,
                            "world-a",
                            GRANTED_AT,
                            GRANTED_AT,
                            GRANTED_AT,
                            0
                    )
            );
            transaction.lifecycles().create(dormant());
            transaction.provisioning().create(
                    new ProvisioningRecord(
                            ORIGIN.profileId(),
                            ORIGIN,
                            null,
                            7,
                            CREATION_OPERATION,
                            GRANTED_AT
                    )
            );
            transaction.populationGroups().replaceAssignment(
                    null,
                    new PopulationGroupAssignment(
                            ORIGIN.profileId(),
                            "Mini",
                            List.of(new PopulationGroupMembership(
                                    "mod:mini",
                                    PopulationGroupScope.GLOBAL
                            )),
                            7,
                            0,
                            LifecycleRevision.INITIAL,
                            1,
                            GRANTED_AT
                    )
            );
            connection.commit();
        }
    }

    private static void halt(Path marker, Boundary boundary) {
        try {
            Files.writeString(marker, boundary.name());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    enum Boundary {
        PREPARE_COMMITTED,
        LIVE_APPLYING_COMMITTED,
        DURABLE_UNCOMMITTED,
        DURABLE_COMMITTED
    }
}
