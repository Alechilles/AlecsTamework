package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at population reservation and durable commit boundaries. */
final class OwnerPopulationProcessCrashChild {
    static final int HALT_EXIT_CODE = 92;
    static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    static final IdempotencyKey IDEMPOTENCY =
            new IdempotencyKey("owner-population-process-crash");

    private OwnerPopulationProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path marker = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
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
                                && number == 2) {
                            halt(marker, boundary);
                        }
                    }
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED) {
                        int number = commitNumber.get();
                        if ((boundary == Boundary.PREPARE_COMMITTED
                                && number == 1)
                                || (boundary == Boundary.DURABLE_COMMITTED
                                && number == 2)) {
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
                        OwnerPopulationTransitionDefinition.INSTANCE
                )),
                units
        );
        SqliteOwnerPopulationTransitionOperations operations =
                new SqliteOwnerPopulationTransitionOperations(
                        new SqliteDatabaseOperationCoordinator(
                                engine,
                                new SqliteOperationEvidenceReader(reads),
                                new ProjectionCoordinator(
                                        new SqliteProjectionGateway(
                                                reads,
                                                units
                                        ),
                                        ProjectionRetryPolicy.DEFAULT,
                                        () -> -5_000
                                ),
                                () -> -5_000
                        ),
                        List.of()
                );
        operations.submit(OPERATION, IDEMPOTENCY, transition())
                .completion().toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
        throw new IllegalStateException(
                "Population crash boundary was not reached"
        );
    }

    static OwnerPopulationTransitionRequest transition() {
        return new OwnerPopulationTransitionRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                null,
                null,
                OWNER,
                "world-a",
                2,
                2,
                -5_000
        );
    }

    private static void seed(SqliteConnectionFactory connections)
            throws Exception {
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Crash Companion",
                    "role",
                    null,
                    null,
                    "world-a",
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    null,
                    LifecycleState.UNRESOLVED,
                    LifecycleLocation.unresolved(),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    ReconciliationGeneration.INITIAL,
                    null,
                    null
            ));
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
        DURABLE_UNCOMMITTED,
        DURABLE_COMMITTED
    }
}

