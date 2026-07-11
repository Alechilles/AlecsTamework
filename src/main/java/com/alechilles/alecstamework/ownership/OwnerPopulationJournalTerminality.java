package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Centralizes conservative terminal results for owner-population journal failures. */
final class OwnerPopulationJournalTerminality {
    private final OwnerPopulationIndex index;
    private final PersistenceHealthService health;

    OwnerPopulationJournalTerminality(@Nonnull OwnerPopulationIndex index,
                                      @Nonnull PersistenceHealthService health) {
        this.index = Objects.requireNonNull(index, "index");
        this.health = Objects.requireNonNull(health, "health");
    }

    void degrade(@Nonnull String reason) {
        index.setReadiness(OwnerPopulationReadiness.DEGRADED);
        health.markDegraded(Objects.requireNonNull(reason, "reason"));
    }

    @Nonnull
    CompletableFuture<OwnerPopulationPreparationResult> preparationStartFailed(
            @Nonnull OwnerPopulationDecision decision,
            @Nonnull String reason
    ) {
        index.cancel(decision.reservation());
        degrade(reason.replace('-', '_'));
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
        degrade(reason.replace('-', '_'));
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
