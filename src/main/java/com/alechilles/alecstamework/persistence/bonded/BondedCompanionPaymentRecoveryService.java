package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Settles one restart-recovered escrow from isolated terminal SQLite proof. */
public final class BondedCompanionPaymentRecoveryService {
    private static final long OPERATION_RETENTION_MS =
            30L * 24L * 60L * 60L * 1000L;
    private final BondedCompanionStore store;
    private final BondedCompanionRevivePaymentVerifier paymentVerifier;
    private final LongSupplier clock;
    private final Consumer<BondedCompanionRecord.Profile> revivedPublisher;

    public BondedCompanionPaymentRecoveryService(
            @Nonnull BondedCompanionStore store,
            @Nonnull LongSupplier clock
    ) {
        this(store, clock, ignored -> { });
    }

    public BondedCompanionPaymentRecoveryService(
            @Nonnull BondedCompanionStore store,
            @Nonnull LongSupplier clock,
            @Nonnull Consumer<BondedCompanionRecord.Profile> revivedPublisher
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.paymentVerifier = new BondedCompanionRevivePaymentVerifier(store);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.revivedPublisher = Objects.requireNonNull(
                revivedPublisher, "revivedPublisher");
    }

    /** Recovers only exact, hash-validated payment evidence for one owner. */
    @Nonnull
    public CompletionStage<Outcome> recover(
            @Nonnull BondedCompanionPaymentOperationId.Identity identity,
            @Nonnull BondedCompanionActionContext.Inventory inventory
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(inventory, "inventory");
        return recoverCanonical(identity, inventory);
    }

    /** Reconciles bounded terminal rows after their player escrow is absent. */
    @Nonnull
    public CompletionStage<Integer> recoverAwaitingWithoutEscrow(
            @Nonnull UUID ownerUuid,
            int limit,
            @Nonnull BondedCompanionActionContext.Inventory inventory
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(inventory, "inventory");
        java.util.List<BondedCompanionOperationProbe> awaiting;
        java.util.List<BondedCompanionLegacyPaymentSettlementGroup>
                legacyGroups;
        try {
            awaiting = store.listAwaitingProfilePaymentSettlements(
                    ownerUuid, limit);
            legacyGroups = store.listAwaitingLegacyPaymentSettlementGroups(
                    ownerUuid, limit);
        } catch (RuntimeException | LinkageError failure) {
            return completed(0);
        }
        CompletionStage<Integer> recovered = completed(0);
        for (BondedCompanionOperationProbe probe : awaiting) {
            if (probe.expectedRevision() == null
                    || probe.profileId() == null) continue;
            String operationId = BondedCompanionPaymentOperationId.create(
                    probe.callerNamespace(), probe.idempotencyKey(),
                    probe.ownerUuid(), probe.rosterId(), probe.profileId(),
                    probe.expectedRevision());
            recovered = recovered.thenCompose(count -> recover(
                    probe, operationId, inventory).thenApply(outcome ->
                    acknowledged(outcome) ? count + 1 : count));
        }
        for (BondedCompanionLegacyPaymentSettlementGroup group
                : legacyGroups) {
            if (group.ambiguous()) {
                recovered = recovered.thenApply(count -> {
                    quarantineLegacy(ownerUuid, group);
                    return count;
                });
                continue;
            }
            BondedCompanionOperationProbe probe = group.operations().getFirst();
            recovered = recovered.thenCompose(count -> recover(
                    probe, group.operationId(), inventory).thenApply(outcome -> {
                        if (outcome == Outcome.QUARANTINED) {
                            quarantineLegacy(ownerUuid, group);
                        }
                        return acknowledged(outcome) ? count + 1 : count;
                    }));
        }
        return recovered.exceptionally(ignored -> 0);
    }

    private CompletionStage<Outcome> recover(
            BondedCompanionOperationProbe probe,
            String operationId,
            BondedCompanionActionContext.Inventory inventory
    ) {
        Optional<BondedCompanionStoreResult<
                BondedCompanionRecord.Profile>> prior;
        try {
            prior = store.findProfileOperationByIdentity(probe);
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (prior.isEmpty()) return completed(Outcome.NO_TERMINAL_PROOF);
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal =
                prior.get();
        if (!terminal.replayed()) {
            return completed(Outcome.NO_TERMINAL_PROOF);
        }
        return recoverTerminal(probe, operationId, terminal, inventory);
    }

    private CompletionStage<Outcome> recoverTerminal(
            BondedCompanionOperationProbe probe,
            String operationId,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal,
            BondedCompanionActionContext.Inventory inventory
    ) {
        CompletionStage<BondedCompanionActionContext.ChargeReceipt> found;
        try {
            found = inventory.findChargeAsync(operationId);
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (found == null) return completed(Outcome.RETRY_REQUIRED);
        return found.thenCompose(receipt -> validateTerminalProof(
                operationId, probe, terminal, receipt))
                .exceptionally(ignored -> Outcome.RETRY_REQUIRED);
    }

    private CompletionStage<Outcome> validateTerminalProof(
            String operationId,
            BondedCompanionOperationProbe probe,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal,
            BondedCompanionActionContext.ChargeReceipt receipt
    ) {
        if (receipt == null) {
            return recover(operationId, probe, terminal, null);
        }
        BondedCompanionRevivePaymentVerifier.Verification verified =
                paymentVerifier.verifyTerminal(
                        probe, operationId, terminal, receipt,
                        clock.getAsLong(), retainedUntil());
        return switch (verified) {
            case VERIFIED, HISTORICAL_MARKER ->
                    recover(operationId, probe, terminal, receipt);
            case QUARANTINED -> completed(Outcome.QUARANTINED);
            case RETRY_REQUIRED -> completed(Outcome.RETRY_REQUIRED);
        };
    }

    private CompletionStage<Outcome> recoverCanonical(
            BondedCompanionPaymentOperationId.Identity identity,
            BondedCompanionActionContext.Inventory inventory
    ) {
        BondedCompanionOperationProbe probe = probe(identity);
        Optional<BondedCompanionStoreResult<
                BondedCompanionRecord.Profile>> prior;
        try {
            prior = store.findProfileOperationByIdentity(probe);
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (prior.isPresent() && prior.get().replayed()) {
            return recoverTerminal(
                    probe, identity.operationId(), prior.get(), inventory);
        }
        CompletionStage<BondedCompanionActionContext.ChargeReceipt> found;
        try {
            found = inventory.findChargeAsync(identity.operationId());
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (found == null) return completed(Outcome.RETRY_REQUIRED);
        boolean operationPresent = prior.isPresent();
        return found.thenCompose(receipt -> resumePrepared(
                identity, probe, receipt, operationPresent))
                .exceptionally(ignored -> Outcome.RETRY_REQUIRED);
    }

    private CompletionStage<Outcome> resumePrepared(
            BondedCompanionPaymentOperationId.Identity identity,
            BondedCompanionOperationProbe probe,
            BondedCompanionActionContext.ChargeReceipt receipt,
            boolean operationPresent
    ) {
        if (receipt == null) return completed(operationPresent
                ? Outcome.RETRY_REQUIRED : Outcome.NO_TERMINAL_PROOF);
        String operationId = identity.operationId();
        if (!operationId.equals(safeOperationId(receipt))
                || safeQuarantined(receipt)) {
            return completed(Outcome.QUARANTINED);
        }
        if (safeCompensationPending(receipt)) {
            if (operationPresent) return completed(Outcome.RETRY_REQUIRED);
            return settle(receipt, false).thenApply(refunded -> refunded
                    ? Outcome.SETTLED_UNCLAIMED_REFUND
                    : Outcome.RETRY_REQUIRED);
        }
        java.util.List<com.alechilles.alecstamework.api.BondedCompanionReviveCost>
                costs = safeCosts(receipt);
        if (!safePreparedClaimProof(receipt)
                || costs.isEmpty()) {
            return completed(Outcome.QUARANTINED);
        }
        long attemptedAtMs = clock.getAsLong();
        BondedCompanionOperation operation;
        try {
            operation = BondedCompanionRevivePaymentProof.operation(
                    identity.callerNamespace(), identity.idempotencyKey(),
                    identity.ownerUuid(), identity.rosterId(),
                    identity.profileId(), costs,
                    attemptedAtMs, retainedUntil());
        } catch (RuntimeException | LinkageError invalid) {
            return completed(Outcome.QUARANTINED);
        }
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> saved;
        try {
            saved = store.reviveProfile(
                    operation, identity.expectedRevision(), attemptedAtMs);
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (saved.code() == BondedCompanionStoreResult.Code.STORAGE_FAILURE) {
            return completed(Outcome.RETRY_REQUIRED);
        }
        if (saved.code()
                == BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT) {
            return completed(Outcome.QUARANTINED);
        }
        if (saved.code() == BondedCompanionStoreResult.Code.APPLIED
                && saved.value() != null && !saved.replayed()) {
            publishRevived(saved.value());
        }
        return recover(operationId, probe, saved, receipt);
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

    private void publishRevived(BondedCompanionRecord.Profile profile) {
        try {
            revivedPublisher.accept(profile);
        } catch (RuntimeException | LinkageError ignored) {
            // Listener failures cannot invalidate a committed payment result.
        }
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

    private void quarantineLegacy(
            UUID ownerUuid,
            BondedCompanionLegacyPaymentSettlementGroup group) {
        try {
            store.quarantineLegacyPaymentSettlementGroup(
                    ownerUuid, group.operationId(), retainedUntil());
        } catch (RuntimeException | LinkageError ignored) {
            // Leave the complete group pinned for a later retry.
        }
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

    private java.util.List<com.alechilles.alecstamework.api.BondedCompanionReviveCost>
            safeCosts(BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return java.util.List.copyOf(receipt.costs());
        } catch (RuntimeException | LinkageError failure) {
            return java.util.List.of();
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

    private boolean safePreparedClaimProof(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.preparedClaimProof();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean safeCompensationPending(
            BondedCompanionActionContext.ChargeReceipt receipt) {
        try {
            return receipt.compensationPending();
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
        SETTLED_UNCLAIMED_REFUND,
        QUARANTINED,
        RETRY_REQUIRED,
        RETENTION_PENDING
    }
}
