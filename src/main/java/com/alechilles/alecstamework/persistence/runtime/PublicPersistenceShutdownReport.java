package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteKernelShutdownReport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Visible evidence for the exact boundary reached by ordered shutdown. */
public record PublicPersistenceShutdownReport(
        @Nonnull Status status,
        int outstandingWorkflows,
        @Nullable SqliteKernelShutdownReport kernel,
        @Nullable Throwable failure
) {
    public PublicPersistenceShutdownReport {
        if (status == null || outstandingWorkflows < 0
                || (status == Status.COMPLETE && failure != null)) {
            throw new IllegalArgumentException(
                    "Consistent public shutdown evidence is required"
            );
        }
    }

    public boolean terminal() {
        return status == Status.COMPLETE
                || status == Status.COMPLETE_UNCLEAN;
    }

    public enum Status {
        COMPLETE,
        COMPLETE_UNCLEAN,
        QUIESCE_FAILED,
        FEATURE_DRAIN_TIMED_OUT,
        KERNEL_DRAIN_TIMED_OUT,
        CONTROL_CLOSE_FAILED
    }
}
