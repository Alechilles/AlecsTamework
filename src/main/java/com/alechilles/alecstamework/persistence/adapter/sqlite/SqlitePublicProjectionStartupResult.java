package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit canonical-rebuild and durable-catch-up outcome for startup. */
public record SqlitePublicProjectionStartupResult(
        @Nonnull Status status,
        @Nonnull List<ProjectionCatchUpResult> catchUps,
        @Nullable Throwable failure
) {
    public SqlitePublicProjectionStartupResult {
        if (status == null || catchUps == null
                || catchUps.stream().anyMatch(java.util.Objects::isNull)
                || (status == Status.COMPLETE) != (failure == null)) {
            throw new IllegalArgumentException(
                    "Complete projection startup result is required"
            );
        }
        catchUps = List.copyOf(catchUps);
    }

    public enum Status {
        COMPLETE,
        CANONICAL_READ_FAILED,
        CANONICAL_REBUILD_FAILED,
        CATCH_UP_FAILED
    }
}
