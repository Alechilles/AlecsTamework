package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionEvaluation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancySnapshot;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Coordinates owner and physical-claim reservations as one mutation capability.
 *
 * <p>The two in-memory authorities are reserved serially without holding either lock across the
 * other. Partial reservation is immediately canceled, so no live side effect can observe only
 * one half of the admission.</p>
 */
public final class CompanionPopulationAdmissionCoordinator {
    private static final int EVALUATION_RETRY_LIMIT = 3;
    private final OwnerPopulationAdmissionCoordinator ownerCoordinator;
    private final ClaimAdmissionService claimAdmissionService;
    private final ReentrantLock reservationBoundary = new ReentrantLock();

    public CompanionPopulationAdmissionCoordinator(
            @Nonnull OwnerPopulationAdmissionCoordinator ownerCoordinator,
            @Nonnull ClaimAdmissionService claimAdmissionService
    ) {
        this.ownerCoordinator = Objects.requireNonNull(ownerCoordinator, "ownerCoordinator");
        this.claimAdmissionService = Objects.requireNonNull(claimAdmissionService, "claimAdmissionService");
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationPreparationResult> prepareAsync(
            @Nonnull OwnerPopulationAdmissionPlan ownerPlan,
            @Nonnull ClaimAdmissionRequest claimRequest,
            @Nonnull ClaimLookupSession lookupSession
    ) {
        Objects.requireNonNull(ownerPlan, "ownerPlan");
        Objects.requireNonNull(claimRequest, "claimRequest");
        Objects.requireNonNull(lookupSession, "lookupSession");
        for (int attempt = 0; attempt < EVALUATION_RETRY_LIMIT; attempt++) {
            ClaimAdmissionEvaluation evaluation = claimAdmissionService.evaluate(
                    claimRequest,
                    lookupSession
            );
            CompanionPopulationReservationPreparation reservation =
                    withinReservationBoundary(() -> reserveEvaluatedWithinReservationBoundary(
                            ownerPlan,
                            evaluation
                    ));
            CompletableFuture<CompanionPopulationPreparationResult> preparation =
                    prepareReservedAsync(reservation);
            CompanionPopulationPreparationResult immediate = preparation.getNow(null);
            if (immediate == null
                    || !"claim-occupancy-changed-during-admission".equals(immediate.reason())) {
                return preparation;
            }
        }
        return CompletableFuture.completedFuture(new CompanionPopulationPreparationResult(
                false,
                "claim-occupancy-changed-during-admission",
                null,
                null,
                null
        ));
    }

    /**
     * Group-aware preparation seam installed by the unified transition coordinator. Owner, claim,
     * and every group reservation are acquired all-or-none before a capability is returned.
     */
    @Nonnull
    public CompletableFuture<CompanionPopulationPreparationResult> prepareWithGroupsAsync(
            @Nonnull OwnerPopulationAdmissionPlan ownerPlan,
            @Nonnull ClaimAdmissionRequest claimRequest,
            @Nonnull ClaimLookupSession lookupSession,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull java.util.List<PopulationGroupRepository.ReservationEvidence> groupEvidence) {
        Objects.requireNonNull(ownerPlan, "ownerPlan");
        Objects.requireNonNull(claimRequest, "claimRequest");
        Objects.requireNonNull(lookupSession, "lookupSession");
        for (int attempt = 0; attempt < EVALUATION_RETRY_LIMIT; attempt++) {
            ClaimAdmissionEvaluation evaluation = claimAdmissionService.evaluate(
                    claimRequest, lookupSession);
            CompanionPopulationReservationPreparation reservation =
                    withinReservationBoundary(() -> reserveEvaluatedWithinReservationBoundary(
                            ownerPlan, evaluation));
            CompletableFuture<CompanionPopulationPreparationResult> preparation =
                    prepareGroupReservedAsync(
                            reservation, groupRepository, groupOperation, groupEvidence);
            CompanionPopulationPreparationResult immediate = preparation.getNow(null);
            if (immediate == null
                    || !"claim-occupancy-changed-during-admission".equals(immediate.reason())) {
                return preparation;
            }
        }
        return CompletableFuture.completedFuture(new CompanionPopulationPreparationResult(
                false, "claim-occupancy-changed-during-admission", null, null, null));
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationPreparationResult> prepareGroupReservedAsync(
            @Nonnull CompanionPopulationReservationPreparation reservation,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull java.util.List<PopulationGroupRepository.ReservationEvidence> groupEvidence) {
        if (!reservation.allowed() || reservation.ownerReservation() == null) {
            return CompletableFuture.completedFuture(new CompanionPopulationPreparationResult(
                    false, reservation.reason(),
                    reservation.ownerReservation() == null
                            ? null : reservation.ownerReservation().decision(),
                    reservation.claimDecision(), null));
        }
        return ownerCoordinator.groupCompositeCoordinator().prepareReservedAsync(
                        reservation.ownerReservation(), groupRepository, groupOperation, groupEvidence)
                .thenApply(ownerResult -> finishPreparation(ownerResult, reservation.claimDecision()));
    }

    /**
     * Runs one short in-memory reservation phase while a caller already owns the shared boundary.
     * The returned future may wait for SQLite, but this method never waits while the boundary is
     * held. Batch callers use this seam to prevent single admissions from interleaving between
     * units of one exact reservation attempt.
     */
    @Nonnull
    CompanionPopulationReservationPreparation reserveEvaluatedWithinReservationBoundary(
            @Nonnull OwnerPopulationAdmissionPlan ownerPlan,
            @Nonnull ClaimAdmissionEvaluation claimEvaluation
    ) {
        if (!reservationBoundary.isHeldByCurrentThread()) {
            throw new IllegalStateException("The companion reservation boundary is not held.");
        }
        ClaimAdmissionDecision claimDecision = claimAdmissionService.reserveEvaluated(
                Objects.requireNonNull(claimEvaluation, "claimEvaluation")
        );
        if (!claimDecision.allowed() || claimDecision.reservation() == null) {
            return new CompanionPopulationReservationPreparation(
                    false,
                    claimDecision.reason(),
                    claimDecision,
                    null
            );
        }
        OwnerPopulationReservationPreparation ownerReservation = ownerCoordinator.reserveInMemory(
                Objects.requireNonNull(ownerPlan, "ownerPlan")
        );
        if (!ownerReservation.allowed()) {
            claimAdmissionService.cancel(claimDecision.reservation());
            return new CompanionPopulationReservationPreparation(
                    false,
                    ownerReservation.reason(),
                    claimDecision,
                    ownerReservation
            );
        }
        return new CompanionPopulationReservationPreparation(
                true,
                "companion-population-reserved",
                claimDecision,
                ownerReservation
        );
    }

    /** Starts SQLite preparation after the shared in-memory reservation boundary is released. */
    @Nonnull
    CompletableFuture<CompanionPopulationPreparationResult> prepareReservedAsync(
            @Nonnull CompanionPopulationReservationPreparation reservation
    ) {
        Objects.requireNonNull(reservation, "reservation");
        if (!reservation.allowed() || reservation.ownerReservation() == null) {
            return CompletableFuture.completedFuture(new CompanionPopulationPreparationResult(
                    false,
                    reservation.reason(),
                    reservation.ownerReservation() == null
                            ? null
                            : reservation.ownerReservation().decision(),
                    reservation.claimDecision(),
                    null
            ));
        }
        return ownerCoordinator.prepareReservedAsync(reservation.ownerReservation())
                .thenApply(ownerResult -> finishPreparation(ownerResult, reservation.claimDecision()));
    }

    /** Executes only a synchronous reservation phase under the shared short-lived mutex. */
    @Nonnull
    <T> T withinReservationBoundary(@Nonnull Supplier<T> reservationPhase) {
        Objects.requireNonNull(reservationPhase, "reservationPhase");
        reservationBoundary.lock();
        try {
            return reservationPhase.get();
        } finally {
            reservationBoundary.unlock();
        }
    }

    /** Performs provider/topology/snapshot work without holding the combined reservation mutex. */
    @Nonnull
    ClaimAdmissionEvaluation evaluateClaim(@Nonnull ClaimAdmissionRequest request,
                                            @Nonnull ClaimLookupSession lookupSession) {
        return claimAdmissionService.evaluate(request, lookupSession);
    }

    /** Sweep-scoped evaluation overload reusing one immutable committed occupancy snapshot. */
    @Nonnull
    ClaimAdmissionEvaluation evaluateClaim(@Nonnull ClaimAdmissionRequest request,
                                            @Nonnull ClaimLookupSession lookupSession,
                                            @Nonnull ClaimOccupancySnapshot sharedSnapshot) {
        return claimAdmissionService.evaluate(request, lookupSession, sharedSnapshot);
    }

    public boolean claimForApply(@Nonnull PreparedCompanionPopulationAdmission prepared,
                                 long currentSettingsRevision,
                                 @Nonnull ClaimLookupSession refreshedSession) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(refreshedSession, "refreshedSession");
        try {
            if (!claimAdmissionService.claimForApply(
                    prepared.claimReservation(), refreshedSession
            )) {
                cancelBothAfterFailedClaim(prepared, "companion-claim-reservation-invalid");
                return false;
            }
            if (ownerCoordinator.claimForApply(
                    prepared.ownerAdmission(),
                    currentSettingsRevision,
                    refreshedSession.context().providerGeneration()
            )) {
                return true;
            }
            cancelBothAfterFailedClaim(prepared, "companion-owner-reservation-invalid");
            return false;
        } catch (RuntimeException | LinkageError failure) {
            cancelBothAfterFailedClaim(prepared, "companion-claim-for-apply-exception");
            markReadinessDegradedSafely("companion_population_claim_for_apply_failed");
            return false;
        }
    }

    private void cancelBothAfterFailedClaim(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        try {
            if (!claimAdmissionService.cancel(prepared.claimReservation())) {
                markReadinessDegradedSafely("companion_claim_cancel_after_recheck_failed");
            }
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_claim_cancel_after_recheck_failed");
        }
        try {
            CompletableFuture<Boolean> cancellation = ownerCoordinator.cancelAsync(
                    prepared.ownerAdmission(),
                    reason
            );
            if (cancellation == null) {
                markReadinessDegradedSafely("companion_owner_cancel_after_claim_rejection_failed");
                return;
            }
            cancellation.whenComplete((canceled, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(canceled)) {
                    markReadinessDegradedSafely(
                            "companion_owner_cancel_after_claim_rejection_failed"
                    );
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_owner_cancel_after_claim_rejection_failed");
        }
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitAsync(
            @Nonnull PreparedCompanionPopulationAdmission prepared
    ) {
        Objects.requireNonNull(prepared, "prepared");
        boolean claimCommitSucceeded;
        try {
            claimCommitSucceeded = claimAdmissionService.commit(prepared.claimReservation());
        } catch (RuntimeException | LinkageError failure) {
            claimCommitSucceeded = false;
            markReadinessDegradedSafely("companion_claim_index_commit_exception");
        }
        final boolean claimCommitted = claimCommitSucceeded;
        // The live component mutation has already happened. Always commit the owner side even if
        // the claim index unexpectedly rejects its half; canceling here would undercount the live
        // owner. Any asymmetric outcome degrades both authorities until reconciliation repairs it.
        final CompletableFuture<OwnerPopulationCommitResult> ownerCompletion;
        try {
            ownerCompletion = ownerCoordinator.commitAsync(prepared.ownerAdmission());
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_owner_commit_exception");
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    false,
                    "companion-owner-finalize-exception",
                    claimCommitted,
                    null
            ));
        }
        if (ownerCompletion == null) {
            markReadinessDegradedSafely("companion_owner_commit_stage_missing");
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    false,
                    "companion-owner-finalize-exception",
                    claimCommitted,
                    null
            ));
        }
        return ownerCompletion.handle((ownerCommit, failure) -> {
            boolean ownerCommitted = failure == null
                    && ownerCommit != null
                    && ownerCommit.committed();
            if (!claimCommitted || !ownerCommitted) {
                if (failure == null && ownerCommit != null
                        && ownerCommit.status()
                        == OwnerPopulationCommitResult.Status.DURABLE_CONFLICT) {
                    markAdmissionReadinessDegradedSafely();
                } else {
                    markReadinessDegradedSafely(!claimCommitted
                            ? "companion_claim_index_commit_failed"
                            : "companion_owner_commit_failed");
                }
            }
            String reason;
            if (!claimCommitted) {
                reason = "companion-claim-index-commit-failed";
            } else if (failure != null || ownerCommit == null) {
                reason = "companion-owner-finalize-exception";
            } else if (!ownerCommitted) {
                reason = ownerCommit.reason();
            } else {
                reason = "companion-population-committed";
            }
            return new CompanionPopulationCommitResult(
                    claimCommitted && ownerCommitted,
                    reason,
                    claimCommitted,
                    ownerCommit
            );
        });
    }

    /** Commits claim occupancy plus owner/group durability for one live mutation. */
    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitWithGroupsAsync(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) {
        Objects.requireNonNull(prepared, "prepared");
        boolean claimCommitted;
        try {
            claimCommitted = claimAdmissionService.commit(prepared.claimReservation());
        } catch (RuntimeException | LinkageError failure) {
            claimCommitted = false;
        }
        final boolean finalClaimCommitted = claimCommitted;
        CompletableFuture<OwnerPopulationCommitResult> owner =
                ownerCoordinator.groupCompositeCoordinator().commitPopulationGroupsAsync(
                        prepared.ownerAdmission(), groupRepository, groupOperationId,
                        classification, nowMs);
        return owner.handle((ownerCommit, failure) -> {
            boolean ownerCommitted = failure == null && ownerCommit != null && ownerCommit.committed();
            if (!finalClaimCommitted || !ownerCommitted) {
                markReadinessDegradedSafely(!finalClaimCommitted
                        ? "population_group_claim_commit_failed"
                        : "population_group_owner_commit_failed");
            }
            return new CompanionPopulationCommitResult(
                    finalClaimCommitted && ownerCommitted,
                    finalClaimCommitted && ownerCommitted
                            ? "companion-population-group-committed"
                            : !finalClaimCommitted
                            ? "companion-claim-index-commit-failed"
                            : ownerCommit == null
                            ? "companion-owner-finalize-exception" : ownerCommit.reason(),
                    finalClaimCommitted, ownerCommit);
        });
    }

    /** Completes the retained owner journal after an external source finalizer succeeds. */
    @Nonnull
    public CompletableFuture<Boolean> completeSourceFinalizationAsync(
            @Nonnull PreparedCompanionPopulationAdmission prepared
    ) {
        Objects.requireNonNull(prepared, "prepared");
        try {
            CompletableFuture<Boolean> completion = ownerCoordinator.completeSourceFinalizationAsync(
                    prepared.ownerAdmission()
            );
            return completion == null ? CompletableFuture.completedFuture(false) : completion;
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_source_finalization_commit_failed");
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Persists COMPENSATING while both owner and claim reservations remain held. */
    @Nonnull
    public CompletableFuture<Boolean> beginCompensationAsync(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        Objects.requireNonNull(prepared, "prepared");
        try {
            CompletableFuture<Boolean> completion = ownerCoordinator.beginCompensationAsync(
                    prepared.ownerAdmission(), reason
            );
            return completion == null ? CompletableFuture.completedFuture(false) : completion;
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_population_compensation_start_failed");
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Releases claim capacity only after the restored owner operation is durably FAILED. */
    @Nonnull
    public CompletableFuture<Boolean> completeCompensationAsync(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        Objects.requireNonNull(prepared, "prepared");
        try {
            CompletableFuture<Boolean> ownerCompletion = ownerCoordinator.completeCompensationAsync(
                    prepared.ownerAdmission(), reason
            );
            if (ownerCompletion == null) {
                markReadinessDegradedSafely("companion_population_compensation_close_failed");
                return CompletableFuture.completedFuture(false);
            }
            return ownerCompletion.handle((ownerCanceled, failure) -> {
                boolean claimCanceled = false;
                if (failure == null && Boolean.TRUE.equals(ownerCanceled)) {
                    try {
                        claimCanceled = claimAdmissionService.cancel(prepared.claimReservation());
                    } catch (RuntimeException | LinkageError ignored) {
                        claimCanceled = false;
                    }
                }
                boolean completed = failure == null
                        && Boolean.TRUE.equals(ownerCanceled)
                        && claimCanceled;
                if (!completed) {
                    markReadinessDegradedSafely("companion_population_compensation_close_failed");
                }
                return completed;
            });
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_population_compensation_close_failed");
            return CompletableFuture.completedFuture(false);
        }
    }

    private void markBothReadinessDegraded(@Nonnull String reason) {
        if (ownerCoordinator.usesScopedPersistenceResilience()) {
            ownerCoordinator.markReadinessDegraded(reason);
            return;
        }
        claimAdmissionService.markReadinessDegraded();
        ownerCoordinator.markReadinessDegraded(reason);
    }

    /** Fails closed when a live combined mutation cannot retain its canonical identity mapping. */
    public void markReadinessDegraded(@Nonnull String reason) {
        markBothReadinessDegraded(reason == null || reason.isBlank()
                ? "companion_population_degraded"
                : reason.trim());
    }

    /** Quarantines new work while allowing an already-APPLYING capability to finish durably. */
    public void markCapabilityReadinessDegraded(@Nonnull String reason) {
        Objects.requireNonNull(reason, "reason");
        claimAdmissionService.markReadinessDegraded();
        ownerCoordinator.markAdmissionReadinessDegraded();
    }

    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        Objects.requireNonNull(prepared, "prepared");
        final boolean claimCanceled;
        try {
            claimCanceled = claimAdmissionService.cancel(prepared.claimReservation());
            CompletableFuture<Boolean> ownerCancellation = ownerCoordinator.cancelAsync(
                    prepared.ownerAdmission(), reason
            );
            if (ownerCancellation == null) {
                markReadinessDegradedSafely("companion_population_cancel_stage_missing");
                return CompletableFuture.completedFuture(false);
            }
            return ownerCancellation.handle((ownerCanceled, failure) -> {
                boolean canceled = failure == null
                        && claimCanceled
                        && Boolean.TRUE.equals(ownerCanceled);
                if (!canceled) {
                    markReadinessDegradedSafely("companion_population_cancel_failed");
                }
                return canceled;
            });
        } catch (RuntimeException | LinkageError failure) {
            markReadinessDegradedSafely("companion_population_cancel_failed");
            return CompletableFuture.completedFuture(false);
        }
    }

    private void markReadinessDegradedSafely(@Nonnull String reason) {
        try {
            markBothReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The unresolved reservation remains conservative even if diagnostics also fail.
        }
    }

    private void markAdmissionReadinessDegradedSafely() {
        try {
            claimAdmissionService.markReadinessDegraded();
            ownerCoordinator.markAdmissionReadinessDegraded();
        } catch (RuntimeException | LinkageError ignored) {
            // The unresolved APPLYING journal remains conservative without poisoning storage.
        }
    }

    @Nonnull
    private CompanionPopulationPreparationResult finishPreparation(
            @Nonnull OwnerPopulationPreparationResult ownerResult,
            @Nonnull ClaimAdmissionDecision claimDecision
    ) {
        if (!ownerResult.allowed() || ownerResult.preparedAdmission() == null) {
            claimAdmissionService.cancel(claimDecision.reservation());
            return new CompanionPopulationPreparationResult(
                    false,
                    ownerResult.reason(),
                    ownerResult.decision(),
                    claimDecision,
                    null
            );
        }
        PreparedCompanionPopulationAdmission prepared = new PreparedCompanionPopulationAdmission(
                ownerResult.preparedAdmission(),
                claimDecision
        );
        return new CompanionPopulationPreparationResult(
                true,
                "companion-population-prepared",
                ownerResult.decision(),
                claimDecision,
                prepared
        );
    }
}
