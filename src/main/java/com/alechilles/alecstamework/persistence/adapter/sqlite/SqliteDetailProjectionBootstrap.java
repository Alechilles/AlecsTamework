package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionIndex;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningProjectionIndex;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Rebuilds independent feature-detail projections in deterministic order. */
final class SqliteDetailProjectionBootstrap {
    private SqliteDetailProjectionBootstrap() {
    }

    @Nonnull
    static CompletionStage<Result> rebuild(
            @Nonnull SqliteProvisioningReader provisioningReader,
            @Nonnull ProvisioningProjectionIndex provisioning,
            @Nonnull SqliteProfileExtensionReader extensionReader,
            @Nonnull ProfileExtensionProjectionIndex extensions
    ) {
        if (provisioningReader == null || provisioning == null
                || extensionReader == null || extensions == null) {
            throw new IllegalArgumentException(
                    "Detail projection bootstrap dependencies are required"
            );
        }
        return provisioningReader.findAll().thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    List<ProvisioningRecord>> found)) {
                return java.util.concurrent.CompletableFuture
                        .completedFuture(
                                Result.readFailure("provisioning", read)
                        );
            }
            try {
                provisioning.rebuild(found.value());
            } catch (Throwable failure) {
                return java.util.concurrent.CompletableFuture
                        .completedFuture(Result.rebuildFailure(failure));
            }
            return rebuildExtensions(extensionReader, extensions);
        });
    }

    private static CompletionStage<Result> rebuildExtensions(
            SqliteProfileExtensionReader reader,
            ProfileExtensionProjectionIndex projection
    ) {
        return reader.findAll().thenApply(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    List<ProfileExtensionData>> found)) {
                return Result.readFailure("extensions", read);
            }
            try {
                projection.rebuild(found.value());
                return Result.success();
            } catch (Throwable failure) {
                return Result.rebuildFailure(failure);
            }
        });
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
                        "Consistent detail projection result is required"
                );
            }
        }

        boolean complete() {
            return status == Status.COMPLETE;
        }

        static Result success() {
            return new Result(Status.COMPLETE, null);
        }

        static Result rebuildFailure(Throwable failure) {
            return new Result(Status.CANONICAL_REBUILD_FAILED, failure);
        }

        static Result readFailure(
                String authority,
                PersistenceReadResult<?> read
        ) {
            Throwable failure =
                    read instanceof PersistenceReadResult.Failed<?> failed
                            ? new IllegalStateException(
                            failed.failure().code(),
                            failed.failure().cause()
                    )
                            : new IllegalStateException(
                            "canonical_" + authority
                                    + "_rebuild_read_absent"
                    );
            return new Result(Status.CANONICAL_READ_FAILED, failure);
        }
    }
}
