package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import java.util.List;
import java.util.Objects;

/** Proves a fresh ordered revive recipe is fully available before escrow moves. */
final class BondedCompanionEscrowPreflight {
    private final BondedCompanionEscrowTransfer transfer;

    BondedCompanionEscrowPreflight(BondedCompanionEscrowTransfer transfer) {
        this.transfer = Objects.requireNonNull(transfer, "transfer");
    }

    boolean canReserveFresh(
            CombinedItemContainer source,
            List<BondedCompanionReviveCost> costs
    ) {
        for (BondedCompanionReviveCost cost : costs) {
            if (transfer.availableQuantity(source, cost.itemId())
                    < cost.quantity()) {
                return false;
            }
        }
        return true;
    }
}
