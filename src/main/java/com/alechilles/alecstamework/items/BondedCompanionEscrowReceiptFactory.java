package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Builds recipe-aware receipts without coupling escrow reservation to settlement. */
final class BondedCompanionEscrowReceiptFactory {
    private final BondedCompanionEscrowSettlementCoordinator settlements;
    private final HytaleBondedCompanionLegacyPaymentAdapter legacyPayments;

    BondedCompanionEscrowReceiptFactory(
            BondedCompanionEscrowSettlementCoordinator settlements,
            HytaleBondedCompanionLegacyPaymentAdapter legacyPayments
    ) {
        this.settlements = Objects.requireNonNull(settlements, "settlements");
        this.legacyPayments = Objects.requireNonNull(
                legacyPayments, "legacyPayments");
    }

    BondedCompanionActionContext.ChargeReceipt escrow(
            String operationId, List<BondedCompanionReviveCost> costs,
            boolean claimPrepared, boolean compensationPending, boolean replayed
    ) {
        return BondedCompanionChargeReceipts.escrow(operationId, costs,
                claimPrepared, compensationPending, replayed,
                () -> settle(operationId, costs, false),
                () -> settle(operationId, costs, true));
    }

    BondedCompanionActionContext.ChargeReceipt escrow(
            String operationId, String itemId, int quantity,
            boolean claimPrepared, boolean compensationPending, boolean replayed
    ) {
        return escrow(operationId, List.of(new BondedCompanionReviveCost(
                itemId, quantity)), claimPrepared, compensationPending, replayed);
    }

    BondedCompanionActionContext.ChargeReceipt legacy(
            String operationId, String itemId, int quantity, boolean compensated
    ) {
        return BondedCompanionChargeReceipts.legacy(operationId, compensated,
                () -> legacyPayments.release(operationId, itemId, quantity));
    }

    BondedCompanionActionContext.ChargeReceipt legacyIdentity(
            String operationId, boolean compensated) {
        return BondedCompanionChargeReceipts.legacy(operationId, compensated,
                () -> legacyPayments.releaseByIdentity(operationId));
    }

    BondedCompanionActionContext.ChargeReceipt quarantined(String operationId) {
        return BondedCompanionChargeReceipts.quarantined(operationId);
    }

    private CompletionStage<Boolean> settle(
            String operationId, List<BondedCompanionReviveCost> costs,
            boolean consume) {
        return consume ? settlements.consume(operationId, costs)
                : settlements.refund(operationId, costs);
    }
}
