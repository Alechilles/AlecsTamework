package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import java.util.List;
import java.util.Objects;

/** Authenticates terminal SQLite evidence against one exact escrow payment. */
final class BondedCompanionRevivePaymentVerifier {
    private final BondedCompanionStore store;

    BondedCompanionRevivePaymentVerifier(BondedCompanionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    Verification verifyTerminal(
            BondedCompanionOperationProbe probe,
            String operationId,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> expected,
            BondedCompanionActionContext.ChargeReceipt receipt,
            long attemptedAtMs,
            long retainedUntilMs
    ) {
        if (receipt == null || !operationId.equals(operationId(receipt))) {
            return Verification.QUARANTINED;
        }
        if (historical(receipt)) {
            return probe.expectedRevision() == null
                    ? Verification.HISTORICAL_MARKER
                    : Verification.QUARANTINED;
        }
        List<BondedCompanionReviveCost> costs = costs(receipt);
        if (probe.profileId() == null || probe.expectedRevision() == null
                || costs.isEmpty()) {
            return Verification.QUARANTINED;
        }
        BondedCompanionOperation exact;
        try {
            exact = BondedCompanionRevivePaymentProof.operation(
                    probe.callerNamespace(), probe.idempotencyKey(),
                    probe.ownerUuid(), probe.rosterId(), probe.profileId(),
                    costs, attemptedAtMs, retainedUntilMs);
        } catch (RuntimeException | LinkageError invalid) {
            return Verification.QUARANTINED;
        }
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> replay;
        try {
            replay = store.reviveProfile(
                    exact, probe.expectedRevision(), attemptedAtMs);
        } catch (RuntimeException | LinkageError failure) {
            return Verification.RETRY_REQUIRED;
        }
        if (replay.code()
                == BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT) {
            return Verification.QUARANTINED;
        }
        return replay.replayed() && sameTerminal(expected, replay)
                ? Verification.VERIFIED : Verification.RETRY_REQUIRED;
    }

    private boolean sameTerminal(
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> expected,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> actual
    ) {
        return expected.code() == actual.code()
                && Objects.equals(expected.reason(), actual.reason())
                && Objects.equals(expected.value(), actual.value());
    }

    private String operationId(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.operationId();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private List<BondedCompanionReviveCost> costs(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return List.copyOf(receipt.costs());
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    private boolean historical(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.historicalPaymentMarker();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    enum Verification {
        VERIFIED,
        HISTORICAL_MARKER,
        QUARANTINED,
        RETRY_REQUIRED
    }
}
