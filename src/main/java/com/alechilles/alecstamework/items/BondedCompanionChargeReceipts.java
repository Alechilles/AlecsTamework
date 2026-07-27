package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Focused receipt implementations for durable bonded-companion payments. */
final class BondedCompanionChargeReceipts {
    private BondedCompanionChargeReceipts() {
    }

    static BondedCompanionActionContext.ChargeReceipt escrow(
            String operationId,
            List<BondedCompanionReviveCost> costs,
            boolean claimPrepared,
            boolean compensationPending,
            boolean replayed,
            Supplier<CompletionStage<Boolean>> refund,
            Supplier<CompletionStage<Boolean>> complete
    ) {
        return new EscrowReceipt(
                operationId, costs, claimPrepared,
                compensationPending, replayed, refund, complete);
    }

    static BondedCompanionActionContext.ChargeReceipt legacy(
            String operationId,
            boolean compensated,
            Supplier<CompletionStage<Boolean>> complete
    ) {
        return new LegacyReceipt(operationId, compensated, complete);
    }

    static BondedCompanionActionContext.ChargeReceipt quarantined(
            String operationId) {
        return new QuarantinedReceipt(operationId);
    }

    private record EscrowReceipt(
            String operationId,
            List<BondedCompanionReviveCost> costs,
            boolean claimPrepared,
            boolean compensationPending,
            boolean replayed,
            Supplier<CompletionStage<Boolean>> refundAction,
            Supplier<CompletionStage<Boolean>> completeAction
    ) implements BondedCompanionActionContext.ChargeReceipt {
        private EscrowReceipt {
            Objects.requireNonNull(operationId, "operationId");
            costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
            if (costs.isEmpty()) throw new IllegalArgumentException(
                    "costs are required");
            Objects.requireNonNull(refundAction, "refundAction");
            Objects.requireNonNull(completeAction, "completeAction");
        }

        @Override public String itemId() {
            return costs.getFirst().itemId();
        }
        @Override public int quantity() {
            return costs.getFirst().quantity();
        }
        @Override public List<BondedCompanionReviveCost> costs() { return costs; }
        @Override public boolean preparedClaimProof() { return claimPrepared; }
        @Override public boolean refund() { return false; }
        @Override public boolean complete() { return false; }
        @Override public CompletionStage<Boolean> refundAsync() {
            return refundAction.get();
        }
        @Override public CompletionStage<Boolean> completeAsync() {
            return completeAction.get();
        }
    }

    private record LegacyReceipt(
            String operationId,
            boolean compensated,
            Supplier<CompletionStage<Boolean>> completeAction
    ) implements BondedCompanionActionContext.ChargeReceipt {
        private LegacyReceipt {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(completeAction, "completeAction");
        }

        @Override public boolean replayed() { return true; }
        @Override public boolean quarantined() { return true; }
        @Override public boolean historicalPaymentMarker() { return true; }
        @Override public boolean terminalRejectionCleanupSafe() {
            return compensated;
        }
        @Override public boolean refund() { return false; }
        @Override public boolean complete() { return false; }
        @Override public CompletionStage<Boolean> completeAsync() {
            return completeAction.get();
        }
    }

    private record QuarantinedReceipt(String operationId)
            implements BondedCompanionActionContext.ChargeReceipt {
        @Override public boolean replayed() { return true; }
        @Override public boolean quarantined() { return true; }
        @Override public boolean refund() { return false; }
        @Override public boolean complete() { return false; }
    }
}
