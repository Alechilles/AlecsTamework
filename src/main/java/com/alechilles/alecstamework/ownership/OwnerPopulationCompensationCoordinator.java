package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Persists the two compensation journal boundaries and keeps the owner reservation conservative
 * until live rollback has succeeded and the journal is durably FAILED.
 */
final class OwnerPopulationCompensationCoordinator {
    private final OwnerPopulationIndex index;
    private final CompanionPopulationRepository repository;
    private final OwnerPopulationJournalTerminality terminality;

    OwnerPopulationCompensationCoordinator(
            @Nonnull OwnerPopulationIndex index,
            @Nonnull CompanionPopulationRepository repository,
            @Nonnull OwnerPopulationJournalTerminality terminality
    ) {
        this.index = Objects.requireNonNull(index, "index");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.terminality = Objects.requireNonNull(terminality, "terminality");
    }

    @Nonnull
    CompletableFuture<Boolean> beginAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        CompletableFuture<Boolean> completion;
        synchronized (prepared) {
            CompletableFuture<Boolean> existing = prepared.compensationStartCompletion();
            if (existing != null) {
                return existing;
            }
            if (prepared.state() == PreparedOwnerPopulationAdmission.State.COMPENSATING
                    || prepared.state() == PreparedOwnerPopulationAdmission.State.COMPENSATION_CLOSING
                    || prepared.state() == PreparedOwnerPopulationAdmission.State.CANCELED) {
                return CompletableFuture.completedFuture(true);
            }
            if (!prepared.transition(
                    PreparedOwnerPopulationAdmission.State.APPLYING,
                    PreparedOwnerPopulationAdmission.State.COMPENSATION_STARTING
            )) {
                return CompletableFuture.completedFuture(false);
            }
            completion = new CompletableFuture<>();
            prepared.compensationStartCompletion(completion);
        }
        submitTransition(
                prepared,
                CompanionPopulationOperationRecord.State.APPLYING,
                CompanionPopulationOperationRecord.State.COMPENSATING,
                normalizeReason(reason),
                completion,
                PreparedOwnerPopulationAdmission.State.COMPENSATING,
                "owner_population_compensation_start_failed"
        );
        return completion;
    }

    @Nonnull
    CompletableFuture<Boolean> completeAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        CompletableFuture<Boolean> completion;
        synchronized (prepared) {
            CompletableFuture<Boolean> existing = prepared.compensationCompletion();
            if (existing != null) {
                return existing;
            }
            if (prepared.state() == PreparedOwnerPopulationAdmission.State.CANCELED) {
                return CompletableFuture.completedFuture(true);
            }
            if (!prepared.transition(
                    PreparedOwnerPopulationAdmission.State.COMPENSATING,
                    PreparedOwnerPopulationAdmission.State.COMPENSATION_CLOSING
            )) {
                return CompletableFuture.completedFuture(false);
            }
            completion = new CompletableFuture<>();
            prepared.compensationCompletion(completion);
        }
        submitClose(prepared, normalizeReason(reason), completion);
        return completion;
    }

    private void submitClose(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason,
            @Nonnull CompletableFuture<Boolean> completion
    ) {
        final PersistenceWriteQueue.WriteSubmission<Boolean> submission;
        try {
            submission = repository.advanceOperationAsync(
                    prepared.operationId().toString(),
                    CompanionPopulationOperationRecord.State.COMPENSATING,
                    CompanionPopulationOperationRecord.State.FAILED,
                    reason
            );
        } catch (RuntimeException | LinkageError failure) {
            finishFailure(prepared, completion, "owner_population_compensation_close_failed");
            return;
        }
        if (submission == null || submission.completion() == null) {
            finishFailure(prepared, completion, "owner_population_compensation_close_failed");
            return;
        }
        submission.completion().whenComplete((outcome, failure) -> {
            boolean closed = failure == null && outcome != null && outcome.isCommitted()
                    && Boolean.TRUE.equals(outcome.value());
            if (!closed) {
                finishFailure(prepared, completion, "owner_population_compensation_close_failed");
                return;
            }
            boolean canceled;
            try {
                canceled = index.cancel(prepared.reservation());
            } catch (RuntimeException | LinkageError cancelFailure) {
                canceled = false;
            }
            if (canceled) {
                prepared.setState(PreparedOwnerPopulationAdmission.State.CANCELED);
                completion.complete(true);
            } else {
                finishFailure(prepared, completion, "owner_population_compensation_index_release_failed");
            }
        });
    }

    private void submitTransition(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull CompanionPopulationOperationRecord.State expected,
            @Nonnull CompanionPopulationOperationRecord.State next,
            @Nonnull String reason,
            @Nonnull CompletableFuture<Boolean> completion,
            @Nonnull PreparedOwnerPopulationAdmission.State successState,
            @Nonnull String failureReason
    ) {
        final PersistenceWriteQueue.WriteSubmission<Boolean> submission;
        try {
            submission = repository.advanceOperationAsync(
                    prepared.operationId().toString(), expected, next, reason
            );
        } catch (RuntimeException | LinkageError failure) {
            finishFailure(prepared, completion, failureReason);
            return;
        }
        if (submission == null || submission.completion() == null) {
            finishFailure(prepared, completion, failureReason);
            return;
        }
        submission.completion().whenComplete((outcome, failure) -> {
            boolean advanced = failure == null && outcome != null && outcome.isCommitted()
                    && Boolean.TRUE.equals(outcome.value());
            if (advanced) {
                prepared.setState(successState);
                completion.complete(true);
            } else {
                finishFailure(prepared, completion, failureReason);
            }
        });
    }

    private void finishFailure(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull CompletableFuture<Boolean> completion,
            @Nonnull String reason
    ) {
        prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
        terminality.degrade(reason);
        completion.complete(false);
    }

    @Nonnull
    private static String normalizeReason(@Nonnull String reason) {
        return reason == null || reason.isBlank()
                ? "owner-population-compensated"
                : reason.trim();
    }
}
