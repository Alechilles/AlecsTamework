package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Refreshes the pure runtime lifecycle-operation index from one complete typed repository read. */
public final class ManagedCoopLifecycleOperationIndexRefreshService {
    public enum RefreshStatus {
        REFRESHED,
        REJECTED
    }

    public record RefreshResult(@Nonnull RefreshStatus status,
                                long revision,
                                @Nullable String detail) {
        public boolean refreshed() {
            return status == RefreshStatus.REFRESHED;
        }
    }

    private final ManagedCoopLifecycleOperationIndex index;
    private final SnapshotSource source;
    private final WarningSink warningSink;

    public ManagedCoopLifecycleOperationIndexRefreshService(
            @Nonnull CoopLifecycleOperationRepository repository,
            @Nonnull ManagedCoopLifecycleOperationIndex index,
            @Nullable HytaleLogger logger) {
        this(index, new RepositorySnapshotSource(repository), loggerWarningSink(logger));
    }

    ManagedCoopLifecycleOperationIndexRefreshService(
            @Nonnull ManagedCoopLifecycleOperationIndex index,
            @Nonnull SnapshotSource source,
            @Nonnull WarningSink warningSink) {
        this.index = Objects.requireNonNull(index, "index");
        this.source = Objects.requireNonNull(source, "source");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /** Performs one explicit repository read and atomically publishes it only when fully valid. */
    @Nonnull
    public synchronized RefreshResult refresh() {
        final ManagedCoopReadResult<List<OperationRecord>> operations;
        try {
            operations = source.loadOperations();
        } catch (RuntimeException exception) {
            index.rebuild(null);
            return rejected("operations:source_exception:" + exceptionDetail(exception));
        }

        ManagedCoopLifecycleOperationIndex.RebuildResult rebuild = index.rebuild(operations);
        if (rebuild.rebuilt()) {
            return new RefreshResult(
                    RefreshStatus.REFRESHED,
                    index.snapshot().revision(),
                    null
            );
        }
        String typedFailure = typedFailure(operations);
        return rejected(typedFailure == null ? rebuild.detail() : typedFailure);
    }

    @Nonnull
    private RefreshResult rejected(@Nullable String detail) {
        String resolved = detail == null || detail.isBlank()
                ? "coop_lifecycle_operation_index_refresh_rejected"
                : detail;
        warningSink.warn("Managed coop lifecycle operation index refresh rejected: " + resolved);
        return new RefreshResult(RefreshStatus.REJECTED, index.snapshot().revision(), resolved);
    }

    @Nullable
    private String typedFailure(@Nullable ManagedCoopReadResult<?> result) {
        if (result == null) {
            return "operations:missing_read_result";
        }
        if (result.status() == ManagedCoopReadResult.Status.LOADED) {
            return null;
        }
        if (result.failure() == null) {
            return "operations:" + result.status().name().toLowerCase();
        }
        return "operations:" + result.failure().kind().name().toLowerCase()
                + ":" + result.failure().detail();
    }

    @Nonnull
    private static String exceptionDetail(@Nonnull RuntimeException exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isBlank()
                ? exception.getClass().getSimpleName()
                : detail;
    }

    @Nonnull
    private static WarningSink loggerWarningSink(@Nullable HytaleLogger logger) {
        if (logger == null) {
            return ignored -> { };
        }
        return message -> logger.at(Level.WARNING).log(message);
    }

    interface SnapshotSource {
        @Nullable
        ManagedCoopReadResult<List<OperationRecord>> loadOperations();
    }

    interface WarningSink {
        void warn(@Nonnull String message);
    }

    private record RepositorySnapshotSource(
            CoopLifecycleOperationRepository repository) implements SnapshotSource {
        private RepositorySnapshotSource {
            Objects.requireNonNull(repository, "repository");
        }

        @Nonnull
        @Override
        public ManagedCoopReadResult<List<OperationRecord>> loadOperations() {
            return repository.loadAllActiveOperations();
        }
    }
}
