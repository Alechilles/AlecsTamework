package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable terminal result shared by the two replacement spawner persistence authors. */
public record SpawnerPersistenceAuthorResult(
        @Nonnull Kind kind,
        @Nonnull Status status,
        @Nullable OperationId operationId,
        @Nullable OperationWorkflowResult.Status workflowStatus,
        @Nullable String detail,
        @Nullable Throwable failure
) {
    public SpawnerPersistenceAuthorResult {
        Objects.requireNonNull(kind, "Spawner operation kind is required");
        Objects.requireNonNull(status, "Spawner result status is required");
    }

    /** Returns whether the requested gameplay transition reached published canonical state. */
    public boolean published() {
        return status == Status.PUBLISHED;
    }

    static SpawnerPersistenceAuthorResult of(
            Kind kind,
            Status status,
            OperationId operationId,
            OperationWorkflowResult.Status workflow,
            String detail,
            Throwable failure
    ) {
        return new SpawnerPersistenceAuthorResult(
                kind, status, operationId, workflow, detail, failure
        );
    }

    /** Released spawner lifecycle operation being authored. */
    public enum Kind {
        CAPTURE,
        CAPTURE_RELEASE
    }

    /** Stable result vocabulary for feedback and retry decisions. */
    public enum Status {
        PUBLISHED,
        COMPENSATED,
        INVALID_CONTEXT,
        EVIDENCE_FAILED,
        PROFILE_READ_FAILED,
        PROFILE_CONFLICT,
        ADOPTION_REJECTED,
        ADOPTION_FAILED,
        SNAPSHOT_DECODE_FAILED,
        PLACEMENT_FAILED,
        SUBMISSION_REJECTED,
        WORKFLOW_FAILED
    }
}
