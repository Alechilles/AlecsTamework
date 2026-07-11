package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.ProfileOwnerMutation;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates atomic in-memory owner reservations with the crash-recoverable SQLite journal.
 *
 * <p>Preparation and final durability complete asynchronously. The synchronous apply claim is
 * deliberately in-memory only so a world thread never waits on SQLite.
 */
public final class OwnerPopulationAdmissionCoordinator {
    private final OwnerPopulationIndex index;
    private final CompanionPopulationRepository repository;
    private final PersistenceHealthService persistenceHealth;

    public OwnerPopulationAdmissionCoordinator(@Nonnull OwnerPopulationIndex index,
                                               @Nonnull CompanionPopulationRepository repository,
                                               @Nonnull PersistenceHealthService persistenceHealth) {
        this.index = Objects.requireNonNull(index, "index");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
    }

    @Nonnull
    public CompletableFuture<OwnerPopulationPreparationResult> prepareAsync(
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        if (!persistenceHealth.isHealthy()) {
            OwnerPopulationDecision original = index.reserve(plan.transition());
            if (original.reservation() != null) {
                index.cancel(original.reservation());
            }
            OwnerPopulationDecision denied = deniedWithoutReservation(
                    original,
                    "owner-population-persistence-degraded"
            );
            return CompletableFuture.completedFuture(
                    new OwnerPopulationPreparationResult(false, denied.reason(), denied, null)
            );
        }

        OwnerPopulationDecision decision = index.reserve(plan.transition());
        if (!decision.allowed()) {
            return CompletableFuture.completedFuture(
                    new OwnerPopulationPreparationResult(false, decision.reason(), decision, null)
            );
        }
        UUID operationId = decision.reservation().tokenId();
        PopulationPersistenceTransition.Prepare persistencePrepare =
                new PopulationPersistenceTransition.Prepare(
                        operationRecord(operationId, plan),
                        plan.baselineState()
                );
        PersistenceWriteQueue.WriteSubmission<PopulationPersistenceTransition.Result> submission =
                repository.prepareAsync(persistencePrepare);
        return submission.completion().thenCompose(outcome ->
                finishPreparation(plan, decision, operationId, outcome)
        );
    }

    /**
     * Revalidates immutable operation context and claims the reservation for one live mutation.
     */
    public boolean claimForApply(@Nonnull PreparedOwnerPopulationAdmission prepared,
                                 long currentSettingsRevision,
                                 @Nonnull ClaimProviderGeneration currentProviderGeneration) {
        Objects.requireNonNull(prepared, "prepared");
        ClaimProviderGeneration generation = currentProviderGeneration == null
                ? ClaimProviderGeneration.NONE
                : currentProviderGeneration;
        if (prepared.settingsRevision() != currentSettingsRevision
                || !prepared.plan().providerGeneration().equals(generation)) {
            cancelAsync(prepared, "owner-population-context-changed");
            return false;
        }
        if (!prepared.transition(
                PreparedOwnerPopulationAdmission.State.PREPARED,
                PreparedOwnerPopulationAdmission.State.APPLYING
        )) {
            return false;
        }
        if (index.claimForApply(prepared.reservation())) {
            return true;
        }
        prepared.setState(PreparedOwnerPopulationAdmission.State.CANCELED);
        closeApplyingJournal(prepared.operationId(), "owner-population-reservation-expired");
        return false;
    }

    /**
     * Commits conservative in-memory counts first, then finalizes the durable transaction.
     */
    @Nonnull
    public CompletableFuture<OwnerPopulationCommitResult> commitAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared
    ) {
        Objects.requireNonNull(prepared, "prepared");
        if (!prepared.transition(
                PreparedOwnerPopulationAdmission.State.APPLYING,
                PreparedOwnerPopulationAdmission.State.COMMITTING
        )) {
            return CompletableFuture.completedFuture(new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.INVALID_CAPABILITY,
                    "owner-population-capability-not-applying",
                    null
            ));
        }
        if (!index.commit(prepared.reservation())) {
            prepared.setState(PreparedOwnerPopulationAdmission.State.CANCELED);
            closeApplyingJournal(prepared.operationId(), "owner-population-index-commit-failed");
            return CompletableFuture.completedFuture(new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.INDEX_COMMIT_FAILED,
                    "owner-population-index-commit-failed",
                    null
            ));
        }

        PopulationPersistenceTransition.Commit persistenceCommit =
                persistenceCommit(prepared.operationId(), prepared.plan());
        return repository.commitAsync(persistenceCommit).completion().thenApply(outcome ->
                finishCommit(prepared, outcome)
        );
    }

    /**
     * Cancels an uncommitted mutation and closes its journal row without waiting on the caller.
     */
    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedOwnerPopulationAdmission prepared,
                                                   @Nonnull String reason) {
        Objects.requireNonNull(prepared, "prepared");
        String normalizedReason = reason == null || reason.isBlank()
                ? "owner-population-canceled"
                : reason.trim();
        PreparedOwnerPopulationAdmission.State current = prepared.state();
        if ((current != PreparedOwnerPopulationAdmission.State.PREPARED
                && current != PreparedOwnerPopulationAdmission.State.APPLYING)
                || !prepared.transition(current, PreparedOwnerPopulationAdmission.State.CANCELED)) {
            return CompletableFuture.completedFuture(false);
        }
        index.cancel(prepared.reservation());
        return closeApplyingJournal(prepared.operationId(), normalizedReason);
    }

    @Nonnull
    private CompletableFuture<OwnerPopulationPreparationResult> finishPreparation(
            @Nonnull OwnerPopulationAdmissionPlan plan,
            @Nonnull OwnerPopulationDecision decision,
            @Nonnull UUID operationId,
            @Nonnull PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome
    ) {
        if (!outcome.isCommitted()
                || outcome.value() == null
                || outcome.value().status() != PopulationPersistenceTransition.ResultStatus.PREPARED) {
            index.cancel(decision.reservation());
            OwnerPopulationDecision denied = deniedWithoutReservation(
                    decision,
                    "owner-population-prepare-failed"
            );
            return CompletableFuture.completedFuture(
                    new OwnerPopulationPreparationResult(false, denied.reason(), denied, null)
            );
        }
        PersistenceWriteQueue.WriteSubmission<Boolean> applying = repository.advanceOperationAsync(
                operationId.toString(),
                CompanionPopulationOperationRecord.State.PREPARED,
                CompanionPopulationOperationRecord.State.APPLYING,
                null
        );
        return applying.completion().thenApply(advanceOutcome -> {
            if (!advanceOutcome.isCommitted() || !Boolean.TRUE.equals(advanceOutcome.value())) {
                index.cancel(decision.reservation());
                persistenceHealth.markDegraded("owner_population_journal_apply_failed");
                OwnerPopulationDecision denied = deniedWithoutReservation(
                        decision,
                        "owner-population-prepare-finalize-failed"
                );
                return new OwnerPopulationPreparationResult(false, denied.reason(), denied, null);
            }
            PreparedOwnerPopulationAdmission prepared =
                    new PreparedOwnerPopulationAdmission(operationId, plan, decision);
            return new OwnerPopulationPreparationResult(
                    true,
                    "owner-population-prepared",
                    decision,
                    prepared
            );
        });
    }

    @Nonnull
    private OwnerPopulationCommitResult finishCommit(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome
    ) {
        PopulationPersistenceTransition.Result result = outcome.value();
        if (outcome.isCommitted()
                && result != null
                && (result.status() == PopulationPersistenceTransition.ResultStatus.COMMITTED
                || result.status() == PopulationPersistenceTransition.ResultStatus.IDEMPOTENT)) {
            prepared.setState(PreparedOwnerPopulationAdmission.State.COMMITTED);
            return new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.COMMITTED,
                    "owner-population-committed",
                    result
            );
        }
        persistenceHealth.markDegraded("owner_population_final_durability_failed");
        prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
        return new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                "owner-population-final-durability-failed",
                result
        );
    }

    @Nonnull
    private CompletableFuture<Boolean> closeApplyingJournal(@Nonnull UUID operationId,
                                                            @Nonnull String reason) {
        PersistenceWriteQueue.WriteSubmission<Boolean> close = repository.advanceOperationAsync(
                operationId.toString(),
                CompanionPopulationOperationRecord.State.APPLYING,
                CompanionPopulationOperationRecord.State.FAILED,
                reason
        );
        return close.completion().thenApply(outcome ->
                outcome.isCommitted() && Boolean.TRUE.equals(outcome.value())
        );
    }

    @Nonnull
    private static CompanionPopulationOperationRecord operationRecord(
            @Nonnull UUID operationId,
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        long now = System.currentTimeMillis();
        return new CompanionPopulationOperationRecord(
                operationId.toString(),
                plan.transition().profileId(),
                plan.transition().operation().name(),
                CompanionPopulationOperationRecord.State.PREPARED,
                plan.baselineState().revision(),
                plan.oldStateJson(),
                plan.newStateJson(),
                plan.targetContextJson(),
                now,
                now,
                0L,
                null
        );
    }

    @Nonnull
    private static PopulationPersistenceTransition.Commit persistenceCommit(
            @Nonnull UUID operationId,
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        OwnerPopulationTransitionRequest transition = plan.transition();
        return new PopulationPersistenceTransition.Commit(
                operationId.toString(),
                transition.profileId(),
                plan.baselineState().revision(),
                ownerMutation(transition.expectedOwnerId(), transition.newOwnerId()),
                plan.finalNpcUuid(),
                transition.destinationWorldName(),
                transition.lifecycleState().name(),
                plan.finalPhysicalWorldName(),
                plan.finalPhysicalChunkX(),
                plan.finalPhysicalChunkZ(),
                plan.source()
        );
    }

    @Nonnull
    private static ProfileOwnerMutation ownerMutation(@Nullable UUID oldOwner, @Nullable UUID newOwner) {
        if (Objects.equals(oldOwner, newOwner)) {
            return ProfileOwnerMutation.unchanged();
        }
        return newOwner == null ? ProfileOwnerMutation.clear() : ProfileOwnerMutation.set(newOwner);
    }

    @Nonnull
    private static OwnerPopulationDecision deniedWithoutReservation(
            @Nonnull OwnerPopulationDecision original,
            @Nonnull String reason
    ) {
        return new OwnerPopulationDecision(
                false,
                reason,
                null,
                original.readiness(),
                original.limit(),
                original.committedCount(),
                original.pendingCount(),
                original.currentRevision(),
                original.positiveDelta(),
                original.forced()
        );
    }
}
