package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Consistent public decision mapping for runtime admission lifecycle stages. */
final class PopulationAdmissionDecisionMapper {
    private final OwnerPopulationIndex ownerIndex;
    private final CompanionAdmissionPolicyResolver policyResolver;

    PopulationAdmissionDecisionMapper(@Nonnull OwnerPopulationIndex ownerIndex,
                                      @Nonnull CompanionAdmissionPolicyResolver policyResolver) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
    }

    @Nonnull
    PopulationAdmissionDecision denied(@Nonnull PopulationAdmissionRequest request,
                                       @Nonnull String reason) {
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(operation(request), false);
        UUID owner = request.newOwnerUuid() != null ? request.newOwnerUuid() : request.oldOwnerUuid();
        OwnerPopulationCounts counts = owner == null
                ? new OwnerPopulationCounts(0L, 0L, 0L, 0L)
                : ownerIndex.counts(owner, world(request));
        long committed = policy.scope() == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalCommitted() : counts.worldCommitted();
        long pending = policy.scope() == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalPending() : counts.worldPending();
        return new PopulationAdmissionDecision(
                PopulationAdmissionDecision.Status.DENIED,
                reason,
                null,
                readiness(ownerIndex.readiness(policy.scope())),
                committed,
                pending
        );
    }

    @Nonnull
    PopulationAdmissionDecision denied(@Nonnull PopulationAdmissionRequest request,
                                       @Nonnull CompanionPopulationPreparationResult result) {
        if (result.ownerDecision() == null) {
            return denied(request, result.reason());
        }
        OwnerPopulationDecision owner = result.ownerDecision();
        return new PopulationAdmissionDecision(
                PopulationAdmissionDecision.Status.DENIED,
                result.reason(),
                null,
                readiness(owner.readiness()),
                owner.committedCount(),
                owner.pendingCount()
        );
    }

    @Nonnull
    PopulationAdmissionDecision accepted(@Nonnull PopulationAdmissionDecision.Status status,
                                         @Nonnull String reason,
                                         @Nonnull PopulationAdmissionToken token,
                                         @Nonnull OwnerPopulationDecision owner) {
        long pending = owner.pendingCount() + (owner.positiveDelta() ? 1L : 0L);
        return new PopulationAdmissionDecision(
                status,
                reason,
                token,
                readiness(owner.readiness()),
                owner.committedCount(),
                pending
        );
    }

    @Nonnull
    PopulationAdmissionDecision closed(@Nonnull PopulationAdmissionDecision.Status status,
                                       @Nonnull String reason) {
        return new PopulationAdmissionDecision(
                status,
                reason,
                null,
                status == PopulationAdmissionDecision.Status.DEGRADED
                        ? OwnerPopulationCapDecisionViewV2.Readiness.DEGRADED
                        : OwnerPopulationCapDecisionViewV2.Readiness.READY,
                OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT,
                OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT
        );
    }

    @Nonnull
    PopulationBatchAdmissionDecision batchDenied(@Nonnull PopulationBatchAdmissionRequest request,
                                                 @Nonnull String reason,
                                                 @Nonnull List<PopulationAdmissionDecision> units) {
        return new PopulationBatchAdmissionDecision(
                PopulationBatchAdmissionDecision.Status.DENIED,
                reason,
                request.units().size(),
                0,
                units
        );
    }

    @Nonnull
    static OwnerPopulationCapDecisionViewV2.Readiness readiness(
            @Nonnull OwnerPopulationReadiness readiness
    ) {
        return OwnerPopulationCapDecisionViewV2.Readiness.valueOf(readiness.name());
    }

    @Nonnull
    static OwnerPopulationOperation operation(@Nonnull PopulationAdmissionRequest request) {
        return OwnerPopulationOperation.valueOf(request.operation().name());
    }

    private static String world(PopulationAdmissionRequest request) {
        return request.destination() != null
                ? request.destination().worldName()
                : request.source() == null ? null : request.source().worldName();
    }
}
