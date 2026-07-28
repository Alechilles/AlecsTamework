package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import javax.annotation.Nonnull;

/** Exact result of a replacement WAL checkpoint attempt. */
public sealed interface SqliteCheckpointResult
        permits SqliteCheckpointResult.Completed, SqliteCheckpointResult.Failed {

    /** Successfully checkpointed WAL state. */
    record Completed(int logFrames, int checkpointedFrames) implements SqliteCheckpointResult {
        public Completed {
            if (logFrames < 0 || checkpointedFrames < 0) {
                throw new IllegalArgumentException("Checkpoint frame counts cannot be negative");
            }
        }
    }

    /** Failed or busy checkpoint that did not establish a clean WAL boundary. */
    record Failed(@Nonnull StorageFailure failure) implements SqliteCheckpointResult {
        public Failed {
            if (failure == null) {
                throw new IllegalArgumentException("Checkpoint failure details are required");
            }
        }
    }
}
