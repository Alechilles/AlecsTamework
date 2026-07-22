package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupLimitChangedEvent;
import com.alechilles.alecstamework.api.PopulationGroupMembershipChangedEvent;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupBucket;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDelta;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupLifecycleClassifier;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupEventSink;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
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
    private final PopulationGroupEventSink events;
    private final ConcurrentHashMap<UUID, PreparedGroup> prepared = new ConcurrentHashMap<>();

    public PopulationGroupOwnerAdmissionExtension(
            @Nonnull OwnerPopulationAdmissionCoordinator ownerCoordinator,
            @Nonnull PopulationGroupRegistry registry,
            @Nonnull PopulationGroupRepository repository,
            @Nonnull NpcProfileRepository profiles) {
        this(ownerCoordinator, registry, repository, profiles, PopulationGroupEventSink.noop());
    }

    public PopulationGroupOwnerAdmissionExtension(
            @Nonnull OwnerPopulationAdmissionCoordinator ownerCoordinator,
            @Nonnull PopulationGroupRegistry registry,
            @Nonnull PopulationGroupRepository repository,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull PopulationGroupEventSink events) {
        this.ownerCoordinator = Objects.requireNonNull(ownerCoordinator, "ownerCoordinator");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.events = Objects.requireNonNull(events, "events");
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
                .thenCompose(result -> {
                    if (result == null || !result.committed()
                            || result.status()
                            == OwnerPopulationCommitResult.Status.SOURCE_FINALIZATION_PENDING) {
                        return CompletableFuture.completedFuture(result);
                    }
                    prepared.remove(admission.operationId(), group);
                    return emitMembership(group, false).thenApply(ignored -> result);
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
                .thenCompose(ok -> {
                    if (!Boolean.TRUE.equals(ok)) return CompletableFuture.completedFuture(false);
                    prepared.remove(admission.operationId(), group);
                    return emitMembership(group, false).thenApply(ignored -> true);
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
        return recover(true);
    }

    @Nonnull
    public CompletableFuture<RecoveryReport> recover(boolean recovered) {
        final List<PopulationGroupOperationRecord> operations;
        final List<PopulationGroupOperationRecord> unemitted;
        try {
            operations = repository.loadRecoverableOperations();
            unemitted = repository.loadUnemittedMembershipOperations();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(0, 0, 1, false));
        }
        final Map<String, CompanionPopulationStateRecord> states;
        try {
            states = ownerCoordinator.populationRepository().loadAllStates().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            CompanionPopulationStateRecord::profileId, state -> state,
                            (left, right) -> left));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(
                    operations.size(), 0, Math.max(1, operations.size()), false));
        }
        CompletableFuture<RecoveryReport> chain = CompletableFuture.completedFuture(
                new RecoveryReport(operations.size() + unemitted.size(), 0, 0, false));
        for (PopulationGroupOperationRecord operation : operations) {
            chain = chain.thenCompose(report -> recover(
                    operation, states.get(operation.profileId())).handle((ok, failure) ->
                    failure == null && Boolean.TRUE.equals(ok)
                            ? report.successOne() : report.failureOne()));
        }
        for (PopulationGroupOperationRecord operation : unemitted) {
            chain = chain.thenCompose(report -> emitMembership(operation, true)
                    .handle((ignored, failure) -> failure == null
                            ? report.successOne() : report.failureOne()));
        }
        return chain.thenCompose(report -> reconcileClassifications(report, recovered))
                .thenApply(report -> new RecoveryReport(
                        report.scanned(), report.succeeded(), report.failed(), report.failed() == 0));
    }

    private CompletableFuture<RecoveryReport> reconcileClassifications(
            RecoveryReport initial, boolean recovered) {
        final List<com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord> states;
        try {
            states = ownerCoordinator.populationRepository().loadAllStates();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(initial.failureOne());
        }
        CompletableFuture<RecoveryReport> chain = CompletableFuture.completedFuture(initial);
        for (var state : states) {
            if (CompanionLifecycleState.RELEASED.name().equals(state.lifecycleState())) continue;
            chain = chain.thenCompose(report -> reconcileClassification(state, recovered)
                    .handle((resolved, failure) -> failure == null && Boolean.TRUE.equals(resolved)
                            ? report.successOne() : report.failureOne()));
        }
        return chain;
    }

    private CompletableFuture<Boolean> reconcileClassification(
            com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord state,
            boolean recovered) {
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
        return submission.completion().thenCompose(outcome -> {
            boolean committed = outcome != null && outcome.isCommitted()
                    && outcome.value() != null
                    && (outcome.value().status() == PopulationGroupRepository.Status.APPLIED
                    || outcome.value().status() == PopulationGroupRepository.Status.IDEMPOTENT);
            if (committed && resolved && state.ownerUuid() != null
                    && (existing == null || !existing.groupIds().equals(groups)
                    || !Objects.equals(existing.roleId(), role))) {
                return emitReconciledMembership(state.ownerUuid(), state.profileId(), role,
                        existing, groups, index.revision(), recovered).thenApply(ignored -> true);
            }
            return CompletableFuture.completedFuture(committed && resolved);
        });
    }

    private PreparedGroup draft(OwnerPopulationReservationPreparation reserved) {
        OwnerPopulationAdmissionPlan plan = reserved.plan();
        var transition = plan.transition();
        var index = registry.snapshot();
        PopulationGroupClassificationRecord existing = classification(transition.profileId());
        PopulationGroupRoleContext context = plan.populationGroupRoleContext();
        String profileRole = profileRole(transition.profileId());
        String oldRole = first(existing == null ? null : existing.roleId(),
                context == null ? null : context.oldRoleId(), profileRole);
        String newRole = first(context == null ? null : context.newRoleId(), profileRole, oldRole);
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
                existing == null ? 0L : existing.classificationRevision(),
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
            @Nullable CompanionPopulationStateRecord state) {
        CompanionPopulationOperationRecord owner = ownerOperation(operation);
        return switch (operation.state()) {
            case PREPARED -> advance(operation.operationId(), operation.state(),
                    PopulationGroupOperationRecord.State.CANCELED, "recovered-unclaimed-reservation");
            case APPLYING, QUARANTINED -> recoverApplying(operation, owner, state);
            case APPLIED -> recoverApplied(operation, owner, state);
            case COMPENSATING -> ownerRolledBack(operation, owner) && matchesOld(operation, state)
                    ? advance(operation.operationId(), operation.state(),
                            PopulationGroupOperationRecord.State.FAILED,
                            "recovered-compensation")
                    : CompletableFuture.completedFuture(false);
            case COMMITTED, CANCELED, FAILED -> CompletableFuture.completedFuture(true);
        };
    }

    private CompletableFuture<Boolean> recoverApplying(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationOperationRecord owner,
            @Nullable CompanionPopulationStateRecord state) {
        if (ownerCommitted(operation, owner) && matchesNew(operation, state)) {
            CompletableFuture<Boolean> applying = operation.state()
                    == PopulationGroupOperationRecord.State.QUARANTINED
                    ? advance(operation.operationId(), operation.state(),
                            PopulationGroupOperationRecord.State.APPLYING,
                            "recovered-owner-commit")
                    : CompletableFuture.completedFuture(true);
            return applying.thenCompose(ok -> Boolean.TRUE.equals(ok)
                            ? advance(operation.operationId(),
                                    PopulationGroupOperationRecord.State.APPLYING,
                                    PopulationGroupOperationRecord.State.APPLIED,
                                    "recovered-owner-commit")
                            : CompletableFuture.completedFuture(false))
                    .thenCompose(ok -> Boolean.TRUE.equals(ok)
                            ? advance(operation.operationId(),
                                    PopulationGroupOperationRecord.State.APPLIED,
                                    PopulationGroupOperationRecord.State.COMMITTED,
                                    "recovered-owner-commit")
                            : CompletableFuture.completedFuture(false))
                    .thenCompose(ok -> Boolean.TRUE.equals(ok)
                            ? emitMembership(operation, true).thenApply(ignored -> true)
                            : CompletableFuture.completedFuture(false));
        }
        if (ownerRolledBack(operation, owner) && matchesOld(operation, state)) {
            return toCompensating(operation.operationId(), "recovered-owner-rollback")
                    .thenCompose(ok -> Boolean.TRUE.equals(ok)
                            ? advance(operation.operationId(),
                                    PopulationGroupOperationRecord.State.COMPENSATING,
                                    PopulationGroupOperationRecord.State.FAILED,
                                    "recovered-owner-rollback")
                            : CompletableFuture.completedFuture(false));
        }
        if (operation.state() == PopulationGroupOperationRecord.State.APPLYING
                && owner != null && owner.state().isTerminal()) {
            return advance(operation.operationId(), operation.state(),
                    PopulationGroupOperationRecord.State.QUARANTINED,
                    "recovery-evidence-mismatch").thenApply(ignored -> false);
        }
        return CompletableFuture.completedFuture(false);
    }

    private CompletableFuture<Boolean> recoverApplied(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationOperationRecord owner,
            @Nullable CompanionPopulationStateRecord state) {
        if (ownerCommitted(operation, owner) && matchesNew(operation, state)) {
            return advance(operation.operationId(), operation.state(),
                    PopulationGroupOperationRecord.State.COMMITTED,
                    "recovered-owner-commit").thenCompose(ok -> Boolean.TRUE.equals(ok)
                            ? emitMembership(operation, true).thenApply(ignored -> true)
                            : CompletableFuture.completedFuture(false));
        }
        if (ownerRolledBack(operation, owner) && matchesOld(operation, state)) {
            return toCompensating(operation.operationId(), "recovered-owner-rollback")
                    .thenCompose(ok -> Boolean.TRUE.equals(ok)
                            ? advance(operation.operationId(),
                                    PopulationGroupOperationRecord.State.COMPENSATING,
                                    PopulationGroupOperationRecord.State.FAILED,
                                    "recovered-owner-rollback")
                            : CompletableFuture.completedFuture(false));
        }
        return CompletableFuture.completedFuture(false);
    }

    @Nullable
    private CompanionPopulationOperationRecord ownerOperation(
            PopulationGroupOperationRecord operation) {
        if (operation.populationOperationId() == null) return null;
        try {
            return ownerCoordinator.populationRepository().findOperation(
                    operation.populationOperationId());
        } catch (Exception failure) {
            throw new IllegalStateException("population-owner-operation-unavailable", failure);
        }
    }

    private boolean matchesNew(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationStateRecord state) {
        return matchesState(operation, state, true)
                && matchesClassification(operation, true);
    }

    private boolean matchesOld(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationStateRecord state) {
        return matchesState(operation, state, false)
                && matchesClassification(operation, false);
    }

    private boolean matchesState(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationStateRecord state,
            boolean target) {
        if (state == null) return false;
        long expectedRevision = operation.expectedPopulationRevision() + (target ? 1L : 0L);
        return state.revision() == expectedRevision
                && Objects.equals(state.ownerUuid(), target
                        ? operation.newOwnerUuid() : operation.oldOwnerUuid())
                && Objects.equals(state.lifecycleState(), target
                        ? operation.newLifecycleState() : operation.oldLifecycleState())
                && Objects.equals(normalize(state.ownershipWorldName()), normalize(target
                        ? operation.newOwnershipWorldName()
                        : operation.oldOwnershipWorldName()));
    }

    private boolean matchesClassification(
            PopulationGroupOperationRecord operation, boolean target) {
        PopulationGroupClassificationRecord current;
        try {
            current = repository.findClassification(operation.profileId());
        } catch (Exception failure) {
            return false;
        }
        String role = target ? operation.newRoleId() : operation.oldRoleId();
        List<String> groups = target ? operation.newGroupIds() : operation.oldGroupIds();
        if (current == null) return role == null && groups.isEmpty();
        return Objects.equals(current.roleId(), role)
                && current.groupIds().equals(groups)
                && (!target || current.classificationRevision()
                        == operation.classificationRevision());
    }

    private static boolean ownerCommitted(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationOperationRecord owner) {
        return owner != null
                ? owner.state() == CompanionPopulationOperationRecord.State.COMMITTED
                : operation.populationOperationId() == null;
    }

    private static boolean ownerRolledBack(
            PopulationGroupOperationRecord operation,
            @Nullable CompanionPopulationOperationRecord owner) {
        return owner != null
                ? owner.state() == CompanionPopulationOperationRecord.State.FAILED
                        || owner.state() == CompanionPopulationOperationRecord.State.RETRYABLE
                : operation.populationOperationId() == null;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        if (operation.state() == PopulationGroupOperationRecord.State.FAILED) {
            return CompletableFuture.completedFuture(true);
        }
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
            return submission.completion().thenApply(outcome -> {
                if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                    return false;
                }
                PopulationGroupRepository.OperationResult value = outcome.value();
                if (value.status() != PopulationGroupRepository.Status.CONFLICT
                        && value.status() != PopulationGroupRepository.Status.INVALID_STATE) {
                    return true;
                }
                return value.operation() != null
                        && atOrBeyond(next, value.operation().state());
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static boolean atOrBeyond(
            PopulationGroupOperationRecord.State target,
            PopulationGroupOperationRecord.State actual) {
        return switch (target) {
            case PREPARED -> actual == PopulationGroupOperationRecord.State.PREPARED;
            case APPLYING -> actual == PopulationGroupOperationRecord.State.APPLYING
                    || actual == PopulationGroupOperationRecord.State.APPLIED
                    || actual == PopulationGroupOperationRecord.State.COMMITTED;
            case APPLIED -> actual == PopulationGroupOperationRecord.State.APPLIED
                    || actual == PopulationGroupOperationRecord.State.COMMITTED;
            case COMMITTED -> actual == PopulationGroupOperationRecord.State.COMMITTED;
            case CANCELED -> actual == PopulationGroupOperationRecord.State.CANCELED;
            case COMPENSATING -> actual == PopulationGroupOperationRecord.State.COMPENSATING
                    || actual == PopulationGroupOperationRecord.State.FAILED;
            case QUARANTINED -> actual == PopulationGroupOperationRecord.State.QUARANTINED;
            case FAILED -> actual == PopulationGroupOperationRecord.State.FAILED;
        };
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

    /** Publishes limit changes only after the caller has reconciled and activated the new index. */
    public void publishLimitChanges(
            @Nonnull com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex previous,
            @Nonnull com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex current,
            boolean recovered) {
        java.util.TreeSet<String> groupIds = new java.util.TreeSet<>();
        groupIds.addAll(previous.definitions().keySet());
        groupIds.addAll(current.definitions().keySet());
        long now = System.currentTimeMillis();
        for (String groupId : groupIds) {
            PopulationGroupDefinitionView oldDefinition = previous.getDefinition(groupId).orElse(null);
            PopulationGroupDefinitionView newDefinition = current.getDefinition(groupId).orElse(null);
            if (!limitChanged(oldDefinition, newDefinition)) continue;
            PopulationGroupScope scope = newDefinition != null
                    ? newDefinition.scope() : oldDefinition.scope();
            UUID operationId = UUID.nameUUIDFromBytes(
                    ("population-group-limit:" + groupId + ":" + current.revision())
                            .getBytes(StandardCharsets.UTF_8));
            emit(operationId, new PopulationGroupLimitChangedEvent(
                    operationId, groupId, previous.revision(), current.revision(),
                    oldDefinition == null ? 0L : oldDefinition.maxOwnedPerOwner(),
                    newDefinition == null ? 0L : newDefinition.maxOwnedPerOwner(),
                    oldDefinition == null ? 0L : oldDefinition.maxActivePerOwner(),
                    newDefinition == null ? 0L : newDefinition.maxActivePerOwner(),
                    scope, recovered, now, System.currentTimeMillis()));
        }
    }

    private static boolean limitChanged(
            @Nullable PopulationGroupDefinitionView previous,
            @Nullable PopulationGroupDefinitionView current) {
        if (previous == null || current == null) return previous != current;
        return previous.maxOwnedPerOwner() != current.maxOwnedPerOwner()
                || previous.maxActivePerOwner() != current.maxActivePerOwner()
                || previous.scope() != current.scope();
    }

    private CompletableFuture<Boolean> emitMembership(PreparedGroup group, boolean recovered) {
        return emitMembership(group.operation(), group.oldClassificationRevision(), recovered);
    }

    private CompletableFuture<Boolean> emitReconciledMembership(
            UUID ownerUuid,
            String profileId,
            String roleId,
            @Nullable PopulationGroupClassificationRecord existing,
            List<String> newGroups,
            long newRevision,
            boolean recovered) {
        UUID operationId = UUID.nameUUIDFromBytes(
                ("population-group-membership:" + profileId + ":" + newRevision)
                        .getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return emit(operationId, new PopulationGroupMembershipChangedEvent(
                operationId, profileId, ownerUuid, roleId,
                existing == null ? java.util.Set.of() : java.util.Set.copyOf(existing.groupIds()),
                java.util.Set.copyOf(newGroups),
                existing == null ? 0L
                        : Math.min(existing.classificationRevision(), newRevision),
                newRevision, recovered, now, System.currentTimeMillis()));
    }

    private CompletableFuture<Boolean> emitMembership(
            PopulationGroupOperationRecord operation, boolean recovered) {
        return emitMembership(operation, 0L, recovered);
    }

    private CompletableFuture<Boolean> emitMembership(
            PopulationGroupOperationRecord operation,
            long oldClassificationRevision,
            boolean recovered) {
        if (operation.oldGroupIds().equals(operation.newGroupIds())
                && Objects.equals(operation.oldRoleId(), operation.newRoleId())) {
            return CompletableFuture.completedFuture(false);
        }
        UUID owner = operation.newOwnerUuid() != null
                ? operation.newOwnerUuid() : operation.oldOwnerUuid();
        String role = first(operation.newRoleId(), operation.oldRoleId());
        if (owner == null || role == null) return CompletableFuture.completedFuture(false);
        UUID operationId;
        try {
            operationId = UUID.fromString(operation.operationId());
        } catch (IllegalArgumentException invalid) {
            operationId = UUID.nameUUIDFromBytes(operation.operationId().getBytes(StandardCharsets.UTF_8));
        }
        long now = System.currentTimeMillis();
        return emit(operationId, new PopulationGroupMembershipChangedEvent(
                operationId, operation.profileId(), owner, role,
                java.util.Set.copyOf(operation.oldGroupIds()),
                java.util.Set.copyOf(operation.newGroupIds()),
                Math.min(oldClassificationRevision, operation.classificationRevision()),
                operation.classificationRevision(), recovered, now, System.currentTimeMillis()));
    }

    private CompletableFuture<Boolean> emit(
            UUID operationId, com.alechilles.alecstamework.api.TameworkEvent event) {
        final com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue
                .WriteSubmission<Boolean> submission;
        try {
            submission = repository.claimEventEmissionAsync(
                    operationId, event.getClass().getSimpleName(), System.currentTimeMillis());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(false);
        }
        return submission.completion().thenApply(outcome -> {
            boolean claimed = outcome != null && outcome.isCommitted()
                    && Boolean.TRUE.equals(outcome.value());
            if (!claimed) return false;
            try {
                events.emit(event);
            } catch (RuntimeException | LinkageError ignored) {
                // Event delivery is informational and must never alter committed population state.
            }
            return true;
        });
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "population-group-admission-unavailable" : failure.getMessage();
    }

    private record PreparedGroup(
            PopulationGroupOperationRecord operation,
            List<PopulationGroupRepository.ReservationEvidence> evidence,
            PopulationGroupRepository.ClassificationMutation classification,
            long oldClassificationRevision,
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
