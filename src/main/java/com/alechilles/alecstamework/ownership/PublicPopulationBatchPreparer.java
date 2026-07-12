package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionMode;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Plans and durably prepares explicit, identity-complete public admission batches. */
final class PublicPopulationBatchPreparer {
    private final PublicPopulationAdmissionPlanner planner;
    private final CompanionPopulationBatchAdmissionCoordinator coordinator;
    private final ClaimLookupMetrics lookupMetrics;
    private final PopulationAdmissionDecisionMapper decisions;

    PublicPopulationBatchPreparer(@Nonnull PublicPopulationAdmissionPlanner planner,
                                  @Nonnull CompanionPopulationBatchAdmissionCoordinator coordinator,
                                  @Nonnull ClaimLookupMetrics lookupMetrics,
                                  @Nonnull PopulationAdmissionDecisionMapper decisions) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Nonnull
    CompletableFuture<PopulationBatchAdmissionDecision> prepare(
            @Nonnull PopulationBatchAdmissionRequest request,
            @Nonnull UnitRegistrar registrar
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(registrar, "registrar");
        CompletableFuture<PopulationBatchAdmissionDecision> future = new CompletableFuture<>();
        List<PublicPopulationAdmissionPlanner.Result> plans = planUnits(request, future);
        if (plans == null) {
            return future;
        }
        if (!samePolicyContext(plans)) {
            release(plans, 0);
            future.complete(PopulationBatchAdmissionDecision.unavailable(
                    request.units().size(), "population-admission-batch-context-mismatch"
            ));
            return future;
        }
        List<CompanionPopulationAdmissionUnit> units = plans.stream()
                .map(PublicPopulationAdmissionPlanner.Result::unit)
                .toList();
        try {
            coordinator.prepareAsync(units, session(plans.getFirst().policy()), mode(request.mode()))
                    .whenComplete((result, failure) -> {
                        try {
                            complete(request, plans, registrar, future, result, failure);
                        } catch (RuntimeException | LinkageError completionFailure) {
                            coordinator.markReadinessDegraded(
                                    "public_population_batch_registration_failed"
                            );
                            future.complete(PopulationBatchAdmissionDecision.unavailable(
                                    request.units().size(),
                                    "population-admission-batch-prepare-failed"
                            ));
                        }
                    });
        } catch (RuntimeException | LinkageError failure) {
            release(plans, 0);
            future.complete(PopulationBatchAdmissionDecision.unavailable(
                    request.units().size(), "population-admission-batch-prepare-failed"
            ));
        }
        return future;
    }

    @Nullable
    private List<PublicPopulationAdmissionPlanner.Result> planUnits(
            PopulationBatchAdmissionRequest request,
            CompletableFuture<PopulationBatchAdmissionDecision> future
    ) {
        List<PublicPopulationAdmissionPlanner.Result> plans = new ArrayList<>(request.units().size());
        for (PopulationAdmissionRequest unit : request.units()) {
            PublicPopulationAdmissionPlanner.Result plan;
            try {
                plan = planner.plan(unit);
            } catch (RuntimeException | LinkageError exception) {
                release(plans, 0);
                future.complete(PopulationBatchAdmissionDecision.unavailable(
                        request.units().size(), "population-admission-batch-plan-failed"
                ));
                return null;
            }
            if (!plan.allowed()) {
                release(plans, 0);
                future.complete(decisions.batchDenied(
                        request,
                        plan.reason(),
                        List.of(decisions.denied(unit, plan.reason()))
                ));
                return null;
            }
            plans.add(plan);
        }
        return List.copyOf(plans);
    }

    private void complete(PopulationBatchAdmissionRequest request,
                          List<PublicPopulationAdmissionPlanner.Result> plans,
                          UnitRegistrar registrar,
                          CompletableFuture<PopulationBatchAdmissionDecision> future,
                          @Nullable CompanionPopulationBatchPreparationResult result,
                          @Nullable Throwable failure) {
        if (failure != null || result == null) {
            release(plans, 0);
            future.complete(PopulationBatchAdmissionDecision.unavailable(
                    request.units().size(), "population-admission-batch-prepare-failed"
            ));
            return;
        }
        if (!result.allowed() || result.preparedBatch() == null) {
            release(plans, 0);
            PopulationAdmissionDecision limiting = result.limitingDecision() == null
                    ? decisions.denied(request.units().getFirst(), result.reason())
                    : decisions.denied(request.units().getFirst(), result.limitingDecision());
            future.complete(decisions.batchDenied(request, result.reason(), List.of(limiting)));
            return;
        }
        List<PopulationAdmissionDecision> unitDecisions = new ArrayList<>();
        for (int index = 0; index < result.admittedCount(); index++) {
            unitDecisions.add(registrar.register(
                    request.units().get(index),
                    plans.get(index),
                    result.preparedBatch().admission(index)
            ));
        }
        release(plans, result.admittedCount());
        if (result.limitingDecision() != null) {
            unitDecisions.add(decisions.denied(
                    request.units().get(result.admittedCount()),
                    result.limitingDecision()
            ));
        }
        future.complete(new PopulationBatchAdmissionDecision(
                result.admittedCount() == result.requestedCount()
                        ? PopulationBatchAdmissionDecision.Status.RESERVED_EXACT
                        : PopulationBatchAdmissionDecision.Status.RESERVED_PARTIAL,
                result.reason(),
                result.requestedCount(),
                result.admittedCount(),
                unitDecisions
        ));
    }

    private void release(List<PublicPopulationAdmissionPlanner.Result> plans, int fromIndex) {
        for (int index = Math.max(0, fromIndex); index < plans.size(); index++) {
            planner.releaseProvisional(plans.get(index));
        }
    }

    @Nonnull
    private ClaimLookupSession session(CompanionAdmissionPolicyResolver.Policy policy) {
        return new ClaimLookupSession(
                policy.claimContext(),
                policy.claimLimitPerChunk() > 0,
                lookupMetrics
        );
    }

    private static boolean samePolicyContext(List<PublicPopulationAdmissionPlanner.Result> plans) {
        CompanionAdmissionPolicyResolver.Policy first = plans.getFirst().policy();
        for (PublicPopulationAdmissionPlanner.Result plan : plans) {
            if (!first.claimContext().equals(plan.policy().claimContext())
                    || first.settingsRevision() != plan.policy().settingsRevision()) {
                return false;
            }
        }
        return true;
    }

    private static CompanionPopulationBatchMode mode(PopulationBatchAdmissionMode mode) {
        return mode == PopulationBatchAdmissionMode.EXACT
                ? CompanionPopulationBatchMode.EXACT
                : CompanionPopulationBatchMode.UP_TO;
    }

    @FunctionalInterface
    interface UnitRegistrar {
        @Nonnull
        PopulationAdmissionDecision register(
                @Nonnull PopulationAdmissionRequest request,
                @Nonnull PublicPopulationAdmissionPlanner.Result plan,
                @Nonnull PreparedCompanionPopulationAdmission prepared
        );
    }
}
