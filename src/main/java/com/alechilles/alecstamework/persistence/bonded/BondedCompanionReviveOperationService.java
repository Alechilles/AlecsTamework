package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProfile;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Coordinates durable bonded-revival payment and the canonical SQLite CAS.
 *
 * <p>A charge stage may complete only after its player inventory evidence is
 * durable. Terminal database proof is always probed before another charge,
 * and storage failures retain escrow for an exact retry.</p>
 */
final class BondedCompanionReviveOperationService {
    private static final long OPERATION_RETENTION_MS =
            30L * 24L * 60L * 60L * 1000L;
    private final BondedCompanionStore store;
    private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionPolicyResolver policies;
    private final BondedCompanionTransitionService transitions;
    private final LongSupplier clock;
    private final Support support;
    private final BondedCompanionRevivePaymentVerifier paymentVerifier;

    BondedCompanionReviveOperationService(
            BondedCompanionStore store,
            BondedCompanionRosterRegistry rosters,
            BondedCompanionPolicyResolver policies,
            BondedCompanionTransitionService transitions,
            LongSupplier clock,
            Support support
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.support = Objects.requireNonNull(support, "support");
        this.paymentVerifier = new BondedCompanionRevivePaymentVerifier(store);
    }

    @Nonnull
    CompletionStage<BondedCompanionResult<BondedCompanionProfileView>> revive(
            @Nonnull BondedCompanionReviveRequest request
    ) {
        try {
            return start(Objects.requireNonNull(request, "request"));
        } catch (RuntimeException | LinkageError failure) {
            return completed(support.internal("bonded-revive-operation-failed"));
        }
    }

    private CompletionStage<BondedCompanionResult<BondedCompanionProfileView>>
            start(BondedCompanionReviveRequest request) {
        BondedCompanionActionRequest action = request.action();
        String paymentOperationId = operationId(action);
        Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>> prior =
                store.findProfileOperationByIdentity(probe(action));
        if (prior.isPresent()) {
            return prior.get().replayed()
                    ? settleTerminal(action, paymentOperationId, prior.get())
                    : completed(support.storeFailure(prior.get()));
        }
        BondedCompanionRecord.Profile profile = support.profile(action);
        if (profile == null) return completed(support.notFound());
        if (request.quoteRevision() != rosters.snapshot().revision()) {
            return completed(support.failure(
                    BondedCompanionResultCode.REVISION_CONFLICT,
                    "bonded-revive-quote-stale"));
        }
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), profile.familyId(),
                request.quoteRevision());
        BondedCompanionPolicy policy = resolved.policy();
        if (policy == null || policy.revivePriceFor(profile.roleId()) == null) {
            return completed(support.policyDenied());
        }
        BondedCompanionPolicy.RevivePrice price = policy.revivePriceFor(profile.roleId());
        long now = clock.getAsLong();
        BondedCompanionOperation operation = support.operation(action, price, now);
        BondedCompanionResult<BondedCompanionProfileView> denied =
                validate(action, profile, price, request.quoteRevision());
        if (denied != null) return completed(denied);
        BondedCompanionActionContext.Inventory inventory = inventory(action);
        if (inventory == null) {
            return completed(paymentUnavailable());
        }
        return consume(inventory, paymentOperationId, price)
                .thenCompose(receipt -> commit(
                        action, operation, price, receipt))
                .exceptionally(ignored -> support.internal(
                        "bonded-revive-payment-recovery-pending"));
    }

    private BondedCompanionResult<BondedCompanionProfileView> validate(
            BondedCompanionActionRequest action,
            BondedCompanionRecord.Profile profile,
            BondedCompanionPolicy.RevivePrice price,
            long policyRevision
    ) {
        long now = clock.getAsLong();
        BondedCompanionSnapshot snapshot = support.decode(profile);
        if (snapshot == null) {
            return support.internal("bonded-snapshot-invalid");
        }
        BondedCompanionProfile domain = support.domain(profile, snapshot);
        var validation = transitions.revive(
                support.mutation(action, now, policyRevision), domain,
                new BondedCompanionTransitionService.RevivePayment(
                        price.costs()));
        return validation.applied()
                ? null : support.transitionFailure(validation.code());
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt> consume(
            BondedCompanionActionContext.Inventory inventory,
            String operationId,
            BondedCompanionPolicy.RevivePrice price
    ) {
        try {
            CompletionStage<BondedCompanionActionContext.ChargeReceipt> stage =
                    inventory.consumeExactAsync(operationId, price.costs());
            return stage == null ? completed(null) : stage;
        } catch (RuntimeException | LinkageError failure) {
            return completed(null);
        }
    }

    private CompletionStage<BondedCompanionResult<BondedCompanionProfileView>>
            commit(
                    BondedCompanionActionRequest action,
                    BondedCompanionOperation operation,
                    BondedCompanionPolicy.RevivePrice price,
                    BondedCompanionActionContext.ChargeReceipt receipt
            ) {
        if (receipt == null) return completed(paymentUnavailable());
        if (safeQuarantined(receipt)) {
            return completed(support.internal(
                    "bonded-revive-payment-quarantined"));
        }
        if (!operationId(action).equals(safeOperationId(receipt))) {
            return settle(receipt, false).thenApply(ignored -> support.internal(
                    "bonded-revive-payment-receipt-invalid"));
        }
        long now = clock.getAsLong();
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> saved =
                store.reviveProfile(operation, action.expectedRevision(), now);
        if (saved.code() == BondedCompanionStoreResult.Code.STORAGE_FAILURE) {
            return completed(support.storeFailure(saved));
        }
        if (saved.code() == BondedCompanionStoreResult.Code.APPLIED
                && saved.value() != null) {
            if (!saved.replayed()) publishRevived(saved.value());
            return settle(receipt, true).thenApply(settled -> {
                if (!settled) return support.internal(
                        "bonded-revive-payment-receipt-release-pending");
                if (!acknowledge(
                        action, saved, operation.retainedUntilMs())) {
                    return support.internal(
                            "bonded-revive-payment-retention-pending");
                }
                return support.success(saved.value());
            });
        }
        return settle(receipt, false).thenApply(settled -> {
            if (!settled) return support.internal(
                    "bonded-revive-payment-compensation-pending");
            return acknowledge(action, saved, operation.retainedUntilMs())
                    ? support.storeFailure(saved)
                    : support.internal(
                    "bonded-revive-payment-retention-pending");
        });
    }

    private CompletionStage<BondedCompanionResult<BondedCompanionProfileView>>
            settleTerminal(
                    BondedCompanionActionRequest action,
                    String paymentOperationId,
                    BondedCompanionStoreResult<BondedCompanionRecord.Profile> stored
            ) {
        BondedCompanionActionContext.Inventory inventory = inventory(action);
        if (inventory == null) return completed(support.storedResult(stored));
        CompletionStage<BondedCompanionActionContext.ChargeReceipt> found;
        try {
            found = inventory.findChargeAsync(paymentOperationId);
        } catch (RuntimeException | LinkageError failure) {
            return completed(support.internal(
                    "bonded-revive-payment-recovery-pending"));
        }
        if (found == null) return completed(support.internal(
                "bonded-revive-payment-recovery-pending"));
        return found.thenCompose(receipt -> verifyAndSettleTerminal(
                action, paymentOperationId, stored, receipt))
                .exceptionally(ignored -> support.internal(
                "bonded-revive-payment-recovery-pending"));
    }

    private CompletionStage<BondedCompanionResult<BondedCompanionProfileView>>
            verifyAndSettleTerminal(
                    BondedCompanionActionRequest action,
                    String operationId,
                    BondedCompanionStoreResult<BondedCompanionRecord.Profile> stored,
                    BondedCompanionActionContext.ChargeReceipt receipt
            ) {
        if (receipt == null) {
            return completed(terminalWithoutReceipt(action, stored));
        }
        var verified = paymentVerifier.verifyTerminal(
                probe(action), operationId, stored, receipt,
                clock.getAsLong(), retainedUntil());
        return switch (verified) {
            case VERIFIED, HISTORICAL_MARKER ->
                    settleVerifiedTerminal(action, stored, receipt);
            case QUARANTINED -> completed(support.internal(
                    "bonded-revive-payment-quarantined"));
            case RETRY_REQUIRED -> completed(support.internal(
                    "bonded-revive-payment-recovery-pending"));
        };
    }

    private CompletionStage<BondedCompanionResult<BondedCompanionProfileView>>
            settleVerifiedTerminal(
                    BondedCompanionActionRequest action,
                    BondedCompanionStoreResult<BondedCompanionRecord.Profile> stored,
                    BondedCompanionActionContext.ChargeReceipt receipt
            ) {
        boolean success = stored.code()
                == BondedCompanionStoreResult.Code.APPLIED
                && stored.value() != null;
        if (safeQuarantined(receipt) && !success
                && !safeTerminalRejectCleanup(receipt)) {
            return completed(support.internal(
                    "bonded-revive-payment-quarantined"));
        }
        return settle(receipt, success || safeQuarantined(receipt))
                .thenApply(settled -> {
                    if (!settled) return support.internal(success
                            ? "bonded-revive-payment-receipt-release-pending"
                            : "bonded-revive-payment-compensation-pending");
                    return acknowledge(action, stored, retainedUntil())
                            ? support.storedResult(stored)
                            : support.internal(
                            "bonded-revive-payment-retention-pending");
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

    private void publishRevived(BondedCompanionRecord.Profile profile) {
        try {
            support.publishRevived(profile);
        } catch (RuntimeException | LinkageError ignored) {
            // Listener failures cannot invalidate the unique durable commit.
        }
    }

    private BondedCompanionResult<BondedCompanionProfileView>
            terminalWithoutReceipt(
                    BondedCompanionActionRequest action,
                    BondedCompanionStoreResult<
                            BondedCompanionRecord.Profile> stored
            ) {
        return acknowledge(action, stored, retainedUntil())
                ? support.storedResult(stored)
                : support.internal(
                "bonded-revive-payment-retention-pending");
    }

    private boolean acknowledge(
            BondedCompanionActionRequest action,
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> stored,
            long retainedUntilMs
    ) {
        try {
            return store.markProfileOperationPaymentSettled(
                    probe(action), terminalApplied(stored),
                    boundedRetention(retainedUntilMs));
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean terminalApplied(
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> stored) {
        return stored.code() == BondedCompanionStoreResult.Code.APPLIED
                && stored.value() != null;
    }

    private long retainedUntil() {
        long now = clock.getAsLong();
        try {
            long retained = Math.addExact(now, OPERATION_RETENTION_MS);
            return boundedRetention(retained == 0L ? 1L : retained);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE - 1L;
        }
    }

    private long boundedRetention(long retainedUntilMs) {
        return retainedUntilMs == Long.MAX_VALUE
                ? Long.MAX_VALUE - 1L : retainedUntilMs;
    }

    private BondedCompanionActionContext.Inventory inventory(
            BondedCompanionActionRequest action) {
        return action.actionContext() == null
                ? null : action.actionContext().inventory();
    }

    private BondedCompanionResult<BondedCompanionProfileView>
            paymentUnavailable() {
        return support.failure(BondedCompanionResultCode.POLICY_DENIED,
                "bonded-revive-payment-unavailable");
    }

    private String operationId(BondedCompanionActionRequest action) {
        return BondedCompanionPaymentOperationId.create(
                action.callerNamespace(), action.idempotencyKey(),
                action.ownerUuid(), action.rosterId(), action.profileId(),
                action.expectedRevision());
    }

    private BondedCompanionOperationProbe probe(
            BondedCompanionActionRequest action) {
        return new BondedCompanionOperationProbe(
                action.callerNamespace(), action.idempotencyKey(),
                action.ownerUuid(), action.rosterId(), action.profileId(),
                BondedCompanionOperation.Type.REVIVE,
                action.expectedRevision());
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

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    /** Focused bridge back to existing core mapping and presentation logic. */
    interface Support {
        BondedCompanionRecord.Profile profile(BondedCompanionActionRequest action);

        BondedCompanionSnapshot decode(BondedCompanionRecord.Profile profile);

        BondedCompanionProfile domain(
                BondedCompanionRecord.Profile profile,
                BondedCompanionSnapshot snapshot);

        BondedCompanionTransitionService.MutationRequest mutation(
                BondedCompanionActionRequest action,
                long now,
                long policyRevision);

        BondedCompanionOperation operation(
                BondedCompanionActionRequest action,
                BondedCompanionPolicy.RevivePrice price,
                long now);

        long cooldownRemaining(long until, long now);

        BondedCompanionResult<BondedCompanionProfileView> success(
                BondedCompanionRecord.Profile profile);

        BondedCompanionResult<BondedCompanionProfileView> storedResult(
                BondedCompanionStoreResult<BondedCompanionRecord.Profile> result);

        BondedCompanionResult<BondedCompanionProfileView> failure(
                BondedCompanionResultCode code, String reason);

        BondedCompanionResult<BondedCompanionProfileView> notFound();

        BondedCompanionResult<BondedCompanionProfileView> policyDenied();

        BondedCompanionResult<BondedCompanionProfileView> internal(String reason);

        BondedCompanionResult<BondedCompanionProfileView> transitionFailure(
                BondedCompanionTransitionService.ResultCode code);

        BondedCompanionResult<BondedCompanionProfileView> storeFailure(
                BondedCompanionStoreResult<?> result);

        void publishRevived(BondedCompanionRecord.Profile profile);
    }
}
