package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Live, caller-supplied placement and payment authority for one panel action. */
public record BondedCompanionActionContext(
        @Nullable BondedCompanionPlacement summonPlacement,
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

        /** Returns owned quantities for one complete ordered recipe identity. */
        default @Nonnull List<Integer> availableQuantities(
                @Nonnull String operationId,
                @Nonnull List<BondedCompanionReviveCost> costs) {
            return costs.stream().map(cost -> availableQuantity(operationId,
                    cost.itemId(), cost.quantity())).toList();
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

        /**
         * Reserves one complete ordered revive recipe under one operation ID.
         * Implementations must never split this into independent operations.
         */
        default CompletionStage<ChargeReceipt> consumeExactAsync(
                @Nonnull String operationId,
                @Nonnull List<BondedCompanionReviveCost> costs
        ) {
            if (costs.size() != 1) return CompletableFuture.completedFuture(null);
            BondedCompanionReviveCost cost = costs.getFirst();
            return consumeExactAsync(operationId, cost.itemId(), cost.quantity());
        }
    }

    /** Compensation authority retained until the durable mutation resolves. */
    public interface ChargeReceipt {
        /** Operation identity this receipt can compensate. */
        @Nonnull String operationId();

        /** Exact item identity authenticating this escrow request, if known. */
        default @Nullable String itemId() { return null; }

        /** Exact item quantity authenticating this escrow request, if known. */
        default int quantity() { return 0; }

        /** Exact immutable frozen recipe retained by the durable receipt. */
        default @Nonnull List<BondedCompanionReviveCost> costs() {
            String itemId = itemId();
            int quantity = quantity();
            return itemId == null || quantity <= 0 ? List.of()
                    : List.of(new BondedCompanionReviveCost(itemId, quantity));
        }

        /** Whether exact escrow evidence may create or resume a missing claim. */
        default boolean preparedClaimProof() {
            return !costs().isEmpty();
        }

        /** Whether this receipt was recovered instead of newly charged. */
        default boolean replayed() { return false; }

        /** Whether a rejected mutation already entered durable compensation. */
        default boolean compensationPending() { return false; }

        /** Whether this is a pre-escrow historical marker, not item escrow. */
        default boolean historicalPaymentMarker() { return false; }

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
