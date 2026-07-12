package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.ImportContext;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.Status;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.SweepResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks one outstanding import write per authority and publishes it only after commit and index
 * refresh. This preserves the crash boundary between durable import steps and live-world effects.
 */
final class ManagedCoopImportWriteCoordinator {
    private final ManagedCoopCompositeIndexRefreshService compositeIndexes;
    private final Map<String, PendingWrite> pending = new ConcurrentHashMap<>();

    ManagedCoopImportWriteCoordinator(
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this.compositeIndexes = Objects.requireNonNull(compositeIndexes, "compositeIndexes");
    }

    @Nonnull
    SweepResult queue(@Nonnull ImportContext context,
                      @Nonnull String operation,
                      @Nonnull PersistenceWriteQueue.WriteSubmission<?> submission) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(submission, "submission");
        if (!submission.accepted()) {
            return blocked("import_write_rejected:" + operation);
        }
        pending.put(context.authorityKey().authorityId(),
                new PendingWrite(operation, submission.completion()));
        return result(Status.WRITE_QUEUED, operation, true);
    }

    @Nullable
    SweepResult settlePending(@Nonnull String authorityId) {
        PendingWrite write = pending.get(Objects.requireNonNull(authorityId, "authorityId"));
        if (write == null) {
            return null;
        }
        if (!write.completion().isDone()) {
            return result(Status.WRITE_PENDING, write.operation(), true);
        }
        pending.remove(authorityId, write);
        final PersistenceWriteQueue.WriteOutcome<?> outcome;
        try {
            outcome = write.completion().join();
        } catch (RuntimeException exception) {
            return blocked("import_write_completion_failed:" + detail(exception));
        }
        if (!outcome.isCommitted()) {
            return blocked("import_write_not_committed:" + outcome.failureReason());
        }
        Object value = outcome.value();
        if (value instanceof ManagedCoopImportRepository.MutationResult mutation
                && !mutation.succeeded()) {
            return blocked("import_mutation_" + mutation.status().name().toLowerCase(Locale.ROOT)
                    + ":" + mutation.detail());
        }
        if (value instanceof ManagedCoopResidentRepository.MutationResult mutation
                && !mutation.succeeded()) {
            return blocked("authority_mutation_" + mutation.status().name().toLowerCase(Locale.ROOT)
                    + ":" + mutation.detail());
        }
        ManagedCoopCompositeIndexRefreshService.RefreshResult refreshed =
                compositeIndexes.refresh();
        if (refreshed == null || !refreshed.refreshed() || !compositeIndexes.isTrusted()) {
            return blocked("import_composite_index_refresh_failed:"
                    + (refreshed == null ? "missing_result" : refreshed.detail()));
        }
        return null;
    }

    private SweepResult blocked(String detail) {
        return result(Status.BLOCKED, detail, true);
    }

    private SweepResult result(Status status, @Nullable String detail, boolean blocks) {
        return new SweepResult(status, detail, blocks);
    }

    private String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record PendingWrite(
            @Nonnull String operation,
            @Nonnull CompletableFuture<? extends PersistenceWriteQueue.WriteOutcome<?>> completion) {
    }
}
