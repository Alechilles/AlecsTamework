package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteKernelShutdownReport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Visible evidence for the result of every attempted shutdown phase.
 *
 * @param terminal whether storage and lease ownership reached a final state
 */
public record PublicPersistenceShutdownReport(
        @Nonnull Status status,
        int outstandingWorkflows,
        @Nullable SqliteKernelShutdownReport kernel,
        @Nullable Throwable failure,
        boolean terminal
) {
    public PublicPersistenceShutdownReport {
        if (status == null || outstandingWorkflows < 0
                || (status == Status.COMPLETE && failure != null)
                || ((status == Status.COMPLETE
                || status == Status.COMPLETE_UNCLEAN) && !terminal)
                || (status == Status.KERNEL_DRAIN_TIMED_OUT && terminal)) {
            throw new IllegalArgumentException(
                    "Consistent public shutdown evidence is required"
            );
        }
    }

    public enum Status {
        COMPLETE,
        COMPLETE_UNCLEAN,
        QUIESCE_FAILED,
        FEATURE_DRAIN_TIMED_OUT,
        FEATURE_DRAIN_FAILED,
        KERNEL_DRAIN_TIMED_OUT,
        KERNEL_CLOSE_FAILED,
        CONTROL_CLOSE_FAILED
    }
}
