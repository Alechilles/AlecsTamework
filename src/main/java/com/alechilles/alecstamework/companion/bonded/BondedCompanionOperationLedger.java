package com.alechilles.alecstamework.companion.bonded;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable receipt history used for mutation idempotency checks. */
public final class BondedCompanionOperationLedger {
    private final Map<String, BondedCompanionOperationReceipt> receipts;

    private BondedCompanionOperationLedger(
            Map<String, BondedCompanionOperationReceipt> receipts
    ) {
        this.receipts = Collections.unmodifiableMap(
                new LinkedHashMap<>(receipts)
        );
    }

    /** Creates an empty operation history for a new canonical profile. */
    @Nonnull
    public static BondedCompanionOperationLedger empty() {
        return new BondedCompanionOperationLedger(Map.of());
    }

    /** Classifies a proposed operation ID as unused, an exact replay, or a conflict. */
    @Nonnull
    public Classification classify(
            @Nonnull BondedCompanionOperationReceipt proposed
    ) {
        Objects.requireNonNull(proposed, "proposed");
        BondedCompanionOperationReceipt existing = receipts.get(
                proposed.operationId()
        );
        if (existing == null) {
            return Classification.NEW;
        }
        return existing.equals(proposed)
                ? Classification.REPLAY : Classification.CONFLICT;
    }

    /** Returns a new ledger containing the accepted receipt. */
    @Nonnull
    public BondedCompanionOperationLedger record(
            @Nonnull BondedCompanionOperationReceipt receipt
    ) {
        Objects.requireNonNull(receipt, "receipt");
        LinkedHashMap<String, BondedCompanionOperationReceipt> next =
                new LinkedHashMap<>(receipts);
        next.remove(receipt.operationId());
        next.put(receipt.operationId(), receipt);
        return new BondedCompanionOperationLedger(next);
    }

    /** Result of comparing a proposed operation with retained receipts. */
    public enum Classification { NEW, REPLAY, CONFLICT }
}
