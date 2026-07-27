package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Live, caller-supplied placement and payment authority for one panel action. */
public record BondedCompanionActionContext(
        @Nullable CompanionSpawnPlacement summonPlacement,
        @Nullable Inventory inventory
) {
    /** Exact live inventory boundary; implementations must consume atomically. */
    public interface Inventory {
        int availableQuantity(String itemId);

        /**
         * Returns source quantity plus an exact durable reservation for this op.
         * Implementations that do not retain escrow may use the source aggregate.
         */
        default int availableQuantity(
                @Nonnull String operationId,
                @Nonnull String itemId,
                int quantity) {
            return availableQuantity(itemId);
        }

        /** Finds durable evidence when current policy details are unavailable. */
        default @Nullable ChargeReceipt findCharge(
                @Nonnull String operationId) {
            return null;
        }

        /** Policy-independent terminal recovery without blocking a world thread. */
        default CompletionStage<ChargeReceipt> findChargeAsync(
                @Nonnull String operationId) {
            return CompletableFuture.completedFuture(findCharge(operationId));
        }

        /** Finds durable evidence of an earlier charge for this operation. */
        default @Nullable ChargeReceipt findCharge(
                @Nonnull String operationId,
                @Nonnull String itemId,
                int quantity) {
            return null;
        }

        /** Asynchronously finds durable evidence without blocking a world thread. */
        default CompletionStage<ChargeReceipt> findChargeAsync(
                @Nonnull String operationId,
                @Nonnull String itemId,
                int quantity) {
            return CompletableFuture.completedFuture(
                    findCharge(operationId, itemId, quantity));
        }

        /**
         * Charges one operation and returns its exact compensation receipt.
         *
         * <p>The receipt must restore only this charge, atomically and
         * idempotently. A {@code null} result means no charge was committed.</p>
         */
        @Nullable ChargeReceipt consumeExact(
                @Nonnull String operationId,
                @Nonnull String itemId,
                int quantity);

        /**
         * Durably reserves one exact charge before completing this stage.
         *
         * <p>Production player inventories use this seam to await their player
         * save barrier. Synchronous test and integration inventories retain the
         * legacy adapter through the default implementation.</p>
         */
        default CompletionStage<ChargeReceipt> consumeExactAsync(
                @Nonnull String operationId,
                @Nonnull String itemId,
                int quantity) {
            return CompletableFuture.completedFuture(
                    consumeExact(operationId, itemId, quantity));
        }
    }

    /** Compensation authority retained until the durable mutation resolves. */
    public interface ChargeReceipt {
        /** Operation identity this receipt can compensate. */
        @Nonnull String operationId();

        /** Exact item identity proving a full prepared escrow claim, if known. */
        default @Nullable String itemId() { return null; }

        /** Exact item quantity proving a full prepared escrow claim, if known. */
        default int quantity() { return 0; }

        /** Whether this receipt was recovered instead of newly charged. */
        default boolean replayed() { return false; }

        /** Whether a rejected mutation already entered durable compensation. */
        default boolean compensationPending() { return false; }

        /** Ambiguous historical evidence that must never mint or recharge. */
        default boolean quarantined() { return false; }

        /** Whether a terminal rejection proves this marker can be discarded. */
        default boolean terminalRejectionCleanupSafe() { return false; }

        /** Restores the exact committed charge; repeated calls must be safe. */
        boolean refund();

        /** Restores and durably saves only this operation's reserved charge. */
        default CompletionStage<Boolean> refundAsync() {
            return CompletableFuture.completedFuture(refund());
        }

        /** Releases durable charge evidence after the mutation commits. */
        default boolean complete() { return true; }

        /** Consumes and durably releases only this operation's reserved charge. */
        default CompletionStage<Boolean> completeAsync() {
            return CompletableFuture.completedFuture(complete());
        }
    }
}
