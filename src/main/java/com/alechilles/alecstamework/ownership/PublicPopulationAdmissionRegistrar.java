package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.LongUnaryOperator;
import javax.annotation.Nonnull;

/** Registers prepared public capabilities and conservatively closes unexposed failures. */
final class PublicPopulationAdmissionRegistrar {
    private final CompanionIdentityResolver identityResolver;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final PopulationAdmissionDecisionMapper decisions;
    private final Map<UUID, PublicPopulationAdmissionRecord> admissions;
    private final LongUnaryOperator retentionDeadline;

    PublicPopulationAdmissionRegistrar(
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull PopulationAdmissionDecisionMapper decisions,
            @Nonnull Map<UUID, PublicPopulationAdmissionRecord> admissions,
            @Nonnull LongUnaryOperator retentionDeadline
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.retentionDeadline = Objects.requireNonNull(retentionDeadline, "retentionDeadline");
    }

    @Nonnull
    PopulationAdmissionDecision register(
            @Nonnull PopulationAdmissionRequest request,
            @Nonnull PublicPopulationAdmissionPlanner.Result plan,
            @Nonnull PreparedCompanionPopulationAdmission prepared
    ) {
        try {
            OwnerPopulationDecision owner = prepared.ownerAdmission().decision();
            PopulationAdmissionToken token = token(prepared, owner);
            PopulationAdmissionDecision decision = decisions.accepted(
                    PopulationAdmissionDecision.Status.RESERVED,
                    "population-admission-reserved",
                    token,
                    owner
            );
            PublicPopulationAdmissionRecord record = record(
                    request, plan, prepared, token, decision
            );
            PublicPopulationAdmissionRecord conflict = admissions.putIfAbsent(
                    token.operationId(), record
            );
            if (conflict == null) {
                return decision;
            }
            cancelUnregistered(prepared, plan, "population-admission-operation-id-conflict");
            return PopulationAdmissionDecision.unavailable(
                    "population-admission-operation-id-conflict"
            );
        } catch (RuntimeException | LinkageError failure) {
            cancelUnregistered(prepared, plan, "population-admission-registration-failed");
            return PopulationAdmissionDecision.unavailable(
                    "population-admission-registration-failed"
            );
        }
    }

    @Nonnull
    private static PopulationAdmissionToken token(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull OwnerPopulationDecision owner
    ) {
        return new PopulationAdmissionToken(
                prepared.ownerAdmission().operationId(),
                prepared.claimReservation().tokenId(),
                prepared.claimReservation().expiresAtMonotonicNanos(),
                prepared.ownerAdmission().settingsRevision(),
                PopulationPolicyViewService.generationToken(
                        prepared.claimReservation().providerGeneration()
                ),
                PopulationAdmissionDecisionMapper.readiness(owner.readiness())
        );
    }

    @Nonnull
    private PublicPopulationAdmissionRecord record(
            @Nonnull PopulationAdmissionRequest request,
            @Nonnull PublicPopulationAdmissionPlanner.Result plan,
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull PopulationAdmissionToken token,
            @Nonnull PopulationAdmissionDecision decision
    ) {
        return new PublicPopulationAdmissionRecord(
                request,
                plan.profileId(),
                plan.currentNpcUuid(),
                plan.provisionalIdentity(),
                prepared,
                token,
                retentionDeadline.applyAsLong(token.expiresAtMonotonicNanos()),
                decision
        );
    }

    private void cancelUnregistered(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull PublicPopulationAdmissionPlanner.Result plan,
            @Nonnull String reason
    ) {
        try {
            CompletionStage<Boolean> cancellation = coordinator.cancelAsync(prepared, reason);
            if (cancellation == null) {
                markDegradedSafely(reason);
                return;
            }
            cancellation.whenComplete((canceled, failure) -> {
                if (failure == null && Boolean.TRUE.equals(canceled)) {
                    releaseProvisional(plan);
                } else {
                    markDegradedSafely(reason);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            markDegradedSafely(reason);
        }
    }

    private void releaseProvisional(@Nonnull PublicPopulationAdmissionPlanner.Result plan) {
        if (plan.provisionalIdentity()) {
            identityResolver.releaseProvisional(plan.profileId(), plan.currentNpcUuid());
        }
    }

    private void markDegradedSafely(@Nonnull String reason) {
        try {
            coordinator.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The prepared reservation remains conservative if readiness reporting also fails.
        }
    }
}
