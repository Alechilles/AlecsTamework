package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import javax.annotation.Nullable;

/** Live, caller-supplied placement and payment authority for one panel action. */
public record BondedCompanionActionContext(
        @Nullable CompanionSpawnPlacement summonPlacement,
        @Nullable Inventory inventory
) {
    /** Exact live inventory boundary; implementations must consume atomically. */
    public interface Inventory {
        int availableQuantity(String itemId);

        boolean consumeExact(String itemId, int quantity);
    }
}
