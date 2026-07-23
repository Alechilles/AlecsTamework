package com.alechilles.alecstamework.persistence.projection;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Transaction-local port for monotonic outbox events and consumer checkpoints. */
public interface ProjectionOutboxPort {
    @Nonnull
    PersistenceMutationResult<ProjectionEvent> append(@Nonnull ProjectionEventDraft event);

    @Nonnull
    List<ProjectionEvent> readAfter(@Nonnull ProjectionSequence sequence, int limit);

    @Nonnull
    List<ProjectionEvent> findByOperation(@Nonnull OperationId operationId);

    @Nonnull
    ProjectionSequence head();

    @Nonnull
    Optional<ProjectionCheckpoint> findCheckpoint(@Nonnull ProjectionConsumerId consumerId);

    @Nonnull
    PersistenceMutationResult<ProjectionCheckpoint> acknowledge(
            @Nonnull ProjectionConsumerId consumerId,
            @Nonnull ProjectionSequence sequence,
            long acknowledgedAtMs
    );
}
