package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact outcome of the shared database-only operation workflow.
 *
 * @param status final workflow status
 * @param operation latest durable envelope when one exists
 * @param events committed projection events, when loaded
 * @param failure diagnostic cause for a non-published result
 */
public record DatabaseOperationResult(
        @Nonnull Status status,
        @Nullable OperationEnvelope operation,
        @Nonnull List<ProjectionEvent> events,
        @Nullable Throwable failure
) {
    public DatabaseOperationResult {
        if (status == null) {
            throw new IllegalArgumentException("Database operation status is required");
        }
        if (events == null) {
            throw new IllegalArgumentException("Database operation events are required");
        }
        events = List.copyOf(events);
        if (status == Status.PUBLISHED && operation == null) {
            throw new IllegalArgumentException("Published result requires an operation");
        }
        if (status == Status.PUBLISHED && operation.phase() != OperationPhase.PUBLISHED) {
            throw new IllegalArgumentException("Published result requires a published envelope");
        }
        if (status != Status.PUBLISHED && failure == null) {
            throw new IllegalArgumentException("Incomplete database operation requires a failure");
        }
        if (status == Status.PUBLISHED && failure != null) {
            throw new IllegalArgumentException("Published result cannot carry a failure");
        }
    }

    /** Stable workflow outcomes used by feature facades and recovery. */
    public enum Status {
        PUBLISHED,
        PREPARE_FAILED,
        DURABLE_READ_FAILED,
        DURABLE_COMMIT_FAILED,
        PUBLICATION_PENDING,
        TERMINALIZATION_FAILED,
        INVALID_PHASE
    }
}
