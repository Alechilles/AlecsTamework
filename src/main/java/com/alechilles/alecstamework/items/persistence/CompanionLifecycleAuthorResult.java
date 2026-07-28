package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stable terminal result shared by replacement dormant and restoration authors.
 */
public record CompanionLifecycleAuthorResult(
        @Nonnull Kind kind,
        @Nonnull Status status,
        @Nullable OperationId operationId,
        @Nullable OperationWorkflowResult.Status workflowStatus,
        @Nullable String detail,
        @Nullable Throwable failure
) {
    public CompanionLifecycleAuthorResult {
        Objects.requireNonNull(kind, "Lifecycle author kind is required");
        Objects.requireNonNull(status, "Lifecycle author status is required");
        detail = normalize(detail);
    }

    /** Returns whether canonical lifecycle state was published. */
    public boolean published() {
        return status == Status.PUBLISHED;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Replacement lifecycle transition being authored. */
    public enum Kind {
        DORMANT,
        RESTORATION
    }

    /** Stable result vocabulary for gameplay feedback and retry decisions. */
    public enum Status {
        PUBLISHED,
        INVALID_CONTEXT,
        INVALID_EVIDENCE,
        EVIDENCE_FAILED,
        PROFILE_READ_FAILED,
        PROFILE_CONFLICT,
        RESTORATION_DISABLED,
        COOLDOWN_ACTIVE,
        SNAPSHOT_DECODE_FAILED,
        SUBMISSION_REJECTED,
        WORKFLOW_FAILED
    }
}
