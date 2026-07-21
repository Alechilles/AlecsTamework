package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupBucket;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDelta;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupLifecycleClassifier;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
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
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Production extension that makes every owner-population admission reserve and commit its
 * persisted population-group classification under the same SQLite transaction boundary.
 */
public final class PopulationGroupOwnerAdmissionExtension {
    private final OwnerPopulationAdmissionCoordinator ownerCoordinator;
    private final PopulationGroupRegistry registry;
    private final PopulationGroupRepository repository;
    private final NpcProfileRepository profiles;
    private final ConcurrentHashMap<UUID, PreparedGroup> prepared = new ConcurrentHashMap<>();

    public PopulationGroupOwnerAdmissionExtension(
            @Nonnull OwnerPopulationAdmissionCoordinator ownerCoordinator,
            @Nonnull PopulationGroupRegistry registry,
            @Nonnull PopulationGroupRepository repository,
            @Nonnull NpcProfileRepository profiles) {
        this.ownerCoordinator = Objects.requireNonNull(ownerCoordinator, "ownerCoordinator");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Nonnull
    CompletableFuture<OwnerPopulationPreparationResult> prepareReservedAsync(
            @Nonnull OwnerPopulationReservationPreparation reserved) {
        if (!reserved.allowed()) {
            return CompletableFuture.completedFuture(new OwnerPopulationPreparationResult(
                    false, reserved.reason(), reserved.decision(), null));
        }
        final PreparedGroup draft;
        try {
            draft = draft(reserved);
        } catch (RuntimeException failure) {
            ownerCoordinator.indexCancel(reserved.decision().reservation());
            return CompletableFuture.completedFuture(new OwnerPopulationPreparationResult(
                    false, reason(failure), reserved.decision(), null));
        }
        return ownerCoordinator.groupCompositeCoordinator().prepareReservedAsync(
                        reserved, repository, draft.operation(), draft.evidence())
                .thenApply(result -> {
                    if (result != null && result.allowed() && result.preparedAdmission() != null) {
                        prepared.put(result.preparedAdmission().operationId(), draft);
                    }
                    return result;
                });
    }

    boolean owns(@Nonnull PreparedOwnerPopulationAdmission admission) {
        return prepared.containsKey(admission.operationId());
    }

    boolean claimAllowed(@Nonnull PreparedOwnerPopulationAdmission admission) {
        PreparedGroup group = prepared.get(admission.operationId());
        return group != null && registry.snapshot().revision() == group.policyRevision();
    }

    @Nonnull
    CompletableFuture<OwnerPopulationCommitResult> commitAsync(
            @Nonnull PreparedOwnerPopulationAdmission admission) {
        PreparedGroup group = prepared.get(admission.operationId());
        if (group == null) {
            return CompletableFuture.completedFuture(new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.INVALID_CAPABILITY,
                    "population-group-capability-missing", null));
        }
        return ownerCoordinator.groupCompositeCoordinator().commitPopulationGroupsAsync(
                        admission, repository, group.operation().operationId(),
                        group.classification(), System.currentTimeMillis())
                .whenComplete((result, failure) -> {
                    if (failure == null && result != null
                            && result.status() != OwnerPopulationCommitResult.Status.SOURCE_FINALIZATION_PENDING) {
                        prepared.remove(admission.operationId(), group);
                    }
                });
    }

    @Nonnull
    CompletableFuture<Boolean> cancelAsync(
            @Nonnull PreparedOwnerPopulationAdmission admission,
            @Nonnull String reason) {
        PreparedGroup group = prepared.remove(admission.operationId());
        CompletableFuture<Boolean> owner = ownerCoordinator.cancelOwnerOnlyAsync(admission, reason);
        if (group == null) return owner;
        return owner.thenCombine(compensate(group.operation().operationId(), reason),
                (ownerOk, groupOk) -> Boolean.TRUE.equals(ownerOk) && Boolean.TRUE.equals(groupOk));
    }

    @Nonnull
    CompletableFuture<Boolean> completeSourceFinalizationAsync(
            @Nonnull PreparedOwnerPopulationAdmission admission) {
        PreparedGroup group = prepared.get(admission.operationId());
        if (group == null) return CompletableFuture.completedFuture(false);
        return ownerCoordinator.completeSourceFinalizationOwnerOnlyAsync(admission)
                .thenCompose(ownerOk -> Boolean.TRUE.equals(ownerOk)
                        ? advance(group.operation().operationId(), PopulationGroupOperationRecord.State.APPLIED,
                                PopulationGroupOperationRecord.State.COMMITTED,
                                "source-finalization-complete")
                        : CompletableFuture.completedFuture(false))
                .whenComplete((ok, failure) -> {
                    if (failure == null && Boolean.TRUE.equals(ok)) {
                        prepared.remove(admission.operationId(), group);
                    }
                });
    }

    @Nonnull
    CompletableFuture<Boolean> beginCompensationAsync(
            @Nonnull PreparedOwnerPopulationAdmission admission,
            @Nonnull String reason) {
        PreparedGroup group = prepared.get(admission.operationId());
        if (group == null) return CompletableFuture.completedFuture(false);
        return ownerCoordinator.beginCompensationOwnerOnlyAsync(admission, reason)
                .thenCombine(toCompensating(group.operation().operationId(), reason),
                        (ownerOk, groupOk) -> Boolean.TRUE.equals(ownerOk) && Boolean.TRUE.equals(groupOk));
    }

    @Nonnull
    CompletableFuture<Boolean> completeCompensationAsync(
            @Nonnull PreparedOwnerPopulationAdmission admission,
            @Nonnull String reason) {
        PreparedGroup group = prepared.remove(admission.operationId());
        if (group == null) return CompletableFuture.completedFuture(false);
        return ownerCoordinator.completeCompensationOwnerOnlyAsync(admission, reason)
                .thenCombine(advance(group.operation().operationId(),
                                PopulationGroupOperationRecord.State.COMPENSATING,
                                PopulationGroupOperationRecord.State.FAILED, reason),
                        (ownerOk, groupOk) -> Boolean.TRUE.equals(ownerOk) && Boolean.TRUE.equals(groupOk));
    }

    @Nonnull
    public CompletableFuture<RecoveryReport> recover() {
        final List<PopulationGroupOperationRecord> operations;
        try {
            operations = repository.loadRecoverableOperations();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(0, 0, 1, false));
        }
        final Map<String, CompanionPopulationOperationRecord> nonterminalOwners;
        try {
            nonterminalOwners = ownerCoordinator.populationRepository().loadNonterminalOperations()
                    .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                            CompanionPopulationOperationRecord::operationId,
                            operation -> operation,
                            (left, right) -> left));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(
                    operations.size(), 0, Math.max(1, operations.size()), false));
        }
        CompletableFuture<RecoveryReport> chain = CompletableFuture.completedFuture(
                new RecoveryReport(operations.size(), 0, 0, false));
        for (PopulationGroupOperationRecord operation : operations) {
            chain = chain.thenCompose(report -> recover(operation, nonterminalOwners).handle((ok, failure) ->
                    failure == null && Boolean.TRUE.equals(ok)
                            ? report.successOne() : report.failureOne()));
        }
        return chain.thenCompose(this::reconcileClassifications)
                .thenApply(report -> new RecoveryReport(
                        report.scanned(), report.succeeded(), report.failed(), report.failed() == 0));
    }

    private CompletableFuture<RecoveryReport> reconcileClassifications(RecoveryReport initial) {
        final List<com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord> states;
        try {
            states = ownerCoordinator.populationRepository().loadAllStates();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(initial.failureOne());
        }
        CompletableFuture<RecoveryReport> chain = CompletableFuture.completedFuture(initial);
        for (var state : states) {
            if (CompanionLifecycleState.RELEASED.name().equals(state.lifecycleState())) continue;
            chain = chain.thenCompose(report -> reconcileClassification(state)
                    .handle((resolved, failure) -> failure == null && Boolean.TRUE.equals(resolved)
                            ? report.successOne() : report.failureOne()));
        }
        return chain;
    }

    private CompletableFuture<Boolean> reconcileClassification(
            com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord state) {
        var index = registry.snapshot();
        PopulationGroupClassificationRecord existing = classification(state.profileId());
        String role = profileRole(state.profileId());
        boolean resolved = role != null || index.definitions().isEmpty();
        List<String> groups = resolve(index, role);
        long now = System.currentTimeMillis();
        PopulationGroupClassificationRecord replacement = new PopulationGroupClassificationRecord(
                state.profileId(), role, groups, index.revision(),
                role != null ? PopulationGroupClassificationRecord.Status.RESOLVED
                        : PopulationGroupClassificationRecord.Status.UNRESOLVED,
                "population_group_reconciliation",
                existing == null ? now : existing.createdAtMs(), now);
        var submission = repository.replaceClassificationAsync(
                new PopulationGroupRepository.ClassificationMutation(
                        existing == null ? null : existing.classificationRevision(), replacement));
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(false);
        }
        return submission.completion().thenApply(outcome -> outcome != null
                && outcome.isCommitted() && outcome.value() != null
                && (outcome.value().status() == PopulationGroupRepository.Status.APPLIED
                || outcome.value().status() == PopulationGroupRepository.Status.IDEMPOTENT)
                && resolved);
    }

    private PreparedGroup draft(OwnerPopulationReservationPreparation reserved) {
        OwnerPopulationAdmissionPlan plan = reserved.plan();
        var transition = plan.transition();
        var index = registry.snapshot();
        PopulationGroupClassificationRecord existing = classification(transition.profileId());
        PopulationGroupRoleContext context = plan.populationGroupRoleContext();
        String profileRole = profileRole(transition.profileId());
        String oldRole = first(context == null ? null : context.oldRoleId(),
                existing == null ? null : existing.roleId(), profileRole);
        String newRole = first(context == null ? null : context.newRoleId(), oldRole, profileRole);
        if (!index.definitions().isEmpty()
                && transition.newOwnerId() != null && newRole == null) {
            throw new IllegalStateException("population-group-target-role-unresolved");
        }
        List<String> oldGroups = existing == null
                ? resolve(index, oldRole) : existing.groupIds();
        List<String> newGroups = resolve(index, newRole);
        CompanionLifecycleState oldLifecycle = lifecycle(plan.baselineState().lifecycleState());
        Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas = deltas(index,
                transition.expectedOwnerId(), oldGroups, plan.baselineState().ownershipWorldName(),
                oldLifecycle, transition.newOwnerId(), newGroups,
                transition.destinationWorldName(), transition.lifecycleState());
        List<PopulationGroupRepository.ReservationEvidence> evidence = evidence(index, deltas);
        UUID ownerOperationId = reserved.decision().reservation().tokenId();
        String groupOperationId = UUID.nameUUIDFromBytes(
                (ownerOperationId + ":groups").getBytes(StandardCharsets.UTF_8)).toString();
        long now = System.currentTimeMillis();
        PopulationGroupOperationRecord operation = new PopulationGroupOperationRecord(
                groupOperationId, ownerOperationId.toString(), transition.profileId(),
                transition.operation().name(), PopulationGroupOperationRecord.State.PREPARED,
                plan.baselineState().revision(), index.revision(), transition.expectedOwnerId(),
                transition.newOwnerId(), oldRole, newRole, oldGroups, newGroups,
                oldLifecycle.name(), transition.lifecycleState().name(),
                plan.baselineState().ownershipWorldName(), transition.destinationWorldName(),
                null, "PREPARING", now, now, 0L);
        PopulationGroupClassificationRecord replacement = new PopulationGroupClassificationRecord(
                transition.profileId(), newRole, newGroups, index.revision(),
                newRole == null ? PopulationGroupClassificationRecord.Status.UNRESOLVED
                        : PopulationGroupClassificationRecord.Status.RESOLVED,
                "owner_population_admission", existing == null ? now : existing.createdAtMs(), now);
        return new PreparedGroup(operation, evidence,
                new PopulationGroupRepository.ClassificationMutation(
                        existing == null ? null : existing.classificationRevision(), replacement),
                index.revision());
    }

    private PopulationGroupClassificationRecord classification(String profileId) {
        try {
            return repository.findClassification(profileId);
        } catch (Exception failure) {
            throw new IllegalStateException("population-group-classification-unavailable", failure);
        }
    }

    private String profileRole(String profileId) {
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
        return profile == null ? null : profile.roleId();
    }

    private static List<String> resolve(
            com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex index,
            @Nullable String role) {
        return role == null ? List.of() : index.resolveForRole(role).stream()
                .map(PopulationGroupDefinitionView::groupId).sorted().toList();
    }

    private static Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas(
            com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex index,
            UUID oldOwner, List<String> oldGroups, String oldWorld, CompanionLifecycleState oldState,
            UUID newOwner, List<String> newGroups, String newWorld, CompanionLifecycleState newState) {
        TreeMap<PopulationGroupBucket, PopulationGroupCountDelta> result = new TreeMap<>();
        add(index, result, oldOwner, oldGroups, oldWorld, oldState, -1);
        add(index, result, newOwner, newGroups, newWorld, newState, 1);
        result.entrySet().removeIf(entry -> entry.getValue().isZero());
        return Map.copyOf(result);
    }

    private static void add(
            com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex index,
            Map<PopulationGroupBucket, PopulationGroupCountDelta> result,
            UUID owner, List<String> groups, String world, CompanionLifecycleState state, int sign) {
        if (owner == null || state == null) return;
        int owned = PopulationGroupLifecycleClassifier.consumesOwned(state) ? sign : 0;
        int active = PopulationGroupLifecycleClassifier.consumesActive(state) ? sign : 0;
        for (String groupId : groups) {
            PopulationGroupDefinitionView definition = index.getDefinition(groupId).orElse(null);
            if (definition == null) continue;
            PopulationGroupBucket bucket = PopulationGroupBucket.of(owner, definition, world);
            result.merge(bucket, new PopulationGroupCountDelta(owned, active),
                    PopulationGroupCountDelta::plus);
        }
    }

    private static List<PopulationGroupRepository.ReservationEvidence> evidence(
            com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex index,
            Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas) {
        List<PopulationGroupRepository.ReservationEvidence> evidence = new ArrayList<>();
        for (var entry : deltas.entrySet()) {
            PopulationGroupDefinitionView definition = index.getDefinition(
                    entry.getKey().groupId()).orElseThrow();
            evidence.add(new PopulationGroupRepository.ReservationEvidence(
                    entry.getKey().ownerUuid(), entry.getKey().groupId(),
                    definition.scope() == PopulationGroupScope.GLOBAL
                            ? PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL
                            : PopulationGroupCountEvidenceRecord.ScopeKind.PER_WORLD,
                    entry.getKey().ownershipWorldName(), entry.getValue().owned(),
                    entry.getValue().active(), limit(definition.maxOwnedPerOwner()),
                    limit(definition.maxActivePerOwner()), index.revision()));
        }
        return List.copyOf(evidence);
    }

    private CompletableFuture<Boolean> recover(
            PopulationGroupOperationRecord operation,
            Map<String, CompanionPopulationOperationRecord> nonterminalOwners) {
        boolean ownerStillNonterminal = operation.populationOperationId() != null
                && nonterminalOwners.containsKey(operation.populationOperationId());
        return switch (operation.state()) {
            case PREPARED -> advance(operation.operationId(), operation.state(),
                    PopulationGroupOperationRecord.State.CANCELED, "recovered-unclaimed-reservation");
            case APPLIED -> ownerStillNonterminal
                    ? CompletableFuture.completedFuture(false)
                    : advance(operation.operationId(), operation.state(),
                            PopulationGroupOperationRecord.State.COMMITTED,
                            "recovered-owner-commit");
            case APPLYING, QUARANTINED -> CompletableFuture.completedFuture(false);
            case COMPENSATING -> advance(operation.operationId(), operation.state(),
                    PopulationGroupOperationRecord.State.FAILED, "recovered-compensation");
            case COMMITTED, CANCELED, FAILED -> CompletableFuture.completedFuture(true);
        };
    }

    private CompletableFuture<Boolean> compensate(String operationId, String reason) {
        return toCompensating(operationId, reason).thenCompose(ok -> Boolean.TRUE.equals(ok)
                ? advance(operationId, PopulationGroupOperationRecord.State.COMPENSATING,
                        PopulationGroupOperationRecord.State.FAILED, reason)
                : CompletableFuture.completedFuture(false));
    }

    private CompletableFuture<Boolean> toCompensating(String operationId, String reason) {
        final PopulationGroupOperationRecord operation;
        try {
            operation = repository.findOperation(operationId);
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(false);
        }
        if (operation == null) return CompletableFuture.completedFuture(false);
        if (operation.state() == PopulationGroupOperationRecord.State.COMPENSATING) {
            return CompletableFuture.completedFuture(true);
        }
        if (operation.state() == PopulationGroupOperationRecord.State.PREPARED) {
            return advance(operationId, operation.state(), PopulationGroupOperationRecord.State.CANCELED, reason);
        }
        return advance(operationId, operation.state(), PopulationGroupOperationRecord.State.COMPENSATING, reason);
    }

    private CompletableFuture<Boolean> advance(String operationId,
                                               PopulationGroupOperationRecord.State expected,
                                               PopulationGroupOperationRecord.State next,
                                               String reason) {
        try {
            var submission = repository.advanceOperationAsync(
                    operationId, expected, next, reason, System.currentTimeMillis());
            if (submission == null || submission.completion() == null) {
                return CompletableFuture.completedFuture(false);
            }
            return submission.completion().thenApply(outcome -> outcome != null
                    && outcome.isCommitted() && outcome.value() != null
                    && outcome.value().status() != PopulationGroupRepository.Status.CONFLICT
                    && outcome.value().status() != PopulationGroupRepository.Status.INVALID_STATE);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static CompanionLifecycleState lifecycle(String value) {
        try {
            return CompanionLifecycleState.valueOf(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("population-group-lifecycle-unresolved", failure);
        }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static int limit(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "population-group-admission-unavailable" : failure.getMessage();
    }

    private record PreparedGroup(
            PopulationGroupOperationRecord operation,
            List<PopulationGroupRepository.ReservationEvidence> evidence,
            PopulationGroupRepository.ClassificationMutation classification,
            long policyRevision) {
    }

    public record RecoveryReport(int scanned, int succeeded, int failed, boolean ready) {
        private RecoveryReport successOne() {
            return new RecoveryReport(scanned, succeeded + 1, failed, ready);
        }

        private RecoveryReport failureOne() {
            return new RecoveryReport(scanned, succeeded, failed + 1, ready);
        }
    }
}
