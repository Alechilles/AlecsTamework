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

        /** Restores the exact committed charge; repeated calls must be safe. */
        boolean refund();
    }
}
