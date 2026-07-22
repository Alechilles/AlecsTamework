package com.alechilles.alecstamework.ownership.groups.runtime;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.ownership.OwnerPopulationAdmissionCoordinator;
import com.alechilles.alecstamework.ownership.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.ownership.OwnerPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.PreparedOwnerPopulationAdmission;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupBucket;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDelta;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDeltaPlanner;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupTransition;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Unified owner/group reservation authority. It serializes the short owner reservation phase,
 * persists every group delta as one all-or-none set, and retains only opaque process-local owner
 * capabilities. Durable recovery is reconstructed from group operations and canonical profiles.
 */
public final class PopulationGroupAdmissionRuntime {
    private final OwnerPopulationAdmissionCoordinator ownerCoordinator;
    private final PopulationGroupRegistry groupRegistry;
    private final PopulationGroupRepository groupRepository;
    private final NpcProfileRepository profileRepository;
    private final ReentrantLock admissionLock = new ReentrantLock();
    private final Map<UUID, PreparedAdmission> preparedByPopulationOperation =
            new ConcurrentHashMap<>();
    private final AtomicBoolean recoveryReady = new AtomicBoolean(false);

    public PopulationGroupAdmissionRuntime(
            @Nonnull OwnerPopulationAdmissionCoordinator ownerCoordinator,
            @Nonnull PopulationGroupRegistry groupRegistry,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull NpcProfileRepository profileRepository) {
        this.ownerCoordinator = Objects.requireNonNull(ownerCoordinator, "ownerCoordinator");
        this.groupRegistry = Objects.requireNonNull(groupRegistry, "groupRegistry");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
    }

    @Nonnull
    public CompletionStage<Preparation> prepare(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        PreparedAdmission existing = preparedByPopulationOperation.get(request.populationOperationId());
        if (existing != null) {
            return CompletableFuture.completedFuture(existing.request().equals(request)
                    ? Preparation.prepared(request.populationOperationId(), "population-group-already-prepared")
                    : Preparation.denied("population-group-idempotency-conflict"));
        }

        PopulationGroupIndex index = groupRegistry.snapshot();
        if (index.revision() != request.classificationRevision()) {
            return CompletableFuture.completedFuture(Preparation.denied(
                    "population-group-policy-revision-changed"));
        }
        PopulationGroupCountDeltaPlanner planner = new PopulationGroupCountDeltaPlanner(index);
        Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas =
                planner.plan(request.groupTransition());
        List<PopulationGroupRepository.ReservationEvidence> evidence = new ArrayList<>();
        for (Map.Entry<PopulationGroupBucket, PopulationGroupCountDelta> entry : deltas.entrySet()) {
            var definition = index.getDefinition(entry.getKey().groupId()).orElse(null);
            if (definition == null) {
                return CompletableFuture.completedFuture(Preparation.denied(
                        "population-group-definition-unavailable"));
            }
            evidence.add(new PopulationGroupRepository.ReservationEvidence(
                    entry.getKey().ownerUuid(), entry.getKey().groupId(),
                    definition.scope() == com.alechilles.alecstamework.api.PopulationGroupScope.GLOBAL
                            ? PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL
                            : PopulationGroupCountEvidenceRecord.ScopeKind.PER_WORLD,
                    entry.getKey().ownershipWorldName(), entry.getValue().owned(),
                    entry.getValue().active(), saturatingLimit(definition.maxOwnedPerOwner()),
                    saturatingLimit(definition.maxActivePerOwner()), request.classificationRevision()));
        }

        PopulationGroupOperationRecord groupOperation = request.groupOperation();
        final CompletableFuture<OwnerPopulationPreparationResult> ownerPreparation;
        admissionLock.lock();
        try {
            ownerPreparation = ownerCoordinator.groupCompositeCoordinator()
                    .prepareProvisionedDormantAsync(
                    request.ownerPlan(), groupRepository, groupOperation, evidence);
        } finally {
            admissionLock.unlock();
        }
        return ownerPreparation.thenApply(result -> {
            if (result == null || !result.allowed() || result.preparedAdmission() == null) {
                return Preparation.denied(result == null
                        ? "population-group-owner-prepare-missing" : result.reason());
            }
            PreparedAdmission prepared = new PreparedAdmission(
                    request, result.preparedAdmission(), groupOperation,
                    request.classification(), request.ownerSettingsRevision(),
                    request.providerGeneration());
            PreparedAdmission raced = preparedByPopulationOperation.putIfAbsent(
                    request.populationOperationId(), prepared);
            if (raced != null) {
                ownerCoordinator.cancelAsync(result.preparedAdmission(),
                        "population-group-duplicate-preparation");
                return raced.request().equals(request)
                        ? Preparation.prepared(request.populationOperationId(),
                                "population-group-already-prepared")
                        : Preparation.denied("population-group-idempotency-conflict");
            }
            return Preparation.prepared(request.populationOperationId(), result.reason());
        });
    }

    @Nonnull
    public Claim claim(@Nonnull UUID populationOperationId,
                       long currentOwnerSettingsRevision,
                       long currentClassificationRevision,
                       @Nonnull ClaimProviderGeneration currentProviderGeneration) {
        PreparedAdmission prepared = preparedByPopulationOperation.get(
                Objects.requireNonNull(populationOperationId, "populationOperationId"));
        if (prepared == null) return Claim.denied("population-group-capability-unavailable");
        if (prepared.request().classificationRevision() != currentClassificationRevision) {
            cancel(populationOperationId, "population-group-policy-revision-changed");
            return Claim.denied("population-group-policy-revision-changed");
        }
        boolean claimed = ownerCoordinator.groupCompositeCoordinator().claimForApply(
                prepared.ownerAdmission(), currentOwnerSettingsRevision,
                Objects.requireNonNull(currentProviderGeneration, "currentProviderGeneration"));
        if (!claimed) {
            cancel(populationOperationId, "population-group-owner-claim-denied");
            return Claim.denied("population-group-owner-claim-denied");
        }
        prepared.claimed().set(true);
        return Claim.success();
    }

    @Nonnull
    public CompletionStage<Commit> commitDormant(
            @Nonnull UUID populationOperationId,
            @Nonnull NpcProfileRepository.DormantProfileMutation profileMutation,
            long nowMs) {
        PreparedAdmission prepared = preparedByPopulationOperation.get(
                Objects.requireNonNull(populationOperationId, "populationOperationId"));
        if (prepared == null) {
            return CompletableFuture.completedFuture(Commit.denied(
                    "population-group-capability-unavailable"));
        }
        if (!prepared.claimed().get()) {
            return CompletableFuture.completedFuture(Commit.denied(
                    "population-group-capability-not-claimed"));
        }
        return ownerCoordinator.groupCompositeCoordinator().commitProvisionedDormantAsync(
                prepared.ownerAdmission(), profileRepository, profileMutation,
                groupRepository, prepared.groupOperation().operationId(),
                prepared.classification(), nowMs).thenApply(result -> {
            if (result == null || !result.committed()) {
                return Commit.denied(result == null
                        ? "population-group-commit-missing" : result.reason());
            }
            preparedByPopulationOperation.remove(populationOperationId, prepared);
            return Commit.success();
        });
    }

    @Nonnull
    public CompletionStage<Void> cancel(@Nonnull UUID populationOperationId,
                                        @Nonnull String reason) {
        PreparedAdmission prepared = preparedByPopulationOperation.remove(
                Objects.requireNonNull(populationOperationId, "populationOperationId"));
        if (prepared == null) return CompletableFuture.completedFuture(null);
        CompletionStage<Boolean> owner = ownerCoordinator.cancelAsync(
                prepared.ownerAdmission(), requireText(reason, "reason"));
        CompletionStage<Boolean> groups = cancelGroupOperation(prepared.groupOperation(), reason);
        return owner.handle((ignored, failure) -> null)
                .thenCombine(groups.handle((ignored, failure) -> null), (left, right) -> null);
    }

    /** Reconciles durable stale reservations before provisioning recovery is advertised ready. */
    @Nonnull
    public CompletionStage<RecoveryReport> recover() {
        recoveryReady.set(false);
        final List<PopulationGroupOperationRecord> operations;
        try {
            operations = groupRepository.loadRecoverableOperations();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(0, 0, 1, false));
        }
        CompletionStage<RecoveryReport> chain = CompletableFuture.completedFuture(
                new RecoveryReport(operations.size(), 0, 0, false));
        for (PopulationGroupOperationRecord operation : operations) {
            chain = chain.thenCompose(report -> reconcile(operation)
                    .handle((success, failure) -> failure == null && Boolean.TRUE.equals(success)
                            ? report.succeededOne() : report.failedOne()));
        }
        return chain.thenApply(report -> {
            boolean ready = report.failed() == 0;
            recoveryReady.set(ready);
            return new RecoveryReport(report.scanned(), report.succeeded(), report.failed(), ready);
        });
    }

    public boolean recoveryReady() { return recoveryReady.get(); }

    @Nonnull
    private CompletionStage<Boolean> reconcile(PopulationGroupOperationRecord operation) {
        if (operation.state() == PopulationGroupOperationRecord.State.PREPARED) {
            return advance(operation.operationId(), PopulationGroupOperationRecord.State.PREPARED,
                    PopulationGroupOperationRecord.State.CANCELED, "recovered-unclaimed-reservation");
        }
        if (operation.state() == PopulationGroupOperationRecord.State.APPLYING
                || operation.state() == PopulationGroupOperationRecord.State.APPLIED
                || operation.state() == PopulationGroupOperationRecord.State.QUARANTINED) {
            return compensate(operation, "recovered-uncommitted-reservation");
        }
        if (operation.state() == PopulationGroupOperationRecord.State.COMPENSATING) {
            return advance(operation.operationId(), PopulationGroupOperationRecord.State.COMPENSATING,
                    PopulationGroupOperationRecord.State.FAILED, "recovered-reservation-released");
        }
        return CompletableFuture.completedFuture(true);
    }

    @Nonnull
    private CompletionStage<Boolean> cancelGroupOperation(
            PopulationGroupOperationRecord operation, String reason) {
        final PopulationGroupOperationRecord durable;
        try {
            durable = groupRepository.findOperation(operation.operationId());
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(false);
        }
        if (durable == null) return CompletableFuture.completedFuture(false);
        if (durable.state() == PopulationGroupOperationRecord.State.PREPARED) {
            return advance(durable.operationId(), PopulationGroupOperationRecord.State.PREPARED,
                    PopulationGroupOperationRecord.State.CANCELED, reason);
        }
        return compensate(durable, reason);
    }

    @Nonnull
    private CompletionStage<Boolean> compensate(PopulationGroupOperationRecord operation,
                                                  String reason) {
        PopulationGroupOperationRecord.State state = operation.state();
        CompletionStage<Boolean> start;
        if (state == PopulationGroupOperationRecord.State.COMPENSATING) {
            start = CompletableFuture.completedFuture(true);
        } else if (state == PopulationGroupOperationRecord.State.APPLYING
                || state == PopulationGroupOperationRecord.State.APPLIED
                || state == PopulationGroupOperationRecord.State.QUARANTINED) {
            start = advance(operation.operationId(), state,
                    PopulationGroupOperationRecord.State.COMPENSATING, reason);
        } else {
            return CompletableFuture.completedFuture(state.isTerminal());
        }
        return start.thenCompose(started -> started
                ? advance(operation.operationId(), PopulationGroupOperationRecord.State.COMPENSATING,
                        PopulationGroupOperationRecord.State.FAILED, reason)
                : CompletableFuture.completedFuture(false));
    }

    @Nonnull
    private CompletionStage<Boolean> advance(String operationId,
                                              PopulationGroupOperationRecord.State expected,
                                              PopulationGroupOperationRecord.State next,
                                              String reason) {
        var submission = groupRepository.advanceOperationAsync(
                operationId, expected, next, reason, System.currentTimeMillis());
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(false);
        }
        return submission.completion().thenApply(outcome -> outcome != null && outcome.isCommitted()
                && outcome.value() != null
                && (outcome.value().status() == PopulationGroupRepository.Status.IDEMPOTENT
                || outcome.value().status() == status(next)));
    }

    private PopulationGroupRepository.Status status(PopulationGroupOperationRecord.State state) {
        return switch (state) {
            case PREPARED -> PopulationGroupRepository.Status.PREPARED;
            case APPLYING -> PopulationGroupRepository.Status.APPLYING;
            case APPLIED -> PopulationGroupRepository.Status.APPLIED;
            case COMMITTED -> PopulationGroupRepository.Status.COMMITTED;
            case CANCELED -> PopulationGroupRepository.Status.CANCELED;
            case COMPENSATING, QUARANTINED -> PopulationGroupRepository.Status.QUARANTINED;
            case FAILED -> PopulationGroupRepository.Status.FAILED;
        };
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static int saturatingLimit(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public record Request(@Nonnull UUID populationOperationId,
                          @Nonnull OwnerPopulationAdmissionPlan ownerPlan,
                          @Nonnull PopulationGroupTransition groupTransition,
                          @Nonnull PopulationGroupOperationRecord groupOperation,
                          @Nonnull PopulationGroupRepository.ClassificationMutation classification,
                          long classificationRevision,
                          long ownerSettingsRevision,
                          @Nonnull ClaimProviderGeneration providerGeneration) {
        public Request {
            populationOperationId = Objects.requireNonNull(populationOperationId,
                    "populationOperationId");
            ownerPlan = Objects.requireNonNull(ownerPlan, "ownerPlan");
            groupTransition = Objects.requireNonNull(groupTransition, "groupTransition");
            groupOperation = Objects.requireNonNull(groupOperation, "groupOperation");
            classification = Objects.requireNonNull(classification, "classification");
            providerGeneration = Objects.requireNonNull(providerGeneration, "providerGeneration");
            if (classificationRevision < 0L || ownerSettingsRevision < 0L) {
                throw new IllegalArgumentException("Policy revisions cannot be negative");
            }
            if (!populationOperationId.toString().equals(groupOperation.populationOperationId())) {
                throw new IllegalArgumentException("Group operation correlation must match");
            }
        }
    }

    public record Preparation(boolean prepared, @Nonnull String reason,
                              @Nullable UUID populationOperationId) {
        static Preparation prepared(UUID id, String reason) {
            return new Preparation(true, reason, id);
        }
        static Preparation denied(String reason) { return new Preparation(false, reason, null); }
    }

    public record Claim(boolean claimed, @Nonnull String reason) {
        static Claim success() { return new Claim(true, "population-group-claimed"); }
        static Claim denied(String reason) { return new Claim(false, reason); }
    }

    public record Commit(boolean committed, @Nonnull String reason) {
        static Commit success() { return new Commit(true, "population-group-committed"); }
        static Commit denied(String reason) { return new Commit(false, reason); }
    }

    public record RecoveryReport(int scanned, int succeeded, int failed, boolean ready) {
        RecoveryReport succeededOne() { return new RecoveryReport(scanned, succeeded + 1, failed, ready); }
        RecoveryReport failedOne() { return new RecoveryReport(scanned, succeeded, failed + 1, ready); }
    }

    private record PreparedAdmission(@Nonnull Request request,
                                     @Nonnull PreparedOwnerPopulationAdmission ownerAdmission,
                                     @Nonnull PopulationGroupOperationRecord groupOperation,
                                     @Nonnull PopulationGroupRepository.ClassificationMutation classification,
                                     long ownerSettingsRevision,
                                     @Nonnull ClaimProviderGeneration providerGeneration,
                                     @Nonnull AtomicBoolean claimed) {
        PreparedAdmission(Request request,
                          PreparedOwnerPopulationAdmission ownerAdmission,
                          PopulationGroupOperationRecord groupOperation,
                          PopulationGroupRepository.ClassificationMutation classification,
                          long ownerSettingsRevision,
                          ClaimProviderGeneration providerGeneration) {
            this(request, ownerAdmission, groupOperation, classification, ownerSettingsRevision,
                    providerGeneration, new AtomicBoolean(false));
        }
    }
}
