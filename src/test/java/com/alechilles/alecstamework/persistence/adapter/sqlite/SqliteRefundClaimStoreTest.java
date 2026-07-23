package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies exact, idempotent refund claims without an independent phase machine. */
class SqliteRefundClaimStoreTest {
    private static final OperationId OPERATION =
            OperationId.parse("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void initialize() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -1_000).initialize();
    }

    @Test
    void createsAndCompletesOneReceiptAddressableClaim() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            prepareOperation(transaction);
            RefundClaim claim = claim("refund:one");

            assertTrue(transaction.refunds().create(claim).applied());
            assertTrue(transaction.refunds().create(claim).applied());
            assertFalse(transaction.refunds()
                    .findByOperation(OPERATION)
                    .orElseThrow()
                    .delivered());

            assertTrue(transaction.refunds().complete(
                    OPERATION,
                    claim.receiptKey(),
                    "inventory_receipt",
                    -800
            ).applied());
            assertTrue(transaction.refunds().complete(
                    OPERATION,
                    claim.receiptKey(),
                    "inventory_receipt",
                    -700
            ).applied());

            RefundClaim delivered = transaction.refunds()
                    .findByReceipt(claim.receiptKey())
                    .orElseThrow();
            assertTrue(delivered.delivered());
            assertEquals(
                    List.of(
                            new RefundItem("capture-device", 1),
                            new RefundItem("life-essence", 3)
                    ),
                    delivered.items()
            );
            assertEquals("inventory_receipt", delivered.deliveryEvidence());
            assertEquals(-800, delivered.deliveredAtMs());
            connection.commit();
        }
    }

    @Test
    void rejectsClaimOrDeliveryEvidenceThatDoesNotMatchExactOperation()
            throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            prepareOperation(transaction);
            RefundClaim claim = claim("refund:one");
            assertTrue(transaction.refunds().create(claim).applied());

            RefundClaim conflicting = new RefundClaim(
                    claim.operationId(),
                    claim.recipientUuid(),
                    List.of(new RefundItem("different-item", 1)),
                    claim.reasonCode(),
                    claim.receiptKey(),
                    claim.claimedAtMs(),
                    null,
                    null
            );
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    transaction.refunds().create(conflicting).status()
            );
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    transaction.refunds().complete(
                            OPERATION,
                            "refund:different",
                            "evidence",
                            -800
                    ).status()
            );
            connection.rollback();
        }
    }

    @Test
    void rejectsDuplicateItemIdsBeforeAnyClaimCanBeStored() {
        assertThrows(IllegalArgumentException.class, () -> new RefundClaim(
                OPERATION,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                List.of(
                        new RefundItem("life-essence", 1),
                        new RefundItem("life-essence", 2)
                ),
                "capture_aborted",
                "refund:duplicate",
                -900,
                null,
                null
        ));
    }

    private RefundClaim claim(String receipt) {
        return new RefundClaim(
                OPERATION,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                List.of(
                        new RefundItem("capture-device", 1),
                        new RefundItem("life-essence", 3)
                ),
                "capture_aborted",
                receipt,
                -900,
                null,
                null
        );
    }

    private void prepareOperation(
            SqlitePersistenceTransactionContext transaction
    ) {
        transaction.operations().prepare(new PreparedOperation(
                OPERATION,
                new IdempotencyKey("refund-test"),
                new OperationKind("refund_test"),
                1,
                "{}",
                "refund-test",
                null,
                List.of(),
                -1_000
        ));
    }
}
