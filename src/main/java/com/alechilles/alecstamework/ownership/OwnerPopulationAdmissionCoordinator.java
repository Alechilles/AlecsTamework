package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.ProfileOwnerMutation;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private final OwnerPopulationJournalTerminality terminality;
    private final OwnerPopulationJournalCloseCoordinator journalCloseCoordinator;
    private final OwnerPopulationCompensationCoordinator compensationCoordinator;

    public OwnerPopulationAdmissionCoordinator(@Nonnull OwnerPopulationIndex index,
                                               @Nonnull CompanionPopulationRepository repository,
                                               @Nonnull PersistenceHealthService persistenceHealth) {
        this.index = Objects.requireNonNull(index, "index");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
        this.terminality = new OwnerPopulationJournalTerminality(index, persistenceHealth);
        this.journalCloseCoordinator = new OwnerPopulationJournalCloseCoordinator(
                index, repository, terminality
        );
        this.compensationCoordinator = new OwnerPopulationCompensationCoordinator(
                index, repository, terminality
        );
    }

    @Nonnull
    public CompletableFuture<OwnerPopulationPreparationResult> prepareAsync(
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        OwnerPopulationReservationPreparation reservation = reserveInMemory(plan);
        return prepareReservedAsync(reservation);
    }

    /** Performs only the short in-memory compare/headroom/reservation phase. */
    @Nonnull
    OwnerPopulationReservationPreparation reserveInMemory(
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
            return new OwnerPopulationReservationPreparation(
                    false,
                    denied.reason(),
                    plan,
                    denied
            );
        }

        OwnerPopulationDecision decision = index.reserve(plan.transition());
        if (!decision.allowed()) {
            return new OwnerPopulationReservationPreparation(
                    false,
                    decision.reason(),
                    plan,
                    decision
            );
        }
        return new OwnerPopulationReservationPreparation(true, decision.reason(), plan, decision);
    }

    /** Submits durability only after any outer combined reservation mutex has been released. */
    @Nonnull
    CompletableFuture<OwnerPopulationPreparationResult> prepareReservedAsync(
            @Nonnull OwnerPopulationReservationPreparation reserved
    ) {
        Objects.requireNonNull(reserved, "reserved");
        if (!reserved.allowed()) {
            return CompletableFuture.completedFuture(new OwnerPopulationPreparationResult(
                    false,
                    reserved.reason(),
                    reserved.decision(),
                    null
            ));
        }
        OwnerPopulationAdmissionPlan plan = reserved.plan();
        OwnerPopulationDecision decision = reserved.decision();
        UUID operationId = decision.reservation().tokenId();
        PopulationPersistenceTransition.Prepare persistencePrepare =
                new PopulationPersistenceTransition.Prepare(
                        operationRecord(operationId, plan),
                        plan.baselineState()
                );
        try {
            PersistenceWriteQueue.WriteSubmission<PopulationPersistenceTransition.Result> submission =
                    repository.prepareAsync(persistencePrepare);
            if (submission == null || submission.completion() == null) {
                return terminality.preparationStartFailed(
                        decision, "owner-population-prepare-stage-missing"
                );
            }
            return submission.completion().handle((outcome, failure) -> {
                if (failure != null || outcome == null) {
                    return terminality.preparationStartFailed(
                            decision, "owner-population-prepare-completion-failed"
                    );
                }
                return finishPreparation(plan, decision, operationId, outcome);
            }).thenCompose(result -> result);
        } catch (RuntimeException | LinkageError failure) {
            return terminality.preparationStartFailed(
                    decision, "owner-population-prepare-start-failed"
            );
        }
    }

    /**
     * Revalidates immutable operation context and claims the reservation for one live mutation.
     */
    public boolean claimForApply(@Nonnull PreparedOwnerPopulationAdmission prepared,
                                 long currentSettingsRevision,
                                 @Nonnull ClaimProviderGeneration currentProviderGeneration) {
        Objects.requireNonNull(prepared, "prepared");
        if (!persistenceHealth.isHealthy()) {
            cancelAsync(prepared, "owner-population-persistence-degraded-before-apply");
            return false;
        }
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
        cancelAsync(prepared, "owner-population-reservation-expired");
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
            terminality.degrade("owner_population_index_commit_failed");
            terminality.observeJournalClose(journalCloseCoordinator.closeApplyingJournal(
                    prepared.operationId(), "owner-population-index-commit-failed"
            ), "owner_population_index_failure_journal_close_failed");
            return CompletableFuture.completedFuture(new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.INDEX_COMMIT_FAILED,
                    "owner-population-index-commit-failed",
                    null
            ));
        }

        PopulationPersistenceTransition.Commit persistenceCommit =
                persistenceCommit(prepared.operationId(), prepared.plan());
        try {
            PersistenceWriteQueue.WriteSubmission<PopulationPersistenceTransition.Result> submission =
                    repository.commitAsync(persistenceCommit);
            if (submission == null || submission.completion() == null) {
                return terminality.commitStartFailed(
                        prepared, "owner-population-commit-stage-missing"
                );
            }
            return submission.completion().handle((outcome, failure) ->
                    failure == null && outcome != null
                            ? finishCommit(prepared, outcome)
                            : terminality.commitStartFailedResult(
                                    prepared, "owner-population-commit-completion-failed"
                            )
            );
        } catch (RuntimeException | LinkageError failure) {
            return terminality.commitStartFailed(
                    prepared, "owner-population-commit-start-failed"
            );
        }
    }

    /** Marks a source-bearing population transition terminal after its exact source CAS succeeds. */
    @Nonnull
    public CompletableFuture<Boolean> completeSourceFinalizationAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared
    ) {
        return journalCloseCoordinator.completeSourceFinalizationAsync(prepared);
    }

    /**
     * Cancels an uncommitted mutation and closes its journal row without waiting on the caller.
     */
    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedOwnerPopulationAdmission prepared,
                                                   @Nonnull String reason) {
        return journalCloseCoordinator.cancelAsync(prepared, reason);
    }

    /** Durably records compensation intent before any live rollback is allowed to run. */
    @Nonnull
    public CompletableFuture<Boolean> beginCompensationAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        return compensationCoordinator.beginAsync(prepared, reason);
    }

    /** Closes a successfully restored mutation before releasing its conservative reservation. */
    @Nonnull
    public CompletableFuture<Boolean> completeCompensationAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        return compensationCoordinator.completeAsync(prepared, reason);
    }

    /** Fails positive owner admissions closed after a post-apply accounting failure. */
    void markReadinessDegraded(@Nonnull String reason) {
        terminality.degrade(reason);
    }

    /** Quarantines new admissions without poisoning persistence needed to finish in-flight work. */
    void markAdmissionReadinessDegraded() {
        index.setReadiness(OwnerPopulationReadiness.DEGRADED);
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
                    preparationFailureReason(outcome)
            );
            return CompletableFuture.completedFuture(
                    new OwnerPopulationPreparationResult(false, denied.reason(), denied, null)
            );
        }
        final PersistenceWriteQueue.WriteSubmission<Boolean> applying;
        try {
            applying = repository.advanceOperationAsync(
                    operationId.toString(),
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            );
        } catch (RuntimeException | LinkageError failure) {
            index.cancel(decision.reservation());
            terminality.degrade("owner_population_journal_apply_start_failed");
            return CompletableFuture.completedFuture(terminality.deniedPreparation(
                    decision, "owner-population-prepare-finalize-failed"
            ));
        }
        if (applying == null || applying.completion() == null) {
            index.cancel(decision.reservation());
            terminality.degrade("owner_population_journal_apply_stage_missing");
            return CompletableFuture.completedFuture(terminality.deniedPreparation(
                    decision, "owner-population-prepare-finalize-failed"
            ));
        }
        return applying.completion().handle((advanceOutcome, failure) -> {
            if (failure != null || advanceOutcome == null) {
                index.cancel(decision.reservation());
                terminality.degrade("owner_population_journal_apply_failed");
                return terminality.deniedPreparation(
                        decision, "owner-population-prepare-finalize-failed"
                );
            }
            if (!advanceOutcome.isCommitted() || !Boolean.TRUE.equals(advanceOutcome.value())) {
                index.cancel(decision.reservation());
                terminality.degrade("owner_population_journal_apply_failed");
                return terminality.deniedPreparation(
                        decision, "owner-population-prepare-finalize-failed"
                );
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
        if (outcome.isCommitted() && result != null
                && result.status() == PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING) {
            prepared.setState(PreparedOwnerPopulationAdmission.State.SOURCE_FINALIZATION_PENDING);
            return new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.SOURCE_FINALIZATION_PENDING,
                    "owner-population-source-finalization-pending",
                    result
            );
        }
        if (outcome.isCommitted() && result != null
                && (result.status() == PopulationPersistenceTransition.ResultStatus.COMMITTED
                || result.status() == PopulationPersistenceTransition.ResultStatus.IDEMPOTENT)) {
            prepared.setState(PreparedOwnerPopulationAdmission.State.COMMITTED);
            return new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.COMMITTED,
                    "owner-population-committed",
                    result
            );
        }
        if (outcome.isCommitted() && result != null
                && result.status()
                == PopulationPersistenceTransition.ResultStatus.MANAGED_COOP_CONFLICT) {
            terminality.degradeAdmissionOnly();
            prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
            return new OwnerPopulationCommitResult(
                    OwnerPopulationCommitResult.Status.DURABLE_CONFLICT,
                    result.reason() == null || result.reason().isBlank()
                            ? "owner-population-managed-coop-conflict"
                            : result.reason(),
                    result
            );
        }
        terminality.degrade("owner_population_final_durability_failed");
        prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
        return new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                "owner-population-final-durability-failed",
                result
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
                recoveryStateJson(
                        plan.oldStateJson(),
                        plan.baselineState().ownerUuid(),
                        plan.baselineState().lifecycleState(),
                        plan.baselineState().ownershipWorldName()
                ),
                recoveryStateJson(
                        plan.newStateJson(),
                        plan.transition().newOwnerId(),
                        plan.transition().lifecycleState().name(),
                        plan.transition().destinationWorldName()
                ),
                plan.targetContextJson(),
                now,
                now,
                0L,
                null
        );
    }

    @Nonnull
    private static String recoveryStateJson(@Nonnull String original,
                                            @Nullable UUID ownerUuid,
                                            @Nonnull String lifecycleState,
                                            @Nullable String ownershipWorldName) {
        JsonObject json = JsonParser.parseString(original).getAsJsonObject();
        if (ownerUuid == null) {
            json.add("ownerUuid", JsonNull.INSTANCE);
        } else {
            json.addProperty("ownerUuid", ownerUuid.toString());
        }
        json.addProperty("lifecycleState", lifecycleState);
        if (ownershipWorldName == null || ownershipWorldName.isBlank()) {
            json.add("ownershipWorldName", JsonNull.INSTANCE);
        } else {
            json.addProperty("ownershipWorldName", ownershipWorldName.trim());
        }
        return json.toString();
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

    @Nonnull
    private static String preparationFailureReason(
            @Nonnull PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome
    ) {
        PopulationPersistenceTransition.Result result = outcome.value();
        return result != null && result.reason() != null && !result.reason().isBlank()
                ? result.reason()
                : "owner-population-prepare-failed";
    }
}
