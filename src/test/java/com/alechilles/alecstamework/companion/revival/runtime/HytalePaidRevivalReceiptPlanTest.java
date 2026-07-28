package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalReceiptCleanupGateway.CleanupAction;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalReceiptPlan.ReleaseStatus;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptStatus;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exact physical and virtual receipt contracts for Hytale paid revival. */
class HytalePaidRevivalReceiptPlanTest {

    @Test
    void emptyRecipeUsesVirtualReceiptWithoutConsumingCapacity() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(true);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);

        assertEquals(ReceiptStatus.EXACT, plan.probe(null).status());
        assertEquals(
                ReleaseStatus.ABSENT,
                plan.releaseNoCharge(null).status()
        );
        assertNull(
                plan.installPending(null)
                        .receiptFor(request.chargeReceiptKey())
        );
        assertNull(
                plan.installPending(null)
                        .receiptFor(request.chargeReceiptKey() + ":pending")
        );
    }

    @Test
    void alreadyAbsentCleanupStillRequiresTheDurabilityFence() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);

        assertEquals(
                CleanupAction.PERSIST_AND_VERIFY,
                HytalePaidRevivalReceiptCleanupGateway.cleanupAction(
                        plan.releaseCanonical(null)
                )
        );
    }

    @Test
    void noChargeCleanupRemovesOnlyExactPendingReceipt() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);
        InventoryOperationReceipt unrelated = new InventoryOperationReceipt(
                "other-operation",
                operation.operationId(),
                operation.kind(),
                Sha256Hash.ofUtf8("other"),
                request.requestedAtMs() - 1
        );
        TameworkInventoryOperationReceiptsComponent current =
                plan.installPending(
                        new TameworkInventoryOperationReceiptsComponent()
                                .withReceipt(unrelated)
                );

        HytalePaidRevivalReceiptPlan.ReleasePlan release =
                plan.releaseNoCharge(current);

        assertEquals(ReleaseStatus.MUTATED, release.status());
        assertNotNull(release.receipts().receiptFor("other-operation"));
        assertNull(release.receipts().receiptFor(
                request.chargeReceiptKey() + ":pending"
        ));
        assertEquals(
                ReleaseStatus.ABSENT,
                plan.releaseNoCharge(release.receipts()).status()
        );
    }

    @Test
    void nonemptyChargedReceiptCannotBeErasedAsNoCharge() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);
        TameworkInventoryOperationReceiptsComponent charged =
                plan.markCharged(plan.installPending(null));

        HytalePaidRevivalReceiptPlan.ReleasePlan release =
                plan.releaseNoCharge(charged);

        assertEquals(ReleaseStatus.CONFLICT, release.status());
        assertNotNull(charged.receiptFor(request.chargeReceiptKey()));
    }

    @Test
    void canonicalCleanupRemovesExactNonemptyChargedReceipt() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);
        TameworkInventoryOperationReceiptsComponent charged =
                plan.markCharged(plan.installPending(null));

        HytalePaidRevivalReceiptPlan.ReleasePlan release =
                plan.releaseCanonical(charged);

        assertEquals(ReleaseStatus.MUTATED, release.status());
        assertNull(release.receipts().receiptFor(
                request.chargeReceiptKey()
        ));
        assertEquals(
                ReleaseStatus.ABSENT,
                plan.releaseCanonical(release.receipts()).status()
        );
    }

    @Test
    void conflictingSameKeyReceiptIsNeverRemoved() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        InventoryOperationReceipt conflicting =
                new InventoryOperationReceipt(
                        request.chargeReceiptKey() + ":pending",
                        operation.operationId(),
                        operation.kind(),
                        Sha256Hash.ofUtf8("different-plan"),
                        request.requestedAtMs()
                );
        TameworkInventoryOperationReceiptsComponent current =
                new TameworkInventoryOperationReceiptsComponent()
                        .withReceipt(conflicting);
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);

        HytalePaidRevivalReceiptPlan.ReleasePlan release =
                plan.releaseNoCharge(current);

        assertEquals(ReleaseStatus.CONFLICT, release.status());
        assertEquals(
                conflicting,
                current.receiptFor(conflicting.receiptKey())
        );
    }

    @Test
    void emptyRecipeCleansLegacyPendingAndChargedReceipts() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(true);
        OperationEnvelope operation =
                PaidRevivalWorldTestFixture.operation(request);
        Sha256Hash hash = Sha256Hash.ofUtf8(operation.payloadJson());
        TameworkInventoryOperationReceiptsComponent legacy =
                new TameworkInventoryOperationReceiptsComponent()
                        .withReceipt(receipt(
                                request.chargeReceiptKey() + ":pending",
                                request,
                                operation,
                                hash
                        ))
                        .withReceipt(receipt(
                                request.chargeReceiptKey(),
                                request,
                                operation,
                                hash
                        ));
        HytalePaidRevivalReceiptPlan plan =
                new HytalePaidRevivalReceiptPlan(request, operation);

        assertEquals(ReceiptStatus.ABSENT, plan.probe(legacy).status());
        HytalePaidRevivalReceiptPlan.ReleasePlan release =
                plan.releaseNoCharge(legacy);

        assertEquals(ReleaseStatus.MUTATED, release.status());
        assertNull(release.receipts().receiptFor(
                request.chargeReceiptKey() + ":pending"
        ));
        assertNull(release.receipts().receiptFor(
                request.chargeReceiptKey()
        ));
    }

    private InventoryOperationReceipt receipt(
            String key,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            Sha256Hash hash
    ) {
        return new InventoryOperationReceipt(
                key,
                operation.operationId(),
                operation.kind(),
                hash,
                request.requestedAtMs()
        );
    }
}
