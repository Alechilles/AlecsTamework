package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Centralizes conservative terminal results for owner-population journal failures. */
final class OwnerPopulationJournalTerminality {
    private final OwnerPopulationIndex index;
    private final PersistenceHealthService health;
    private final OwnerPopulationPersistenceGuard persistenceGuard;

    OwnerPopulationJournalTerminality(@Nonnull OwnerPopulationIndex index,
                                      @Nonnull PersistenceHealthService health) {
        this.index = Objects.requireNonNull(index, "index");
        this.health = Objects.requireNonNull(health, "health");
        this.persistenceGuard = null;
    }

    OwnerPopulationJournalTerminality(@Nonnull OwnerPopulationIndex index,
                                      @Nonnull PersistenceHealthService health,
                                      @Nonnull OwnerPopulationPersistenceGuard persistenceGuard) {
        this.index = Objects.requireNonNull(index, "index");
        this.health = Objects.requireNonNull(health, "health");
        this.persistenceGuard = Objects.requireNonNull(persistenceGuard, "persistenceGuard");
    }

    void degrade(@Nonnull String reason) {
        if (persistenceGuard == null) {
            index.setReadiness(OwnerPopulationReadiness.DEGRADED);
            health.markDegraded(Objects.requireNonNull(reason, "reason"));
            return;
        }
        persistenceGuard.reportFeatureAmbiguity(Objects.requireNonNull(reason, "reason"));
    }

    /** Blocks new admissions after a rolled-back domain CAS without poisoning healthy storage. */
    void degradeAdmissionOnly() {
        index.setReadiness(OwnerPopulationReadiness.DEGRADED);
    }

    @Nonnull
    CompletableFuture<OwnerPopulationPreparationResult> preparationStartFailed(
            @Nonnull OwnerPopulationAdmissionPlan plan,
            @Nonnull OwnerPopulationDecision decision,
            @Nonnull String reason
    ) {
        index.cancel(decision.reservation());
        if (persistenceGuard == null) {
            degrade(reason.replace('-', '_'));
        } else {
            persistenceGuard.reportAmbiguity(
                    plan, decision.reservation().tokenId().toString(), reason,
                    PersistenceOperationPhase.PREPARED, PersistenceTransactionOutcome.NOT_STARTED,
                    false, null);
        }
        return CompletableFuture.completedFuture(deniedPreparation(decision, reason));
    }

    @Nonnull
    OwnerPopulationPreparationResult deniedPreparation(
            @Nonnull OwnerPopulationDecision decision,
            @Nonnull String reason
    ) {
        OwnerPopulationDecision denied = new OwnerPopulationDecision(
                false,
                reason,
                null,
                decision.readiness(),
                decision.limit(),
                decision.committedCount(),
                decision.pendingCount(),
                decision.currentRevision(),
                decision.positiveDelta(),
                decision.forced()
        );
        return new OwnerPopulationPreparationResult(false, reason, denied, null);
    }

    @Nonnull
    CompletableFuture<OwnerPopulationCommitResult> commitStartFailed(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        return CompletableFuture.completedFuture(commitStartFailedResult(prepared, reason));
    }

    @Nonnull
    OwnerPopulationCommitResult commitStartFailedResult(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
        if (persistenceGuard == null) {
            degrade(reason.replace('-', '_'));
        } else {
            persistenceGuard.reportAmbiguity(
                    prepared.plan(), prepared.operationId().toString(), reason,
                    PersistenceOperationPhase.COMMIT, PersistenceTransactionOutcome.NOT_STARTED,
                    true, null);
        }
        return new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                reason,
                null
        );
    }

    void observeJournalClose(
            @Nonnull CompletableFuture<Boolean> close,
            @Nonnull String failureReason
    ) {
        try {
            close.whenComplete((closed, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(closed)) {
                    degrade(failureReason);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            degrade(failureReason);
        }
    }
}
