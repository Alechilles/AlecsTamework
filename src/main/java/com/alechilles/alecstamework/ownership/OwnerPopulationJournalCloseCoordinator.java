package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Closes owner-population journal operations after cancellation or source finalization.
 *
 * <p>This coordinator owns terminal closeout only. Durable compensation intent and rollback
 * completion remain isolated in {@link OwnerPopulationCompensationCoordinator} so an applying
 * operation cannot accidentally bypass the {@code COMPENSATING} boundary.
 */
final class OwnerPopulationJournalCloseCoordinator {
    private final OwnerPopulationIndex index;
    private final CompanionPopulationRepository repository;
    private final OwnerPopulationJournalTerminality terminality;

    OwnerPopulationJournalCloseCoordinator(@Nonnull OwnerPopulationIndex index,
                                           @Nonnull CompanionPopulationRepository repository,
                                           @Nonnull OwnerPopulationJournalTerminality terminality) {
        this.index = Objects.requireNonNull(index, "index");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.terminality = Objects.requireNonNull(terminality, "terminality");
    }

    @Nonnull
    CompletableFuture<Boolean> completeSourceFinalizationAsync(
            @Nonnull PreparedOwnerPopulationAdmission prepared
    ) {
        Objects.requireNonNull(prepared, "prepared");
        final CompletableFuture<Boolean> completion;
        synchronized (prepared) {
            CompletableFuture<Boolean> existing = prepared.sourceFinalizationCompletion();
            if (existing != null) {
                return existing;
            }
            if (prepared.state() == PreparedOwnerPopulationAdmission.State.COMMITTED) {
                return CompletableFuture.completedFuture(true);
            }
            if (!prepared.transition(
                    PreparedOwnerPopulationAdmission.State.SOURCE_FINALIZATION_PENDING,
                    PreparedOwnerPopulationAdmission.State.SOURCE_FINALIZING
            )) {
                return CompletableFuture.completedFuture(false);
            }
            completion = new CompletableFuture<>();
            prepared.sourceFinalizationCompletion(completion);
        }
        submitSourceFinalization(prepared, completion);
        return completion;
    }

    @Nonnull
    CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedOwnerPopulationAdmission prepared,
                                           @Nonnull String reason) {
        Objects.requireNonNull(prepared, "prepared");
        String normalizedReason = reason == null || reason.isBlank()
                ? "owner-population-canceled"
                : reason.trim();
        CompletableFuture<Boolean> completion;
        synchronized (prepared) {
            CompletableFuture<Boolean> existing = prepared.cancellationCompletion();
            if (existing != null) {
                return existing;
            }
            PreparedOwnerPopulationAdmission.State current = prepared.state();
            if (current == PreparedOwnerPopulationAdmission.State.CANCELED) {
                return CompletableFuture.completedFuture(true);
            }
            if ((current != PreparedOwnerPopulationAdmission.State.PREPARED
                    && current != PreparedOwnerPopulationAdmission.State.APPLYING)
                    || !prepared.transition(current, PreparedOwnerPopulationAdmission.State.CANCELED)) {
                return CompletableFuture.completedFuture(false);
            }
            completion = new CompletableFuture<>();
            prepared.cancellationCompletion(completion);
        }
        startCancellation(prepared, normalizedReason, completion);
        return completion;
    }

    @Nonnull
    CompletableFuture<Boolean> closeApplyingJournal(@Nonnull UUID operationId,
                                                    @Nonnull String reason) {
        try {
            PersistenceWriteQueue.WriteSubmission<Boolean> close = repository.advanceOperationAsync(
                    operationId.toString(),
                    CompanionPopulationOperationRecord.State.APPLYING,
                    CompanionPopulationOperationRecord.State.FAILED,
                    reason
            );
            if (close == null || close.completion() == null) {
                return CompletableFuture.completedFuture(false);
            }
            return close.completion().handle((outcome, failure) ->
                    failure == null
                            && outcome != null
                            && outcome.isCommitted()
                            && Boolean.TRUE.equals(outcome.value())
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private void submitSourceFinalization(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull CompletableFuture<Boolean> completion
    ) {
        try {
            PersistenceWriteQueue.WriteSubmission<Boolean> submission =
                    repository.completeSourceFinalizationAsync(prepared.operationId().toString());
            if (submission == null || submission.completion() == null) {
                finishSourceFinalization(prepared, completion, false);
                return;
            }
            submission.completion().whenComplete((outcome, failure) -> finishSourceFinalization(
                    prepared,
                    completion,
                    failure == null && outcome != null && outcome.isCommitted()
                            && Boolean.TRUE.equals(outcome.value())
            ));
        } catch (RuntimeException | LinkageError failure) {
            finishSourceFinalization(prepared, completion, false);
        }
    }

    private void startCancellation(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull String reason,
            @Nonnull CompletableFuture<Boolean> completion
    ) {
        final boolean indexCanceled;
        final CompletableFuture<Boolean> close;
        try {
            indexCanceled = index.cancel(prepared.reservation());
            close = closeApplyingJournal(prepared.operationId(), reason);
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_population_cancel_start_failed");
            completion.complete(false);
            return;
        }
        close.whenComplete((closed, failure) -> {
            boolean success = indexCanceled && failure == null && Boolean.TRUE.equals(closed);
            if (!success) {
                terminality.degrade("owner_population_cancel_journal_close_failed");
            }
            completion.complete(success);
        });
    }

    private void finishSourceFinalization(
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull CompletableFuture<Boolean> completion,
            boolean succeeded
    ) {
        if (succeeded) {
            prepared.setState(PreparedOwnerPopulationAdmission.State.COMMITTED);
        } else {
            prepared.setState(PreparedOwnerPopulationAdmission.State.DEGRADED);
            terminality.degrade("owner_population_source_finalization_commit_failed");
        }
        completion.complete(succeeded);
    }
}
