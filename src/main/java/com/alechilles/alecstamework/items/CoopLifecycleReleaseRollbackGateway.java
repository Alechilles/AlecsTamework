package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Commits the exact-generation v5 rollback for a definitively absent release projection. */
final class CoopLifecycleReleaseRollbackGateway
        implements ManagedCoopReleasePopulationCoordinator.LifecycleRollbackGateway {
    private final CoopLifecycleOperationRepository repository;

    CoopLifecycleReleaseRollbackGateway(@Nonnull CoopLifecycleOperationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public CompletableFuture<MutationResult> failBeforeProjection(
            String operationId,
            long expectedOperationGeneration,
            String reason,
            long nowMs) {
        PersistenceWriteQueue.WriteSubmission<MutationResult> submission =
                repository.failReleaseBeforeProjection(
                        operationId, expectedOperationGeneration, reason, nowMs);
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "release lifecycle rollback submission missing"));
        }
        return submission.completion().thenApply(outcome -> {
            if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                throw new IllegalStateException(outcome != null
                        && outcome.failureReason() != null
                        ? outcome.failureReason()
                        : "release lifecycle rollback not committed");
            }
            return outcome.value();
        });
    }
}
