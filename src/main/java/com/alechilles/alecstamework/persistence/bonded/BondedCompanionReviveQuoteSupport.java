package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.util.ArrayList;
import java.util.List;

/** Resolves one revive quote from policy and exact inventory evidence. */
final class BondedCompanionReviveQuoteSupport {
    List<BondedCompanionReviveQuote.CostLine> costs(
            BondedCompanionActionRequest request,
            BondedCompanionPolicy.RevivePrice price
    ) {
        BondedCompanionActionContext.Inventory inventory = inventory(
                request.actionContext());
        if (inventory == null) return unavailable(price);
        ArrayList<BondedCompanionReviveQuote.CostLine> lines = new ArrayList<>();
        try {
            String operationId = BondedCompanionPaymentOperationId.create(
                    request.callerNamespace(), request.idempotencyKey(),
                    request.ownerUuid(), request.rosterId(), request.profileId(),
                    request.expectedRevision());
            List<Integer> owned = inventory.availableQuantities(
                    operationId, price.costs());
            for (int index = 0; index < price.costs().size(); index++) {
                BondedCompanionReviveCost cost = price.costs().get(index);
                lines.add(new BondedCompanionReviveQuote.CostLine(
                        cost.itemId(), cost.quantity(), Math.max(0,
                        owned.get(index))));
            }
            return List.copyOf(lines);
        } catch (RuntimeException | LinkageError failure) {
            return unavailable(price);
        }
    }

    private List<BondedCompanionReviveQuote.CostLine> unavailable(
            BondedCompanionPolicy.RevivePrice price
    ) {
        return price.costs().stream().map(cost ->
                new BondedCompanionReviveQuote.CostLine(
                        cost.itemId(), cost.quantity(), 0)).toList();
    }

    private BondedCompanionActionContext.Inventory inventory(
            BondedCompanionActionContext context
    ) {
        return context == null ? null : context.inventory();
    }
}
