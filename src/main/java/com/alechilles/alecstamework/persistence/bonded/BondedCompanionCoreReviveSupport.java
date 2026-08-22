package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProfile;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import java.util.Objects;

/** Keeps revive-specific orchestration out of the already-large core class. */
final class BondedCompanionCoreReviveSupport
        implements BondedCompanionReviveOperationService.Support {
    private final BondedCompanionCoreApiOperations core;

    BondedCompanionCoreReviveSupport(BondedCompanionCoreApiOperations core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    @Override
    public BondedCompanionRecord.Profile profile(
            BondedCompanionActionRequest action) {
        return core.profile(action);
    }

    @Override
    public BondedCompanionSnapshot decode(
            BondedCompanionRecord.Profile profile) {
        return core.decode(profile);
    }

    @Override
    public BondedCompanionProfile domain(
            BondedCompanionRecord.Profile profile,
            BondedCompanionSnapshot snapshot) {
        return core.domain(profile, snapshot);
    }

    @Override
    public BondedCompanionTransitionService.MutationRequest mutation(
            BondedCompanionActionRequest action,
            long now,
            long policyRevision) {
        return core.mutation(action, now, policyRevision);
    }

    @Override
    public BondedCompanionOperation operation(
            BondedCompanionActionRequest action,
            BondedCompanionPolicy.RevivePrice price,
            long now) {
        return BondedCompanionCoreApiOperations.reviveOperation(
                action, price, now);
    }

    @Override
    public long cooldownRemaining(long until, long now) {
        return core.cooldownRemaining(until, now);
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> success(
            BondedCompanionRecord.Profile profile) {
        return core.success(core.view(profile));
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> storedResult(
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> result) {
        return result.code() == BondedCompanionStoreResult.Code.APPLIED
                && result.value() != null
                ? success(result.value()) : core.storeFailure(result);
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> failure(
            BondedCompanionResultCode code, String reason) {
        return core.failure(code, reason);
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> notFound() {
        return core.notFound();
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> policyDenied() {
        return core.policyDenied();
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> internal(
            String reason) {
        return core.internal(reason);
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> transitionFailure(
            BondedCompanionTransitionService.ResultCode code) {
        return core.transitionFailure(code);
    }

    @Override
    public BondedCompanionResult<BondedCompanionProfileView> storeFailure(
            BondedCompanionStoreResult<?> result) {
        return core.storeFailure(result);
    }

    @Override
    public void publishRevived(
            BondedCompanionRecord.Profile profile,
            String operationId,
            boolean recovered
    ) {
        core.publish(profile, BondedCompanionState.DEAD,
                BondedCompanionState.STORED, "revived",
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED);
        BondedRevivalActivityProjection.publish(
                operationId, profile, recovered);
    }
}
