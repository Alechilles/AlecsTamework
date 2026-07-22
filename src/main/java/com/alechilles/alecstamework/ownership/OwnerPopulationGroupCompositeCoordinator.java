package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.UnifiedPopulationCompositeStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Coordinates process-local owner capabilities with the multi-store SQLite transaction boundary. */
public final class OwnerPopulationGroupCompositeCoordinator {
    private final OwnerPopulationAdmissionCoordinator ownerCoordinator;
    private final OwnerPopulationIndex index;
    private final PersistenceHealthService persistenceHealth;
    private final UnifiedPopulationCompositeStore compositeStore;
    private final OwnerPopulationJournalTerminality terminality;

    OwnerPopulationGroupCompositeCoordinator(
            OwnerPopulationAdmissionCoordinator ownerCoordinator,
            OwnerPopulationIndex index,
            CompanionPopulationRepository repository,
            PersistenceHealthService persistenceHealth,
            OwnerPopulationJournalTerminality terminality) {
        this.ownerCoordinator = Objects.requireNonNull(ownerCoordinator, "ownerCoordinator");
        this.index = Objects.requireNonNull(index, "index");
        this.compositeStore = Objects.requireNonNull(
                repository, "repository").unifiedPopulationCompositeStore();
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
        this.terminality = Objects.requireNonNull(terminality, "terminality");
    }

    /** Claims a group operation owned by this composite coordinator, not the installed extension. */
    public boolean claimForApply(@Nonnull PreparedOwnerPopulationAdmission prepared,
                                 long currentSettingsRevision,
                                 @Nonnull ClaimProviderGeneration currentProviderGeneration) {
        Objects.requireNonNull(prepared, "prepared");
        if (!persistenceHealth.isHealthy()) {
            ownerCoordinator.cancelOwnerOnlyAsync(
                    prepared, "owner-population-persistence-degraded-before-apply");
            return false;
        }
        ClaimProviderGeneration generation = currentProviderGeneration == null
                ? ClaimProviderGeneration.NONE : currentProviderGeneration;
        if (prepared.settingsRevision() != currentSettingsRevision
                || !prepared.plan().providerGeneration().equals(generation)) {
            ownerCoordinator.cancelOwnerOnlyAsync(prepared, "owner-population-context-changed");
            return false;
        }
        if (!prepared.transition(PreparedOwnerPopulationAdmission.State.PREPARED,
                PreparedOwnerPopulationAdmission.State.APPLYING)) return false;
        if (index.claimForApply(prepared.reservation())) return true;
        ownerCoordinator.cancelOwnerOnlyAsync(prepared, "owner-population-reservation-expired");
        return false;
    }

    @Nonnull
    public CompletableFuture<OwnerPopulationPreparationResult> prepareProvisionedDormantAsync(
            @Nonnull OwnerPopulationAdmissionPlan plan,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull List<PopulationGroupRepository.ReservationEvidence> groupEvidence) {
        return prepareReservedAsync(ownerCoordinator.reserveInMemory(plan), groupRepository,
                groupOperation, groupEvidence);
    }

    @Nonnull
    CompletableFuture<OwnerPopulationPreparationResult> prepareReservedAsync(
            @Nonnull OwnerPopulationReservationPreparation reserved,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull List<PopulationGroupRepository.ReservationEvidence> groupEvidence) {
        if (!reserved.allowed()) {
            return CompletableFuture.completedFuture(new OwnerPopulationPreparationResult(
                    false, reserved.reason(), reserved.decision(), null));
        }
        OwnerPopulationAdmissionPlan plan = reserved.plan();
        OwnerPopulationDecision decision = reserved.decision();
        UUID ownerOperationId = decision.reservation().tokenId();
        PopulationPersistenceTransition.Prepare ownerPrepare =
                new PopulationPersistenceTransition.Prepare(
                        OwnerPopulationPersistenceRecords.prepared(ownerOperationId, plan),
                        plan.baselineState());
        try {
            PersistenceWriteQueue.WriteSubmission<
                    UnifiedPopulationCompositeStore.ProvisionedDormantPreparationResult> submission =
                    compositeStore.prepareProvisionedDormantAsync(
                            ownerPrepare, groupRepository, groupOperation, groupEvidence);
            if (submission == null || submission.completion() == null) {
                index.cancel(decision.reservation());
                return CompletableFuture.completedFuture(terminality.deniedPreparation(
                        decision, "population-group-composite-prepare-stage-missing"));
            }
            return submission.completion().handle((outcome, failure) ->
                    finishPreparation(plan, decision, ownerOperationId, outcome, failure));
        } catch (RuntimeException | LinkageError failure) {
            index.cancel(decision.reservation());
            return CompletableFuture.completedFuture(terminality.deniedPreparation(
                    decision, "population-group-composite-prepare-start-failed"));
        }
    }

    private OwnerPopulationPreparationResult finishPreparation(
            OwnerPopulationAdmissionPlan plan,
            OwnerPopulationDecision decision,
            UUID ownerOperationId,
            PersistenceWriteQueue.WriteOutcome<
                    UnifiedPopulationCompositeStore.ProvisionedDormantPreparationResult> outcome,
            Throwable failure) {
        if (failure != null || outcome == null || !outcome.isCommitted()
                || outcome.value() == null || !outcome.value().prepared()) {
            index.cancel(decision.reservation());
            String reason = outcome != null && outcome.value() != null
                    && outcome.value().reason() != null
                    ? outcome.value().reason() : "population-group-composite-prepare-failed";
            return terminality.deniedPreparation(decision, reason);
        }
        PreparedOwnerPopulationAdmission prepared =
                new PreparedOwnerPopulationAdmission(ownerOperationId, plan, decision);
        return new OwnerPopulationPreparationResult(
                true, "population-group-composite-prepared", decision, prepared);
    }

    @Nonnull
    public CompletableFuture<OwnerPopulationCommitResult> commitProvisionedDormantAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull NpcProfileRepository profileRepository,
            @Nonnull NpcProfileRepository.DormantProfileMutation profileMutation,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) {
        OwnerPopulationCommitResult invalid = beginCommit(
                prepared, "provisioned_dormant_owner_index_commit_failed",
                "provisioned-dormant-owner-index-commit-failed");
        if (invalid != null) return CompletableFuture.completedFuture(invalid);
        PopulationPersistenceTransition.Commit ownerCommit =
                OwnerPopulationPersistenceRecords.commit(prepared.operationId(), prepared.plan());
        try {
            PersistenceWriteQueue.WriteSubmission<
                    UnifiedPopulationCompositeStore.ProvisionedDormantCommitResult> submission =
                    compositeStore.commitProvisionedDormantAsync(
                            ownerCommit, profileRepository, profileMutation, groupRepository,
                            groupOperationId, classification, nowMs);
            if (submission == null || submission.completion() == null) {
                return terminality.commitStartFailed(
                        prepared, "provisioned-dormant-commit-stage-missing");
            }
            return submission.completion().handle((outcome, failure) -> {
                if (failure == null && outcome != null && outcome.isCommitted()
                        && outcome.value() != null && outcome.value().committed()) {
                    prepared.setState(PreparedOwnerPopulationAdmission.State.COMMITTED);
                    return new OwnerPopulationCommitResult(
                            OwnerPopulationCommitResult.Status.COMMITTED,
                            "provisioned-dormant-committed", outcome.value().ownerResult());
                }
                return degraded(prepared, "provisioned_dormant_composite_commit_failed",
                        "provisioned-dormant-composite-commit-failed",
                        outcome == null || outcome.value() == null
                                ? null : outcome.value().ownerResult());
            });
        } catch (RuntimeException | LinkageError failure) {
            return terminality.commitStartFailed(
                    prepared, "provisioned-dormant-commit-start-failed");
        }
    }

    @Nonnull
    CompletableFuture<OwnerPopulationCommitResult> commitPopulationGroupsAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) {
        OwnerPopulationCommitResult invalid = beginCommit(
                prepared, "population_group_owner_index_commit_failed",
                "population-group-owner-index-commit-failed");
        if (invalid != null) return CompletableFuture.completedFuture(invalid);
        PopulationPersistenceTransition.Commit ownerCommit =
                OwnerPopulationPersistenceRecords.commit(prepared.operationId(), prepared.plan());
        try {
            PersistenceWriteQueue.WriteSubmission<
                    UnifiedPopulationCompositeStore.PopulationGroupCompositeCommitResult> submission =
                    compositeStore.commitPopulationGroupsAsync(
                            ownerCommit, groupRepository, groupOperationId, classification, nowMs);
            if (submission == null || submission.completion() == null) {
                return terminality.commitStartFailed(
                        prepared, "population-group-composite-commit-stage-missing");
            }
            return submission.completion().handle((outcome, failure) -> {
                if (failure == null && outcome != null && outcome.isCommitted()
                        && outcome.value() != null && outcome.value().committed()) {
                    boolean sourcePending = outcome.value().status()
                            == UnifiedPopulationCompositeStore.CompositeStatus.SOURCE_FINALIZATION_PENDING;
                    prepared.setState(sourcePending
                            ? PreparedOwnerPopulationAdmission.State.SOURCE_FINALIZATION_PENDING
                            : PreparedOwnerPopulationAdmission.State.COMMITTED);
                    return new OwnerPopulationCommitResult(
                            sourcePending
                                    ? OwnerPopulationCommitResult.Status.SOURCE_FINALIZATION_PENDING
                                    : OwnerPopulationCommitResult.Status.COMMITTED,
                            sourcePending ? "population-group-source-finalization-pending"
                                    : "population-group-composite-committed",
                            outcome.value().ownerResult());
                }
                return degraded(prepared, "population_group_composite_commit_failed",
                        "population-group-composite-commit-failed",
                        outcome == null || outcome.value() == null
                                ? null : outcome.value().ownerResult());
            });
        } catch (RuntimeException | LinkageError failure) {
            return terminality.commitStartFailed(
                    prepared, "population-group-composite-commit-start-failed");
        }
    }

    private OwnerPopulationCommitResult beginCommit(
            PreparedOwnerPopulationAdmission prepared,
            String degradationReason,
            String failureReason) {
        Objects.requireNonNull(prepared, "prepared");
        if (!prepared.transition(PreparedOwnerPopulationAdmission.State.APPLYING,
                PreparedOwnerPopulationAdmission.State.COMMITTING)) {
            return new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.INVALID_CAPABILITY,
                    "owner-population-capability-not-applying", null);
        }
        if (index.commit(prepared.reservation())) return null;
        prepared.setState(PreparedOwnerPopulationAdmission.State.CANCELED);
        terminality.degrade(degradationReason);
        return new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.INDEX_COMMIT_FAILED, failureReason, null);
    }

    private OwnerPopulationCommitResult degraded(
            PreparedOwnerPopulationAdmission prepared,
            String degradationReason,
            String failureReason,
            PopulationPersistenceTransition.Result result) {
        terminality.degrade(degradationReason);
        prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
        return new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED, failureReason, result);
    }
}
