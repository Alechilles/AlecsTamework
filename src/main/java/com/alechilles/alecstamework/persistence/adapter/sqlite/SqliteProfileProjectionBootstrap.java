package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.internal.CompanionProfileObserverProjection;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Rebuilds the synchronous public profile lookup from one canonical read. */
final class SqliteProfileProjectionBootstrap {
    private SqliteProfileProjectionBootstrap() {
    }

    @Nonnull
    static CompletionStage<Result> rebuild(
            @Nonnull SqliteCompanionProfileReader reader,
            @Nonnull CompanionProfileObserverProjection projection
    ) {
        if (reader == null || projection == null) {
            throw new IllegalArgumentException(
                    "Profile projection bootstrap dependencies are required"
            );
        }
        return reader.findAllProjectionStates().thenApply(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    List<CompanionProfileProjectionState>> found)) {
                return new Result(
                        Status.CANONICAL_READ_FAILED,
                        readFailure(read)
                );
            }
            try {
                projection.rebuild(found.value());
                return new Result(Status.COMPLETE, null);
            } catch (Throwable failure) {
                return new Result(
                        Status.CANONICAL_REBUILD_FAILED,
                        failure
                );
            }
        });
    }

    private static Throwable readFailure(PersistenceReadResult<?> read) {
        if (read instanceof PersistenceReadResult.Failed<?> failed) {
            return new IllegalStateException(
                    failed.failure().code(),
                    failed.failure().cause()
            );
        }
        return new IllegalStateException(
                "canonical_profiles_rebuild_read_absent"
        );
    }

    enum Status {
        COMPLETE,
        CANONICAL_READ_FAILED,
        CANONICAL_REBUILD_FAILED
    }

    record Result(@Nonnull Status status, Throwable failure) {
        Result {
            if (status == null
                    || (status == Status.COMPLETE) != (failure == null)) {
                throw new IllegalArgumentException(
                        "Consistent profile projection result is required"
                );
            }
        }
    }
}
