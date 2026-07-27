package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;

/** Exact movement boundary between visible inventory and hidden escrow. */
interface BondedCompanionEscrowTransfer {
    int availableQuantity(CombinedItemContainer source, String itemId);

    void reserveRemaining(
            CombinedItemContainer source,
            TameworkBondedReviveEscrowComponent escrow,
            int remaining);

    boolean restore(
            CombinedItemContainer source,
            TameworkBondedReviveEscrowComponent escrow);
}
