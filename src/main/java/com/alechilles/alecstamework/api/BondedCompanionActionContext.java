package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
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

        /** Finds durable evidence of an earlier charge for this operation. */
        default @Nullable ChargeReceipt findCharge(
                @Nonnull String operationId,
                @Nonnull String itemId,
                int quantity) {
            return null;
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
    }

    /** Compensation authority retained until the durable mutation resolves. */
    public interface ChargeReceipt {
        /** Operation identity this receipt can compensate. */
        @Nonnull String operationId();

        /** Whether this receipt was recovered instead of newly charged. */
        default boolean replayed() { return false; }

        /** Whether a rejected mutation already entered durable compensation. */
        default boolean compensationPending() { return false; }

        /** Restores the exact committed charge; repeated calls must be safe. */
        boolean refund();

        /** Releases durable charge evidence after the mutation commits. */
        default boolean complete() { return true; }
    }
}
