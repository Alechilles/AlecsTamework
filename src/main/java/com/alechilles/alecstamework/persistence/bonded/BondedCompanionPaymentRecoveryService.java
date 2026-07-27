package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Settles one restart-recovered escrow from isolated terminal SQLite proof. */
public final class BondedCompanionPaymentRecoveryService {
    private static final long OPERATION_RETENTION_MS =
            30L * 24L * 60L * 60L * 1000L;
    private final BondedCompanionStore store;
    private final LongSupplier clock;

    public BondedCompanionPaymentRecoveryService(
            @Nonnull BondedCompanionStore store,
            @Nonnull LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Recovers only exact, hash-validated payment evidence for one owner. */
    @Nonnull
    public CompletionStage<Outcome> recover(
            @Nonnull BondedCompanionPaymentOperationId.Identity identity,
            @Nonnull BondedCompanionActionContext.Inventory inventory
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(inventory, "inventory");
        BondedCompanionOperationProbe probe = probe(identity);
        return recover(probe, identity.operationId(), inventory);
    }

    /** Reconciles migrated terminal rows without bypassing legacy evidence. */
    @Nonnull
    public CompletionStage<Integer> recoverAwaitingWithoutEscrow(
            @Nonnull UUID ownerUuid,
            int limit,
            @Nonnull BondedCompanionActionContext.Inventory inventory
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(inventory, "inventory");
        java.util.List<BondedCompanionOperationProbe> awaiting;
        try {
            awaiting = store.listAwaitingProfilePaymentSettlements(
                    ownerUuid, limit);
        } catch (RuntimeException | LinkageError failure) {
            return completed(0);
        }
        CompletionStage<Integer> recovered = completed(0);
        for (BondedCompanionOperationProbe probe : awaiting) {
            String operationId = BondedCompanionPaymentOperationId
                    .legacyOperationKey(probe.callerNamespace(),
                            probe.idempotencyKey());
            recovered = recovered.thenCompose(count -> recover(
                    probe, operationId, inventory).thenApply(outcome ->
                    acknowledged(outcome) ? count + 1 : count));
        }
        return recovered.exceptionally(ignored -> 0);
    }

    private CompletionStage<Outcome> recover(
            BondedCompanionOperationProbe probe,
            String operationId,
            BondedCompanionActionContext.Inventory inventory
    ) {
        Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>> prior;
        try {
            prior = store.findProfileOperationByIdentity(probe);
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (prior.isEmpty()) return completed(Outcome.NO_TERMINAL_PROOF);
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal =
                prior.get();
        if (!terminal.replayed()) return completed(Outcome.NO_TERMINAL_PROOF);
        CompletionStage<BondedCompanionActionContext.ChargeReceipt> found;
        try {
            found = inventory.findChargeAsync(operationId);
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (found == null) return completed(Outcome.RETRY_REQUIRED);
        return found.thenCompose(receipt -> recover(
                operationId, probe, terminal, receipt))
                .exceptionally(ignored -> Outcome.RETRY_REQUIRED);
    }

    private CompletionStage<Outcome> recover(
            String operationId,
            BondedCompanionOperationProbe probe,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal,
            BondedCompanionActionContext.ChargeReceipt receipt
    ) {
        if (receipt == null) return completed(acknowledge(probe, terminal)
                ? Outcome.ALREADY_SETTLED : Outcome.RETENTION_PENDING);
        if (!operationId.equals(safeOperationId(receipt))) {
            return completed(Outcome.QUARANTINED);
        }
        boolean committed = terminal.code()
                == BondedCompanionStoreResult.Code.APPLIED
                && terminal.value() != null;
        boolean quarantined = safeQuarantined(receipt);
        if (quarantined && !committed
                && !safeTerminalRejectCleanup(receipt)) {
            return completed(Outcome.QUARANTINED);
        }
        CompletionStage<Boolean> settlement = settle(
                receipt, committed || quarantined);
        return settlement.thenApply(settled -> {
            if (!settled) return Outcome.RETRY_REQUIRED;
            if (!acknowledge(probe, terminal)) {
                return Outcome.RETENTION_PENDING;
            }
            return committed
                    ? Outcome.SETTLED_COMMITTED : Outcome.SETTLED_REJECTED;
        });
    }

    private CompletionStage<Boolean> settle(
            BondedCompanionActionContext.ChargeReceipt receipt,
            boolean consume
    ) {
        try {
            CompletionStage<Boolean> stage = consume
                    ? receipt.completeAsync() : receipt.refundAsync();
            return stage == null ? completed(false) : stage;
        } catch (RuntimeException | LinkageError failure) {
            return completed(false);
        }
    }

    private boolean acknowledge(
            BondedCompanionOperationProbe probe,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal
    ) {
        try {
            return store.markProfileOperationPaymentSettled(
                    probe, terminalApplied(terminal), retainedUntil());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean terminalApplied(
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal) {
        return terminal.code() == BondedCompanionStoreResult.Code.APPLIED
                && terminal.value() != null;
    }

    private boolean acknowledged(Outcome outcome) {
        return outcome == Outcome.ALREADY_SETTLED
                || outcome == Outcome.SETTLED_COMMITTED
                || outcome == Outcome.SETTLED_REJECTED;
    }

    private BondedCompanionOperationProbe probe(
            BondedCompanionPaymentOperationId.Identity identity) {
        return new BondedCompanionOperationProbe(
                identity.callerNamespace(), identity.idempotencyKey(),
                identity.ownerUuid(), identity.rosterId(),
                identity.profileId(), BondedCompanionOperation.Type.REVIVE,
                identity.expectedRevision());
    }

    private String safeOperationId(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.operationId();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private boolean safeQuarantined(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.quarantined();
        } catch (RuntimeException | LinkageError failure) {
            return true;
        }
    }

    private boolean safeTerminalRejectCleanup(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.terminalRejectionCleanupSafe();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private long retainedUntil() {
        try {
            long retained = Math.addExact(
                    clock.getAsLong(), OPERATION_RETENTION_MS);
            if (retained == 0L) return 1L;
            return retained == Long.MAX_VALUE
                    ? Long.MAX_VALUE - 1L : retained;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE - 1L;
        }
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    /** Finite recovery result used for diagnostics and deterministic tests. */
    public enum Outcome {
        NO_TERMINAL_PROOF,
        ALREADY_SETTLED,
        SETTLED_COMMITTED,
        SETTLED_REJECTED,
        QUARANTINED,
        RETRY_REQUIRED,
        RETENTION_PENDING
    }
}
