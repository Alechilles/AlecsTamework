package com.alechilles.alecstamework.persistence.compensation.runtime;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import javax.annotation.Nonnull;

/**
 * Engine-neutral state machine for one exact, receipt-addressable refund recipe.
 *
 * <p>Each recipe line owns a deterministic receipt. Existing positive receipts are resolved
 * before any add, and every write is classified by immediate exact readback.</p>
 */
public final class ReceiptFirstRefundDelivery {

    /** Applies or resolves the exact recipe against one current recipient inventory. */
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull RefundClaim claim,
            @Nonnull ReceiptInventory inventory
    ) {
        if (claim == null || inventory == null) {
            return unknown("refund_delivery_context_missing", null);
        }
        if (claim.delivered()) {
            return LiveOperationResult.confirmed(claim.deliveryEvidence());
        }
        for (int index = 0; index < claim.items().size(); index++) {
            LiveOperationResult line = applyLine(
                    claim.items().get(index),
                    lineReceipt(claim, index),
                    inventory
            );
            if (line != null) {
                return line;
            }
        }
        return LiveOperationResult.confirmed(
                "refund_receipt_confirmed:" + claim.receiptKey()
        );
    }

    private LiveOperationResult applyLine(
            RefundItem item,
            String receipt,
            ReceiptInventory inventory
    ) {
        ReceiptObservation before = observe(inventory, item, receipt);
        LiveOperationResult initial = classify(before, item.quantity());
        if (initial != null || before.quantity() == item.quantity()) {
            return initial;
        }
        int missing = item.quantity() - before.quantity();
        try {
            AddResult added = inventory.add(item.itemId(), missing, receipt);
            return classifyWrite(
                    before,
                    observe(inventory, item, receipt),
                    item.quantity(),
                    added,
                    null
            );
        } catch (Throwable failure) {
            return classifyWriteFailure(
                    before,
                    observe(inventory, item, receipt),
                    item.quantity(),
                    failure
            );
        }
    }

    private ReceiptObservation observe(
            ReceiptInventory inventory,
            RefundItem item,
            String receipt
    ) {
        try {
            ReceiptObservation observation =
                    inventory.observe(item.itemId(), receipt);
            return observation == null
                    ? ReceiptObservation.unreadable(
                            new IllegalStateException(
                                    "Refund receipt read returned null"
                            ))
                    : observation;
        } catch (Throwable failure) {
            return ReceiptObservation.unreadable(failure);
        }
    }

    private LiveOperationResult classify(
            ReceiptObservation observation,
            int expectedQuantity
    ) {
        if (observation.failure() != null) {
            return unknown(
                    "refund_receipt_read_failed",
                    observation.failure()
            );
        }
        if (observation.conflicting()
                || observation.quantity() > expectedQuantity) {
            return unknown("refund_receipt_conflict", null);
        }
        return null;
    }

    private LiveOperationResult classifyWrite(
            ReceiptObservation before,
            ReceiptObservation after,
            int expectedQuantity,
            AddResult added,
            Throwable failure
    ) {
        LiveOperationResult invalid = classify(after, expectedQuantity);
        if (invalid != null) {
            return invalid;
        }
        if (after.quantity() == expectedQuantity) {
            return null;
        }
        if (added == AddResult.REJECTED
                && after.quantity() == before.quantity()) {
            return LiveOperationResult.retryable(
                    "refund_inventory_unavailable",
                    failure
            );
        }
        return unknown("refund_write_ambiguous", failure);
    }

    private LiveOperationResult classifyWriteFailure(
            ReceiptObservation before,
            ReceiptObservation after,
            int expectedQuantity,
            Throwable failure
    ) {
        LiveOperationResult classified = classifyWrite(
                before,
                after,
                expectedQuantity,
                AddResult.REJECTED,
                failure
        );
        return classified == null
                ? null
                : classified;
    }

    @Nonnull
    public static String lineReceipt(@Nonnull RefundClaim claim, int lineIndex) {
        if (claim == null || lineIndex < 0
                || lineIndex >= claim.items().size()) {
            throw new IllegalArgumentException(
                    "Refund claim and valid line index are required"
            );
        }
        return claim.receiptKey() + "/line/" + lineIndex;
    }

    private LiveOperationResult unknown(String code, Throwable failure) {
        return LiveOperationResult.unknown(code, failure);
    }

    /** Minimal inventory contract kept independent from Hytale runtime types. */
    public interface ReceiptInventory {
        @Nonnull
        ReceiptObservation observe(
                @Nonnull String expectedItemId,
                @Nonnull String receipt
        );

        @Nonnull
        AddResult add(
                @Nonnull String itemId,
                int quantity,
                @Nonnull String receipt
        );
    }

    public enum AddResult {
        APPLIED,
        REJECTED
    }

    /** Exact readback for every stack carrying one deterministic line receipt. */
    public record ReceiptObservation(
            int quantity,
            boolean conflicting,
            Throwable failure
    ) {
        public ReceiptObservation {
            if (quantity < 0) {
                throw new IllegalArgumentException(
                        "Observed refund quantity cannot be negative"
                );
            }
            if (failure != null && (quantity != 0 || conflicting)) {
                throw new IllegalArgumentException(
                        "Unreadable receipt cannot carry inventory evidence"
                );
            }
        }

        @Nonnull
        public static ReceiptObservation readable(
                int quantity,
                boolean conflicting
        ) {
            return new ReceiptObservation(quantity, conflicting, null);
        }

        @Nonnull
        public static ReceiptObservation unreadable(@Nonnull Throwable failure) {
            return new ReceiptObservation(0, false, failure);
        }
    }
}
