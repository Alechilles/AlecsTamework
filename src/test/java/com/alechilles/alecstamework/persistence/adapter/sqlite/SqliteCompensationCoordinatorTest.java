package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.file.Path;
import java.sql.Connection;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves reusable compensation and receipt retry through the shared operation phases. */
class SqliteCompensationCoordinatorTest {
    private static final OperationId OPERATION =
            OperationId.parse("10000000-0000-0000-0000-000000000001");
    private static final OperationDefinition<String> DEFINITION =
            new StringDefinition();

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteOperationEngine engine;
    private SqliteCompensationCoordinator compensations;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -1_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(DEFINITION)),
                new SqliteUnitOfWorkRunner(writer, reads)
        );
        compensations = new SqliteCompensationCoordinator(engine, () -> -800);
    }

    @AfterEach
    void tearDown() {
        writer.shutdown(Duration.ofSeconds(5));
        reads.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void retryUsesOneClaimThenCommitsPositiveDeliveryEvidence()
            throws Exception {
        OperationEnvelope applying = applyingOperation();
        RefundClaim claim = claim();
        RefundDetail detail = new RefundDetail(claim);
        RefundCompletion completion = new RefundCompletion();
        AtomicInteger attempts = new AtomicInteger();

        OperationWorkflowResult first = compensations.resume(
                applying,
                claim,
                detail,
                (refund, operation) -> attempts.getAndIncrement() == 0
                        ? LiveOperationResult.retryable(
                                "inventory_temporarily_unavailable",
                                null
                        )
                        : LiveOperationResult.confirmed(
                                "refund_receipt_confirmed"
                        ),
                completion,
                "test_refund"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.COMPENSATION_RETRYABLE,
                first.status()
        );
        assertEquals(OperationPhase.RETRYABLE, first.operation().phase());
        assertFalse(readClaim().delivered());

        OperationWorkflowResult second = compensations.resume(
                first.operation(),
                claim,
                detail,
                (refund, operation) -> {
                    attempts.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "refund_receipt_confirmed"
                    );
                },
                completion,
                "test_refund"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.COMPENSATED, second.status());
        assertEquals(OperationPhase.COMPENSATED, second.operation().phase());
        assertEquals(2, attempts.get());
        assertTrue(readClaim().delivered());
        assertEquals(
                "refund_receipt_confirmed",
                readClaim().deliveryEvidence()
        );
    }

    private OperationEnvelope applyingOperation() throws Exception {
        OperationRequest<String> request = new OperationRequest<>(
                OPERATION,
                new IdempotencyKey("compensation-test"),
                "payload",
                "test_compensation",
                null,
                List.of(),
                -1_000
        );
        OperationEnvelope prepared = committed(engine.prepare(
                DEFINITION,
                request
        ));
        return committed(engine.transition(
                prepared,
                OperationPhase.LIVE_APPLYING,
                null,
                null,
                -900
        ));
    }

    private RefundClaim claim() {
        return new RefundClaim(
                OPERATION,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "capture-device",
                1,
                "capture_aborted",
                "refund:" + OPERATION,
                -800,
                null,
                null
        );
    }

    private RefundClaim readClaim() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteRefundClaimStore(connection)
                    .findByOperation(OPERATION)
                    .orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T committed(
            SqliteUnitOfWorkRunner.Submission<T> submission
    ) throws Exception {
        return ((com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<T>)
                submission.completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)).value();
    }

    private static boolean sameClaim(RefundClaim expected, RefundClaim actual) {
        return expected.operationId().equals(actual.operationId())
                && expected.recipientUuid().equals(actual.recipientUuid())
                && expected.itemId().equals(actual.itemId())
                && expected.quantity() == actual.quantity()
                && expected.reasonCode().equals(actual.reasonCode())
                && expected.receiptKey().equals(actual.receiptKey())
                && expected.claimedAtMs() == actual.claimedAtMs();
    }

    private static final class RefundDetail
            implements PreparedCompensationDetail {
        private final RefundClaim claim;

        private RefundDetail(RefundClaim claim) {
            this.claim = claim;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                long preparedAtMs
        ) {
            if (!transaction.refunds().create(claim).applied()) {
                throw new IllegalStateException("refund_claim_rejected");
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            return transaction.refunds()
                    .findByOperation(operation.operationId())
                    .filter(found -> sameClaim(claim, found))
                    .isPresent();
        }
    }

    private static final class RefundCompletion
            implements TimedCompensatedOperationWork<RefundClaim> {
        @Override
        public void execute(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                RefundClaim claim,
                String liveEvidence,
                long compensatedAtMs
        ) {
            if (!transaction.refunds().complete(
                    operation.operationId(),
                    claim.receiptKey(),
                    liveEvidence,
                    compensatedAtMs
            ).applied()) {
                throw new IllegalStateException("refund_completion_rejected");
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                RefundClaim claim
        ) {
            return transaction.refunds()
                    .findByOperation(operation.operationId())
                    .filter(RefundClaim::delivered)
                    .filter(found -> sameClaim(claim, found))
                    .isPresent();
        }
    }

    private static final class StringDefinition
            implements OperationDefinition<String> {
        @Override
        public OperationKind kind() {
            return new OperationKind("compensation_test");
        }

        @Override
        public int payloadVersion() {
            return 1;
        }

        @Override
        public Class<String> payloadType() {
            return String.class;
        }

        @Override
        public String encode(String payload) {
            return "{\"value\":\"" + payload + "\"}";
        }

        @Override
        public String decode(String payloadJson) {
            return payloadJson;
        }
    }
}
