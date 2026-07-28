package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at paid-revival database and receipt seams. */
final class PaidRevivalProcessCrashChild {
    static final int HALT_EXIT_CODE = 95;
    static final OperationId OPERATION = OperationId.parse(
            "60000000-0000-0000-0000-000000000301"
    );
    static final IdempotencyKey IDEMPOTENCY =
            new IdempotencyKey("paid-revival-process-crash");

    private PaidRevivalProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = path(args[1]);
        Path haltMarker = path(args[2]);
        Receipts receipts = new Receipts(
                path(args[3]),
                path(args[4]),
                path(args[5]),
                path(args[6])
        );
        Files.createDirectories(database.getParent());

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(
                connections, () -> -10_000
        ).initialize();
        PaidRevivalTestSupport.seed(connections);

        AtomicInteger commits = new AtomicInteger();
        SqliteSingleWriter writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, operationId) -> inspectCommit(
                        boundary,
                        haltMarker,
                        commits,
                        checkpoint,
                        operationId
                ),
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        SqlitePaidRevivalOperations revivals = operations(
                writer,
                reads,
                (claim, operation) -> {
                    writeOnce(receipts.refund(), "refund");
                    if (boundary
                            == Boundary.REFUND_RECEIPT_APPLIED) {
                        halt(haltMarker, boundary);
                    }
                    return LiveOperationResult.confirmed(
                            "refund-receipt"
                    ).completed();
                }
        );
        revivals.submit(
                OPERATION,
                IDEMPOTENCY,
                PaidRevivalTestSupport.request(),
                (request, operation) -> live(
                        request, boundary, haltMarker, receipts
                ),
                (request, operation) -> {
                    writeOnce(receipts.release(), "release");
                    if (boundary
                            == Boundary.NO_CHARGE_RELEASE_APPLIED) {
                        halt(haltMarker, boundary);
                    }
                    return LiveOperationResult.confirmed(
                            "release-receipt"
                    ).completed();
                },
                com.alechilles.alecstamework.persistence.operation
                        .DurableOperationCleanupBoundary.notRequired()
        ).completion().toCompletableFuture().get(
                20, TimeUnit.SECONDS
        );
        throw new IllegalStateException(
                "Paid revival crash boundary was not reached: " + boundary
        );
    }

    static SqlitePaidRevivalOperations operations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads,
            com.alechilles.alecstamework.persistence.compensation
                    .RefundDeliveryBoundary refunds
    ) {
        SqliteUnitOfWorkRunner units =
                new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(PaidRevivalDefinition.INSTANCE)
                ),
                units
        );
        return new SqlitePaidRevivalOperations(
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
                refunds,
                List.of()
        );
    }

    private static java.util.concurrent.CompletionStage
            <PaidRevivalLiveResult> live(
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalRequest request,
            Boundary boundary,
            Path haltMarker,
            Receipts receipts
    ) {
        if (boundary.mode() == Mode.NO_CHARGE) {
            return PaidRevivalLiveResult.noCharge(
                    "no-charge-no-spawn"
            ).completed();
        }
        writeOnce(receipts.charge(), "charge");
        if (boundary.mode() == Mode.REFUND) {
            return PaidRevivalLiveResult.refundRequired(
                    request,
                    "charge-without-spawn"
            ).completed();
        }
        writeOnce(receipts.spawn(), "spawn");
        if (boundary == Boundary.LIVE_RECEIPTS_APPLIED) {
            halt(haltMarker, boundary);
        }
        return PaidRevivalLiveResult.confirmed(
                "charge-and-spawn-receipts"
        ).completed();
    }

    private static void inspectCommit(
            Boundary boundary,
            Path marker,
            AtomicInteger commits,
            PersistenceCheckpoint checkpoint,
            OperationId operationId
    ) {
        if (!OPERATION.equals(operationId)) {
            return;
        }
        if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT) {
            int number = commits.incrementAndGet();
            if (boundary.beforeCommit(number)) {
                halt(marker, boundary);
            }
            return;
        }
        if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED
                && boundary.afterCommit(commits.get())) {
            halt(marker, boundary);
        }
    }

    private static Path path(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void writeOnce(Path path, String value) {
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, value);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
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

    record Receipts(
            Path charge,
            Path spawn,
            Path release,
            Path refund
    ) {
    }

    enum Mode {
        SUCCESS,
        NO_CHARGE,
        REFUND
    }

    enum Boundary {
        PREPARE_COMMITTED(Mode.SUCCESS, 0, 1),
        LIVE_APPLYING_COMMITTED(Mode.SUCCESS, 0, 2),
        LIVE_RECEIPTS_APPLIED(Mode.SUCCESS, 0, 0),
        SUCCESS_DURABLE_UNCOMMITTED(Mode.SUCCESS, 3, 0),
        SUCCESS_DURABLE_COMMITTED(Mode.SUCCESS, 0, 3),
        NO_CHARGE_COMPENSATION_COMMITTED(Mode.NO_CHARGE, 0, 3),
        NO_CHARGE_RELEASE_APPLIED(Mode.NO_CHARGE, 0, 0),
        NO_CHARGE_DURABLE_UNCOMMITTED(Mode.NO_CHARGE, 4, 0),
        NO_CHARGE_DURABLE_COMMITTED(Mode.NO_CHARGE, 0, 4),
        REFUND_CLAIM_COMMITTED(Mode.REFUND, 0, 3),
        REFUND_RECEIPT_APPLIED(Mode.REFUND, 0, 0),
        REFUND_DURABLE_UNCOMMITTED(Mode.REFUND, 4, 0),
        REFUND_DURABLE_COMMITTED(Mode.REFUND, 0, 4);

        private final Mode mode;
        private final int beforeCommit;
        private final int afterCommit;

        Boundary(Mode mode, int beforeCommit, int afterCommit) {
            this.mode = mode;
            this.beforeCommit = beforeCommit;
            this.afterCommit = afterCommit;
        }

        Mode mode() {
            return mode;
        }

        boolean beforeCommit(int number) {
            return beforeCommit == number;
        }

        boolean afterCommit(int number) {
            return afterCommit == number;
        }
    }
}
