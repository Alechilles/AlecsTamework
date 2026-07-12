package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.api.internal.PopulationPolicyAuthority;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Runtime-backed public owner/claim population authority with opaque, idempotent capabilities. */
public final class RuntimePopulationPolicyAuthority implements PopulationPolicyAuthority {
    private static final String INVALID_TOKEN = "population-admission-token-invalid";
    private final CompanionIdentityResolver identityResolver;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final PublicPopulationAdmissionPlanner planner;
    private final PopulationAdmissionDecisionMapper decisions;
    private final PublicPopulationAdmissionRegistrar registrar;
    private final PublicPopulationBatchPreparer batchPreparer;
    private final PopulationPolicyViewService views;
    private final ClaimLookupMetrics lookupMetrics;
    private final LongSupplier monotonicClock;
    private final PublicPopulationExpiredAdmissionCleaner expiredCleaner;
    private final PublicPopulationRetentionCleaner retentionCleaner;
    private final PublicPopulationCapabilityLifecycle capabilityLifecycle;
    private final PublicPopulationCommitCoordinator commitCoordinator;
    private final Map<UUID, PublicPopulationAdmissionRecord> admissions = new ConcurrentHashMap<>();
    private final Map<String, SinglePreparation> idempotentSingles = new ConcurrentHashMap<>();
    private final Map<String, BatchPreparation> idempotentBatches = new ConcurrentHashMap<>();
    public RuntimePopulationPolicyAuthority(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull CompanionPopulationBatchAdmissionCoordinator batchCoordinator,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull ClaimAdmissionService claimAdmissionService,
            @Nonnull ClaimProviderRegistry claimProviderRegistry
    ) {
        this(
                ownerIndex,
                identityResolver,
                coordinator,
                batchCoordinator,
                claimIndex,
                claimAdmissionService,
                claimProviderRegistry,
                System::nanoTime,
                new ClaimLookupMetrics()
        );
    }

    RuntimePopulationPolicyAuthority(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull CompanionPopulationBatchAdmissionCoordinator batchCoordinator,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull ClaimAdmissionService claimAdmissionService,
            @Nonnull ClaimProviderRegistry claimProviderRegistry,
            @Nonnull LongSupplier monotonicClock,
            @Nonnull ClaimLookupMetrics lookupMetrics
    ) {
        Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(batchCoordinator, "batchCoordinator");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.expiredCleaner = new PublicPopulationExpiredAdmissionCleaner(
                admissions,
                this.monotonicClock
        );
        this.retentionCleaner = new PublicPopulationRetentionCleaner(this.monotonicClock);
        this.policyResolver = new CompanionAdmissionPolicyResolver(
                Objects.requireNonNull(claimIndex, "claimIndex"),
                Objects.requireNonNull(claimProviderRegistry, "claimProviderRegistry")
        );
        this.planner = new PublicPopulationAdmissionPlanner(
                ownerIndex,
                identityResolver,
                claimIndex,
                policyResolver
        );
        this.decisions = new PopulationAdmissionDecisionMapper(ownerIndex, policyResolver);
        this.registrar = new PublicPopulationAdmissionRegistrar(
                identityResolver,
                coordinator,
                decisions,
                admissions,
                retentionCleaner::admissionRetentionDeadline
        );
        this.capabilityLifecycle = new PublicPopulationCapabilityLifecycle(
                coordinator, policyResolver, decisions, lookupMetrics
        );
        this.commitCoordinator = new PublicPopulationCommitCoordinator(
                identityResolver, coordinator, decisions
        );
        this.batchPreparer = new PublicPopulationBatchPreparer(
                planner,
                batchCoordinator,
                lookupMetrics,
                decisions
        );
        this.views = new PopulationPolicyViewService(
                ownerIndex,
                Objects.requireNonNull(claimAdmissionService, "claimAdmissionService"),
                lookupMetrics,
                policyResolver
        );
    }

    public void setReconciliationDiagnostics(
            @Nonnull Supplier<PopulationDiagnosticsView.ReconciliationView> supplier
    ) {
        views.setReconciliationSupplier(supplier);
    }

    @Nonnull
    @Override
    public PopulationCapDecisionView evaluateLegacyOwnerCap(@Nullable UUID ownerUuid) {
        return views.evaluateLegacy(ownerUuid);
    }

    @Nonnull
    @Override
    public OwnerPopulationCapDecisionViewV2 evaluateOwnerCap(
            @Nonnull OwnerPopulationCapRequestV2 request
    ) {
        return views.evaluate(request);
    }

    @Nonnull @Override
    public PopulationDiagnosticsView populationDiagnostics() {
        return views.diagnostics();
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> tryAdmit(
            @Nonnull PopulationAdmissionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        String key = request.identity().idempotencyKey();
        CompletableFuture<PopulationAdmissionDecision> future = new CompletableFuture<>();
        if (key != null) {
            SinglePreparation candidate = new SinglePreparation(
                    request, future, retentionCleaner.retentionDeadline()
            );
            SinglePreparation existing = idempotentSingles.putIfAbsent(key, candidate);
            if (existing != null) {
                return existing.request().equals(request)
                        ? existing.future()
                        : CompletableFuture.completedFuture(decisions.denied(
                                request, "population-admission-idempotency-conflict"
                        ));
            }
        }
        afterCleanup(
                () -> startSingle(request, future),
                () -> future.complete(PopulationAdmissionDecision.unavailable(
                        "population-admission-cleanup-failed"
                ))
        );
        return future;
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
            @Nonnull PopulationBatchAdmissionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<PopulationBatchAdmissionDecision> future = new CompletableFuture<>();
        BatchPreparation candidate = new BatchPreparation(
                request, future, retentionCleaner.retentionDeadline()
        );
        BatchPreparation existing = idempotentBatches.putIfAbsent(
                request.batchIdempotencyKey(),
                candidate
        );
        if (existing != null) {
            return existing.request().equals(request)
                    ? existing.future()
                    : CompletableFuture.completedFuture(decisions.batchDenied(
                            request,
                            "population-admission-batch-idempotency-conflict",
                            List.of()
                    ));
        }
        afterCleanup(
                () -> startBatch(request, future),
                () -> future.complete(PopulationBatchAdmissionDecision.unavailable(
                        request.units().size(), "population-admission-cleanup-failed"
                ))
        );
        return future;
    }

    private void afterCleanup(@Nonnull Runnable start, @Nonnull Runnable failed) {
        try {
            cleanupExpired().whenComplete((ignored, failure) -> {
                if (failure == null) start.run();
                else failed.run();
            });
        } catch (RuntimeException | LinkageError failure) {
            failed.run();
        }
    }

    @Nonnull
    @Override
    public PopulationAdmissionDecision claimForApply(@Nonnull PopulationAdmissionToken token) {
        PublicPopulationAdmissionRecord record = find(token);
        if (record == null) {
            return PopulationAdmissionDecision.unavailable(INVALID_TOKEN);
        }
        PopulationAdmissionDecision decision = capabilityLifecycle.claim(record);
        if (decision.status() == PopulationAdmissionDecision.Status.CANCELED) {
            releaseProvisionalAfterCancellation(record);
        }
        return decision;
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> commit(
            @Nonnull PopulationAdmissionToken token
    ) {
        PublicPopulationAdmissionRecord record = find(token);
        if (record == null) {
            return CompletableFuture.completedFuture(PopulationAdmissionDecision.unavailable(INVALID_TOKEN));
        }
        return commitCoordinator.commit(record);
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> cancel(
            @Nonnull PopulationAdmissionToken token
    ) {
        PublicPopulationAdmissionRecord record = find(token);
        if (record == null) {
            return CompletableFuture.completedFuture(PopulationAdmissionDecision.unavailable(INVALID_TOKEN));
        }
        return capabilityLifecycle.cancel(record).thenApply(decision -> {
            if (decision.status() == PopulationAdmissionDecision.Status.CANCELED) {
                releaseProvisional(record);
            }
            return decision;
        });
    }

    @Nonnull
    @Override
    public CompletionStage<Integer> cleanupExpired() {
        retentionCleaner.prune(admissions, idempotentSingles, idempotentBatches);
        return expiredCleaner.cleanup(
                this::cancel,
                (ignored, state) -> coordinator.markCapabilityReadinessDegraded(
                        "public_population_" + state.name() + "_capability_quarantined"
                )
        );
    }

    private void startSingle(PopulationAdmissionRequest request,
                             CompletableFuture<PopulationAdmissionDecision> future) {
        PublicPopulationAdmissionPlanner.Result plan = null;
        try {
            PublicPopulationAdmissionPlanner.Result planned = planner.plan(request);
            plan = planned;
            if (!planned.allowed()) {
                future.complete(decisions.denied(request, planned.reason()));
                return;
            }
            coordinator.prepareAsync(
                    planned.unit().ownerPlan(),
                    planned.unit().claimRequest(),
                    session(planned.policy())
            ).whenComplete((result, failure) -> completeSingle(
                    request, planned, future, result, failure
            ));
        } catch (RuntimeException | LinkageError exception) {
            if (plan != null) {
                planner.releaseProvisional(plan);
            }
            future.complete(PopulationAdmissionDecision.unavailable("population-admission-prepare-failed"));
        }
    }

    private void completeSingle(PopulationAdmissionRequest request,
                                PublicPopulationAdmissionPlanner.Result plan,
                                CompletableFuture<PopulationAdmissionDecision> future,
                                @Nullable CompanionPopulationPreparationResult result,
                                @Nullable Throwable failure) {
        if (failure != null || result == null) {
            planner.releaseProvisional(plan);
            future.complete(PopulationAdmissionDecision.unavailable("population-admission-prepare-failed"));
            return;
        }
        if (!result.allowed() || result.preparedAdmission() == null) {
            planner.releaseProvisional(plan);
            future.complete(decisions.denied(request, result));
            return;
        }
        future.complete(registrar.register(request, plan, result.preparedAdmission()));
    }

    private void startBatch(PopulationBatchAdmissionRequest request,
                            CompletableFuture<PopulationBatchAdmissionDecision> future) {
        try {
            batchPreparer.prepare(request, registrar::register).whenComplete((decision, failure) -> {
                if (failure != null || decision == null) {
                    future.complete(PopulationBatchAdmissionDecision.unavailable(
                            request.units().size(), "population-admission-batch-prepare-failed"
                    ));
                } else {
                    future.complete(decision);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            future.complete(PopulationBatchAdmissionDecision.unavailable(
                    request.units().size(), "population-admission-batch-prepare-failed"
            ));
        }
    }

    private void releaseProvisional(@Nonnull PublicPopulationAdmissionRecord record) {
        if (record.provisionalIdentity()) {
            identityResolver.releaseProvisional(record.profileId(), record.currentNpcUuid());
        }
    }

    private void releaseProvisionalAfterCancellation(
            @Nonnull PublicPopulationAdmissionRecord record
    ) {
        CompletableFuture<PopulationAdmissionDecision> completion = record.completion();
        if (completion == null) return;
        completion.whenComplete((decision, failure) -> {
            if (failure == null && decision != null
                    && decision.status() == PopulationAdmissionDecision.Status.CANCELED) {
                releaseProvisional(record);
            }
        });
    }

    @Nullable
    private PublicPopulationAdmissionRecord find(PopulationAdmissionToken token) {
        Objects.requireNonNull(token, "token");
        PublicPopulationAdmissionRecord record = admissions.get(token.operationId());
        return record != null && record.matches(token) ? record : null;
    }

    @Nonnull
    private ClaimLookupSession session(CompanionAdmissionPolicyResolver.Policy policy) {
        return new ClaimLookupSession(
                policy.claimContext(),
                policy.claimLimitPerChunk() > 0,
                lookupMetrics
        );
    }

    private record SinglePreparation(PopulationAdmissionRequest request,
                                     CompletableFuture<PopulationAdmissionDecision> future,
                                     long retainUntilNanos)
            implements PublicPopulationRetentionCleaner.RetainedPreparation {
    }

    private record BatchPreparation(PopulationBatchAdmissionRequest request,
                                    CompletableFuture<PopulationBatchAdmissionDecision> future,
                                    long retainUntilNanos)
            implements PublicPopulationRetentionCleaner.RetainedPreparation {
    }
}
