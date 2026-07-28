package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import javax.annotation.Nonnull;

/**
 * Structured shutdown evidence for the replacement SQLite kernel.
 *
 * @param writer writer admission/drain outcome
 * @param checkpoint checkpoint outcome or skip status
 * @param reads read admission/drain outcome
 */
public record SqliteKernelShutdownReport(@Nonnull PersistenceShutdownResult writer,
                                         @Nonnull CheckpointOutcome checkpoint,
                                         @Nonnull PersistenceShutdownResult reads) {
    public SqliteKernelShutdownReport {
        if (writer == null || checkpoint == null || reads == null) {
            throw new IllegalArgumentException("Complete SQLite kernel shutdown evidence is required");
        }
    }

    /** Returns whether every shutdown boundary reached a clean terminal state. */
    public boolean clean() {
        return writer.status() != PersistenceShutdownResult.Status.TIMED_OUT
                && checkpoint.status() == CheckpointStatus.COMPLETED
                && (reads.status() == PersistenceShutdownResult.Status.DRAINED
                || reads.status() == PersistenceShutdownResult.Status.ALREADY_CLOSED);
    }

    public enum CheckpointStatus {
        COMPLETED,
        FAILED,
        SKIPPED_WRITER_ACTIVE
    }

    /** Checkpoint status and optional adapter result. */
    public record CheckpointOutcome(@Nonnull CheckpointStatus status,
                                    SqliteCheckpointResult result) {
        public CheckpointOutcome {
            if (status == null) {
                throw new IllegalArgumentException("Checkpoint status is required");
            }
            if (status == CheckpointStatus.SKIPPED_WRITER_ACTIVE && result != null) {
                throw new IllegalArgumentException("Skipped checkpoint cannot carry a result");
            }
            if (status != CheckpointStatus.SKIPPED_WRITER_ACTIVE && result == null) {
                throw new IllegalArgumentException("Attempted checkpoint requires a result");
            }
        }
    }
}
