package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at alias lease/promotion transaction boundaries. */
final class AliasRotationProcessCrashChild {
    static final int HALT_EXIT_CODE = 83;
    static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    static final NpcAlias OLD_ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000002");
    static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId OLD_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000002");

    private AliasRotationProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path marker = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        SqliteConnectionFactory connections = new SqliteConnectionFactory(database);
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
                        if (boundary == Boundary.PROMOTION_UNCOMMITTED && number == 3) {
                            halt(marker, boundary);
                        }
                    }
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED) {
                        int number = commitNumber.get();
                        if ((boundary == Boundary.LEASE_COMMITTED && number == 1)
                                || (boundary == Boundary.PROMOTION_COMMITTED
                                && number == 3)) {
                            halt(marker, boundary);
                        }
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(CompanionAliasRotationDefinition.INSTANCE)
                ),
                units
        );
        SqliteCompanionAliasRotationOperations rotations =
                new SqliteCompanionAliasRotationOperations(
                        engine,
                        new SqliteOperationPublisher(
                                engine,
                                new SqliteOperationEvidenceReader(reads),
                                new ProjectionCoordinator(
                                        new SqliteProjectionGateway(reads, units),
                                        ProjectionRetryPolicy.DEFAULT,
                                        () -> -5_000
                                ),
                                () -> -5_000
                        ),
                        () -> -5_000,
                        List.of()
                );
        rotations.submit(
                OPERATION,
                new IdempotencyKey("alias-process-crash"),
                new CompanionAliasRotation(PROFILE, TARGET_ALIAS, -9_000),
                (rotation, operation) -> CompanionAliasLiveBoundary.Result.confirmed()
        ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        throw new IllegalStateException("Alias crash boundary was not reached");
    }

    private static void seed(SqliteConnectionFactory connections) throws Exception {
        try (java.sql.Connection connection = connections.openWriterConnection()) {
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
                    LifecycleState.UNLOADED,
                    LifecycleLocation.none(),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    ReconciliationGeneration.INITIAL,
                    null
            ));
            transaction.operations().prepare(new PreparedOperation(
                    OLD_OPERATION,
                    new IdempotencyKey("old-alias"),
                    new OperationKind("alias_seed"),
                    1,
                    "{}",
                    "alias_seed",
                    null,
                    List.of(),
                    -10_000
            ));
            transaction.identities().leaseAlias(
                    PROFILE,
                    OLD_ALIAS,
                    OLD_OPERATION,
                    -10_000
            );
            transaction.identities().promoteAlias(
                    OLD_ALIAS,
                    OLD_OPERATION,
                    -10_000
            );
            connection.commit();
        }
    }

    private static void halt(Path marker, Boundary boundary) throws Exception {
        Files.writeString(marker, boundary.name());
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    enum Boundary {
        LEASE_COMMITTED,
        PROMOTION_UNCOMMITTED,
        PROMOTION_COMMITTED
    }
}
