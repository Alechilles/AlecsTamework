package com.alechilles.alecstamework.ownership.groups.runtime;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.ownership.CompanionPopulationAdmissionCoordinator;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CompanionPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.ownership.PreparedCompanionPopulationAdmission;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupBucket;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDelta;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupLifecycleClassifier;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupTransition;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Installable claim-bearing owner/group transition coordinator. Gameplay paths supply canonical
 * before/after role facts, while this authority resolves the target groups, reserves all owner,
 * claim, and group constraints, and returns one opaque operation id.
 */
public final class UnifiedPopulationTransitionCoordinator {
    private final CompanionPopulationAdmissionCoordinator populationCoordinator;
    private final PopulationGroupRegistry groupRegistry;
    private final PopulationGroupRepository groupRepository;
    private final ConcurrentHashMap<UUID, PreparedTransition> prepared = new ConcurrentHashMap<>();
    private final AtomicBoolean recoveryReady = new AtomicBoolean(false);

    public UnifiedPopulationTransitionCoordinator(
            @Nonnull CompanionPopulationAdmissionCoordinator populationCoordinator,
            @Nonnull PopulationGroupRegistry groupRegistry,
            @Nonnull PopulationGroupRepository groupRepository) {
        this.populationCoordinator = Objects.requireNonNull(
                populationCoordinator, "populationCoordinator");
        this.groupRegistry = Objects.requireNonNull(groupRegistry, "groupRegistry");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
    }

    @Nonnull
    public CompletionStage<Preparation> prepare(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        PreparedTransition existing = prepared.get(request.populationOperationId());
        if (existing != null) {
            return CompletableFuture.completedFuture(existing.request().equals(request)
                    ? Preparation.success(request.populationOperationId(), "population-group-already-prepared")
                    : Preparation.denied("population-group-idempotency-conflict"));
        }
        var index = groupRegistry.snapshot();
        if (index.revision() != request.classificationRevision()) {
            return CompletableFuture.completedFuture(Preparation.denied(
                    "population-group-policy-revision-changed"));
        }
        List<String> resolvedOld = request.oldRoleId() == null
                ? List.of() : index.resolveForRole(request.oldRoleId()).stream()
                .map(PopulationGroupDefinitionView::groupId).sorted().toList();
        if (!resolvedOld.equals(request.persistedOldGroupIds())) {
            return CompletableFuture.completedFuture(Preparation.denied(
                    "population-group-old-classification-mismatch"));
        }
        List<String> newGroups = request.newRoleId() == null
                ? List.of() : index.resolveForRole(request.newRoleId()).stream()
                .map(PopulationGroupDefinitionView::groupId).sorted().toList();
        if (request.groupTransition().newOwnerUuid() != null && newGroups.isEmpty()) {
            return CompletableFuture.completedFuture(Preparation.denied(
                    "population-group-target-role-unresolved"));
        }
        Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas;
        try {
            deltas = deltas(request.groupTransition(), resolvedOld, newGroups);
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(Preparation.denied(
                    "population-group-definition-unavailable"));
        }
        List<PopulationGroupRepository.ReservationEvidence> evidence = evidence(deltas, index);
        long nowMs = request.nowMs();
        String groupOperationId = UUID.nameUUIDFromBytes(
                (request.populationOperationId() + ":groups")
                        .getBytes(StandardCharsets.UTF_8)).toString();
        PopulationGroupOperationRecord operation = new PopulationGroupOperationRecord(
                groupOperationId, request.populationOperationId().toString(),
                request.ownerPlan().transition().profileId(), request.operationType(),
                PopulationGroupOperationRecord.State.PREPARED,
                request.ownerPlan().baselineState().revision(), request.classificationRevision(),
                request.groupTransition().oldOwnerUuid(), request.groupTransition().newOwnerUuid(),
                request.oldRoleId(), request.newRoleId(), resolvedOld, newGroups,
                name(request.groupTransition().oldLifecycle()),
                name(request.groupTransition().newLifecycle()),
                request.groupTransition().oldOwnershipWorldName(),
                request.groupTransition().newOwnershipWorldName(), null, "PREPARING",
                nowMs, nowMs, 0L);
        PopulationGroupClassificationRecord replacement = new PopulationGroupClassificationRecord(
                request.ownerPlan().transition().profileId(), request.newRoleId(), newGroups,
                request.classificationRevision(),
                request.newRoleId() == null
                        ? PopulationGroupClassificationRecord.Status.UNRESOLVED
                        : PopulationGroupClassificationRecord.Status.RESOLVED,
                "unified_population_admission", request.classificationCreatedAtMs(), nowMs);
        PopulationGroupRepository.ClassificationMutation classification =
                new PopulationGroupRepository.ClassificationMutation(
                        request.expectedClassificationRevision(), replacement);
        return populationCoordinator.prepareWithGroupsAsync(
                        request.ownerPlan(), request.claimRequest(), request.lookupSession(),
                        groupRepository, operation, evidence)
                .thenApply(result -> register(request, result, operation, classification));
    }

    @Nonnull
    public Claim claim(@Nonnull UUID populationOperationId,
                       long currentClassificationRevision,
                       long currentOwnerSettingsRevision,
                       @Nonnull ClaimLookupSession refreshedSession) {
        PreparedTransition transition = prepared.get(
                Objects.requireNonNull(populationOperationId, "populationOperationId"));
        if (transition == null) return Claim.denied("population-group-capability-unavailable");
        if (transition.request().classificationRevision() != currentClassificationRevision) {
            cancel(populationOperationId, "population-group-policy-revision-changed");
            return Claim.denied("population-group-policy-revision-changed");
        }
        boolean claimed = populationCoordinator.claimForApply(
                transition.preparedAdmission(), currentOwnerSettingsRevision,
                Objects.requireNonNull(refreshedSession, "refreshedSession"));
        if (!claimed) {
            cancel(populationOperationId, "population-group-claim-denied");
            return Claim.denied("population-group-claim-denied");
        }
        transition.claimed().set(true);
        return Claim.success();
    }

    @Nonnull
    public CompletionStage<Commit> commit(@Nonnull UUID populationOperationId) {
        PreparedTransition transition = prepared.get(
                Objects.requireNonNull(populationOperationId, "populationOperationId"));
        if (transition == null) {
            return CompletableFuture.completedFuture(Commit.denied(
                    "population-group-capability-unavailable"));
        }
        if (!transition.claimed().get()) {
            return CompletableFuture.completedFuture(Commit.denied(
                    "population-group-capability-not-claimed"));
        }
        return populationCoordinator.commitWithGroupsAsync(
                        transition.preparedAdmission(), groupRepository,
                        transition.groupOperation().operationId(), transition.classification(),
                        System.currentTimeMillis())
                .thenApply(result -> {
                    if (result == null || !result.committed()) {
                        return Commit.denied(result == null
                                ? "population-group-commit-missing" : result.reason());
                    }
                    prepared.remove(populationOperationId, transition);
                    return Commit.success(result);
                });
    }

    @Nonnull
    public CompletionStage<Void> cancel(@Nonnull UUID populationOperationId,
                                        @Nonnull String reason) {
        PreparedTransition transition = prepared.remove(
                Objects.requireNonNull(populationOperationId, "populationOperationId"));
        if (transition == null) return CompletableFuture.completedFuture(null);
        CompletionStage<Boolean> population = populationCoordinator.cancelAsync(
                transition.preparedAdmission(), requireText(reason, "reason"));
        CompletionStage<Boolean> groups = compensate(transition.groupOperation(), reason);
        return population.handle((ignored, failure) -> null)
                .thenCombine(groups.handle((ignored, failure) -> null), (left, right) -> null);
    }

    @Nonnull
    public CompletionStage<Recovery> recover() {
        recoveryReady.set(false);
        final List<PopulationGroupOperationRecord> rows;
        try {
            rows = groupRepository.loadRecoverableOperations();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new Recovery(0, 0, 1, false));
        }
        CompletionStage<Recovery> chain = CompletableFuture.completedFuture(
                new Recovery(rows.size(), 0, 0, false));
        for (PopulationGroupOperationRecord row : rows) {
            chain = chain.thenCompose(report -> reconcile(row).handle((ok, failure) ->
                    failure == null && Boolean.TRUE.equals(ok)
                            ? report.successOne() : report.failureOne()));
        }
        return chain.thenApply(report -> {
            boolean ready = report.failed() == 0;
            recoveryReady.set(ready);
            return new Recovery(report.scanned(), report.succeeded(), report.failed(), ready);
        });
    }

    public boolean ready() {
        return recoveryReady.get() && !groupRegistry.snapshot().definitions().isEmpty();
    }

    private Preparation register(Request request,
                                 CompanionPopulationPreparationResult result,
                                 PopulationGroupOperationRecord operation,
                                 PopulationGroupRepository.ClassificationMutation classification) {
        if (result == null || !result.allowed() || result.preparedAdmission() == null) {
            return Preparation.denied(result == null
                    ? "population-group-prepare-missing" : result.reason());
        }
        PreparedTransition candidate = new PreparedTransition(
                request, result.preparedAdmission(), operation, classification,
                new AtomicBoolean(false));
        PreparedTransition raced = prepared.putIfAbsent(request.populationOperationId(), candidate);
        if (raced != null) {
            populationCoordinator.cancelAsync(result.preparedAdmission(),
                    "population-group-duplicate-preparation");
            return raced.request().equals(request)
                    ? Preparation.success(request.populationOperationId(),
                            "population-group-already-prepared")
                    : Preparation.denied("population-group-idempotency-conflict");
        }
        return Preparation.success(request.populationOperationId(), result.reason());
    }

    private Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas(
            PopulationGroupTransition transition, List<String> oldGroups, List<String> newGroups) {
        var index = groupRegistry.snapshot();
        TreeMap<PopulationGroupBucket, PopulationGroupCountDelta> deltas = new TreeMap<>();
        if (transition.oldOwnerUuid() != null && transition.oldLifecycle() != null) {
            int owned = PopulationGroupLifecycleClassifier.consumesOwned(
                    transition.oldLifecycle()) ? -1 : 0;
            int active = PopulationGroupLifecycleClassifier.consumesActive(
                    transition.oldLifecycle()) ? -1 : 0;
            for (String groupId : oldGroups) {
                PopulationGroupDefinitionView definition = index.getDefinition(groupId).orElseThrow();
                PopulationGroupBucket bucket = PopulationGroupBucket.of(
                        transition.oldOwnerUuid(), definition,
                        transition.oldOwnershipWorldName());
                deltas.merge(bucket, new PopulationGroupCountDelta(owned, active),
                        PopulationGroupCountDelta::plus);
            }
        }
        if (transition.newOwnerUuid() != null && transition.newLifecycle() != null) {
            int owned = PopulationGroupLifecycleClassifier.consumesOwned(
                    transition.newLifecycle()) ? 1 : 0;
            int active = PopulationGroupLifecycleClassifier.consumesActive(
                    transition.newLifecycle()) ? 1 : 0;
            for (String groupId : newGroups) {
                PopulationGroupDefinitionView definition = index.getDefinition(groupId).orElseThrow();
                PopulationGroupBucket bucket = PopulationGroupBucket.of(
                        transition.newOwnerUuid(), definition,
                        transition.newOwnershipWorldName());
                deltas.merge(bucket, new PopulationGroupCountDelta(owned, active),
                        PopulationGroupCountDelta::plus);
            }
        }
        deltas.entrySet().removeIf(entry -> entry.getValue().isZero());
        return Map.copyOf(deltas);
    }

    private List<PopulationGroupRepository.ReservationEvidence> evidence(
            Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas,
            com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex index) {
        List<PopulationGroupRepository.ReservationEvidence> rows = new ArrayList<>();
        for (var entry : deltas.entrySet()) {
            PopulationGroupDefinitionView definition = index.getDefinition(
                    entry.getKey().groupId()).orElseThrow();
            rows.add(new PopulationGroupRepository.ReservationEvidence(
                    entry.getKey().ownerUuid(), entry.getKey().groupId(),
                    definition.scope() == PopulationGroupScope.GLOBAL
                            ? PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL
                            : PopulationGroupCountEvidenceRecord.ScopeKind.PER_WORLD,
                    entry.getKey().ownershipWorldName(), entry.getValue().owned(),
                    entry.getValue().active(), saturating(definition.maxOwnedPerOwner()),
                    saturating(definition.maxActivePerOwner()), index.revision()));
        }
        return List.copyOf(rows);
    }

    private CompletionStage<Boolean> reconcile(PopulationGroupOperationRecord operation) {
        if (operation.state() == PopulationGroupOperationRecord.State.PREPARED) {
            return advance(operation.operationId(), PopulationGroupOperationRecord.State.PREPARED,
                    PopulationGroupOperationRecord.State.CANCELED, "recovered-unclaimed-reservation");
        }
        return compensate(operation, "recovered-uncommitted-reservation");
    }

    private CompletionStage<Boolean> compensate(PopulationGroupOperationRecord operation,
                                                  String reason) {
        if (operation.state().isTerminal()) return CompletableFuture.completedFuture(true);
        if (operation.state() == PopulationGroupOperationRecord.State.PREPARED) {
            return advance(operation.operationId(), PopulationGroupOperationRecord.State.PREPARED,
                    PopulationGroupOperationRecord.State.CANCELED, reason);
        }
        CompletionStage<Boolean> start = operation.state()
                == PopulationGroupOperationRecord.State.COMPENSATING
                ? CompletableFuture.completedFuture(true)
                : advance(operation.operationId(), operation.state(),
                        PopulationGroupOperationRecord.State.COMPENSATING, reason);
        return start.thenCompose(ok -> ok
                ? advance(operation.operationId(), PopulationGroupOperationRecord.State.COMPENSATING,
                        PopulationGroupOperationRecord.State.FAILED, reason)
                : CompletableFuture.completedFuture(false));
    }

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
                && outcome.value().status() != PopulationGroupRepository.Status.CONFLICT
                && outcome.value().status() != PopulationGroupRepository.Status.INVALID_STATE);
    }

    @Nullable
    private static String name(@Nullable Enum<?> value) { return value == null ? null : value.name(); }
    private static int saturating(long value) { return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value; }
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public record Request(@Nonnull UUID populationOperationId,
                          @Nonnull OwnerPopulationAdmissionPlan ownerPlan,
                          @Nonnull ClaimAdmissionRequest claimRequest,
                          @Nonnull ClaimLookupSession lookupSession,
                          @Nonnull PopulationGroupTransition groupTransition,
                          @Nullable String oldRoleId,
                          @Nullable String newRoleId,
                          @Nonnull List<String> persistedOldGroupIds,
                          @Nullable Long expectedClassificationRevision,
                          long classificationRevision,
                          long classificationCreatedAtMs,
                          @Nonnull String operationType,
                          long nowMs) {
        public Request {
            populationOperationId = Objects.requireNonNull(populationOperationId,
                    "populationOperationId");
            ownerPlan = Objects.requireNonNull(ownerPlan, "ownerPlan");
            claimRequest = Objects.requireNonNull(claimRequest, "claimRequest");
            lookupSession = Objects.requireNonNull(lookupSession, "lookupSession");
            groupTransition = Objects.requireNonNull(groupTransition, "groupTransition");
            persistedOldGroupIds = Objects.requireNonNull(
                    persistedOldGroupIds, "persistedOldGroupIds")
                    .stream().sorted().distinct().toList();
            operationType = requireText(operationType, "operationType");
            if (classificationRevision < 0L || classificationCreatedAtMs < 0L || nowMs < 0L) {
                throw new IllegalArgumentException("Revisions and timestamps cannot be negative");
            }
        }
    }

    public record Preparation(boolean prepared, @Nonnull String reason,
                              @Nullable UUID populationOperationId) {
        static Preparation success(UUID id, String reason) { return new Preparation(true, reason, id); }
        static Preparation denied(String reason) { return new Preparation(false, reason, null); }
    }

    public record Claim(boolean claimed, @Nonnull String reason) {
        static Claim success() { return new Claim(true, "population-group-claimed"); }
        static Claim denied(String reason) { return new Claim(false, reason); }
    }

    public record Commit(boolean committed, @Nonnull String reason,
                         @Nullable CompanionPopulationCommitResult populationResult) {
        static Commit success(CompanionPopulationCommitResult result) {
            return new Commit(true, "population-group-committed", result);
        }
        static Commit denied(String reason) { return new Commit(false, reason, null); }
    }

    public record Recovery(int scanned, int succeeded, int failed, boolean ready) {
        Recovery successOne() { return new Recovery(scanned, succeeded + 1, failed, ready); }
        Recovery failureOne() { return new Recovery(scanned, succeeded, failed + 1, ready); }
    }

    private record PreparedTransition(@Nonnull Request request,
                                      @Nonnull PreparedCompanionPopulationAdmission preparedAdmission,
                                      @Nonnull PopulationGroupOperationRecord groupOperation,
                                      @Nonnull PopulationGroupRepository.ClassificationMutation classification,
                                      @Nonnull AtomicBoolean claimed) {
    }
}
