package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptProbe;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact pending-to-charged player receipt transition for one revival.
 *
 * <p>The pending receipt is persisted before inventory mutation. Successful
 * consumption replaces it with the charged receipt at the same bounded
 * component cardinality, so a later player save proves the economic state.</p>
 */
final class HytalePaidRevivalReceiptPlan {
    private static final String PENDING_SUFFIX = ":pending";

    private final InventoryOperationReceipt pending;
    private final InventoryOperationReceipt charged;
    private final boolean emptyRecipe;

    HytalePaidRevivalReceiptPlan(
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(operation, "operation");
        Sha256Hash planHash = Sha256Hash.ofUtf8(operation.payloadJson());
        pending = new InventoryOperationReceipt(
                request.chargeReceiptKey() + PENDING_SUFFIX,
                operation.operationId(),
                operation.kind(),
                planHash,
                request.requestedAtMs()
        );
        charged = new InventoryOperationReceipt(
                request.chargeReceiptKey(),
                operation.operationId(),
                operation.kind(),
                planHash,
                request.requestedAtMs()
        );
        emptyRecipe = request.exactCost().isEmpty();
    }

    /**
     * Returns the protocol receipt state.
     *
     * <p>An empty recipe has no inventory mutation to fence, so physical
     * absence is its exact virtual receipt. This keeps no-cost revivals
     * replayable without consuming bounded player receipt capacity.</p>
     */
    @Nonnull
    ReceiptProbe probe(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        if (!emptyRecipe) {
            return probePhysical(receipts);
        }
        InventoryOperationReceipt pendingFound = find(
                receipts, pending.receiptKey()
        );
        InventoryOperationReceipt chargedFound = find(
                receipts, charged.receiptKey()
        );
        if (conflicts(pendingFound, pending)
                || conflicts(chargedFound, charged)) {
            return ReceiptProbe.conflict(null);
        }
        /*
         * Expected physical no-cost receipts are reversible leftovers. Report
         * them as absent protocol evidence so installExactReceipt performs
         * cleanup followed by the normal actor-save fence.
         */
        return pendingFound != null || chargedFound != null
                ? ReceiptProbe.absent()
                : ReceiptProbe.exact();
    }

    @Nonnull
    ReleasePlan releaseNoCharge(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        return releaseExpected(receipts, emptyRecipe);
    }

    @Nonnull
    ReleasePlan releaseCanonical(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        return releaseExpected(receipts, true);
    }

    @Nonnull
    private ReleasePlan releaseExpected(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts,
            boolean releaseCharged
    ) {
        InventoryOperationReceipt pendingFound = find(
                receipts, pending.receiptKey()
        );
        InventoryOperationReceipt chargedFound = find(
                receipts, charged.receiptKey()
        );
        if (conflicts(pendingFound, pending)
                || conflicts(chargedFound, charged)
                || !releaseCharged && chargedFound != null) {
            return ReleasePlan.conflict();
        }
        if (pendingFound == null && chargedFound == null) {
            return ReleasePlan.absent();
        }
        TameworkInventoryOperationReceiptsComponent current =
                receipts == null
                        ? new TameworkInventoryOperationReceiptsComponent()
                        : receipts;
        TameworkInventoryOperationReceiptsComponent released =
                current.withoutReceipt(pending.receiptKey());
        if (releaseCharged) {
            released = released.withoutReceipt(charged.receiptKey());
        }
        return find(released, pending.receiptKey()) == null
                && find(released, charged.receiptKey()) == null
                ? ReleasePlan.mutated(released)
                : ReleasePlan.conflict();
    }

    @Nonnull
    private ReceiptProbe probePhysical(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        InventoryOperationReceipt pendingFound = find(
                receipts, pending.receiptKey()
        );
        InventoryOperationReceipt chargedFound = find(
                receipts, charged.receiptKey()
        );
        if (conflicts(pendingFound, pending)
                || conflicts(chargedFound, charged)
                || pendingFound != null && chargedFound != null) {
            return ReceiptProbe.conflict(null);
        }
        return pendingFound != null || chargedFound != null
                ? ReceiptProbe.exact()
                : ReceiptProbe.absent();
    }

    boolean pending(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        return pending.equals(find(receipts, pending.receiptKey()))
                && find(receipts, charged.receiptKey()) == null;
    }

    boolean charged(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        return charged.equals(find(receipts, charged.receiptKey()))
                && find(receipts, pending.receiptKey()) == null;
    }

    @Nonnull
    TameworkInventoryOperationReceiptsComponent installPending(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        TameworkInventoryOperationReceiptsComponent current =
                receipts == null
                        ? new TameworkInventoryOperationReceiptsComponent()
                        : receipts;
        return emptyRecipe ? current : current.withReceipt(pending);
    }

    @Nonnull
    TameworkInventoryOperationReceiptsComponent markCharged(
            @Nonnull TameworkInventoryOperationReceiptsComponent receipts
    ) {
        if (!pending(receipts)) {
            throw new IllegalStateException(
                    "Exact pending paid-revival receipt is required"
            );
        }
        return receipts.withoutReceipt(pending.receiptKey())
                .withReceipt(charged);
    }

    @Nullable
    private InventoryOperationReceipt find(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts,
            String key
    ) {
        return receipts == null ? null : receipts.receiptFor(key);
    }

    private boolean conflicts(
            @Nullable InventoryOperationReceipt found,
            InventoryOperationReceipt expected
    ) {
        return found != null && !expected.equals(found);
    }

    enum ReleaseStatus {
        ABSENT,
        MUTATED,
        CONFLICT
    }

    record ReleasePlan(
            @Nonnull ReleaseStatus status,
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
        ReleasePlan {
            if (status == null
                    || (status == ReleaseStatus.MUTATED)
                    != (receipts != null)) {
                throw new IllegalArgumentException(
                        "Paid revival receipt release plan is inconsistent"
                );
            }
        }

        static ReleasePlan absent() {
            return new ReleasePlan(ReleaseStatus.ABSENT, null);
        }

        static ReleasePlan mutated(
                TameworkInventoryOperationReceiptsComponent receipts
        ) {
            return new ReleasePlan(ReleaseStatus.MUTATED, receipts);
        }

        static ReleasePlan conflict() {
            return new ReleasePlan(ReleaseStatus.CONFLICT, null);
        }
    }
}
