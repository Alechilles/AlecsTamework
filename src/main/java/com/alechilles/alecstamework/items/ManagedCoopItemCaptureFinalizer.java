package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Completes a captured-item admission only after the matching item-retirement receipt exists. */
public final class ManagedCoopItemCaptureFinalizer {
    public record Outcome(boolean completed, @Nullable String detail) {
    }

    private final CompletionGateway completion;
    private final RefreshGateway refresh;
    private final LongSupplier clock;

    public ManagedCoopItemCaptureFinalizer(
            @Nonnull CoopLifecycleOperationRepository repository,
            @Nonnull ManagedCoopCompositeIndexRefreshService indexes) {
        this(
                (ready, nowMs) -> committed(repository.completeCapture(
                        ready.operationId(), ready.operationGeneration(), nowMs)),
                indexes::refresh,
                System::currentTimeMillis
        );
    }

    ManagedCoopItemCaptureFinalizer(@Nonnull CompletionGateway completion,
                                    @Nonnull RefreshGateway refresh,
                                    @Nonnull LongSupplier clock) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Advances SOURCE_RETIRE_REQUESTED to COMPLETE and republishes both indexes as one epoch. */
    @Nonnull
    public CompletionStage<Outcome> complete(@Nonnull RetirementReady ready) {
        if (ready == null || ready.durableState() != OperationState.SOURCE_RETIRE_REQUESTED) {
            return CompletableFuture.completedFuture(
                    new Outcome(false, "item_capture_finalization_state_invalid"));
        }
        final CompletionStage<MutationResult> submitted;
        try {
            submitted = completion.complete(ready, clock.getAsLong());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    new Outcome(false, detail("item_capture_completion", exception)));
        }
        if (submitted == null) {
            return CompletableFuture.completedFuture(
                    new Outcome(false, "item_capture_completion_missing"));
        }
        return submitted.handle((mutation, failure) -> {
            if (failure != null) {
                return new Outcome(false, detail("item_capture_completion", unwrap(failure)));
            }
            if (!matchesCompleted(ready, mutation)) {
                return new Outcome(false, mutationDetail(mutation));
            }
            final ManagedCoopCompositeIndexRefreshService.RefreshResult refreshed;
            try {
                refreshed = refresh.refresh();
            } catch (RuntimeException exception) {
                return new Outcome(false, detail("item_capture_index_refresh", exception));
            }
            if (refreshed == null || !refreshed.refreshed()) {
                return new Outcome(
                        false,
                        "item_capture_index_refresh_rejected"
                                + suffix(refreshed != null ? refreshed.detail() : null));
            }
            return new Outcome(true, null);
        });
    }

    private boolean matchesCompleted(RetirementReady ready, @Nullable MutationResult result) {
        if (result == null || !result.succeeded() || result.operation() == null) {
            return false;
        }
        OperationRecord operation = result.operation();
        return operation.kind() == OperationKind.CAPTURE
                && operation.state() == OperationState.COMPLETE
                && !operation.active()
                && operation.operationId().equals(ready.operationId())
                && operation.profileId().equals(ready.profileId())
                && operation.authorityKey().equals(ready.authorityKey())
                && operation.coopId().equals(ready.coopId())
                && operation.residentSlot() == ready.residentSlot()
                && Objects.equals(operation.sourceNpcUuid(), ready.sourceNpcUuid())
                && Objects.equals(operation.snapshotHash(), ready.snapshotHash());
    }

    @Nonnull
    private String mutationDetail(@Nullable MutationResult result) {
        if (result == null) {
            return "item_capture_completion_result_missing";
        }
        return "item_capture_completion_" + result.status().name().toLowerCase()
                + suffix(result.detail());
    }

    @Nonnull
    private static CompletionStage<MutationResult> committed(
            @Nullable PersistenceWriteQueue.WriteSubmission<MutationResult> submission) {
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("item_capture_completion_submission_missing"));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome == null
                    || outcome.status() != PersistenceWriteQueue.WriteStatus.COMMITTED
                    || outcome.value() == null) {
                String reason = outcome != null ? outcome.failureReason() : null;
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "item_capture_completion_not_committed" + suffix(reason)));
            }
            return CompletableFuture.completedFuture(outcome.value());
        });
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    @Nonnull
    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed" + suffix(message != null
                ? message
                : failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    @Nonnull
    private static String suffix(@Nullable String value) {
        return value == null || value.isBlank() ? "" : ":" + value;
    }

    @FunctionalInterface
    interface CompletionGateway {
        @Nonnull
        CompletionStage<MutationResult> complete(@Nonnull RetirementReady ready, long nowMs);
    }

    @FunctionalInterface
    interface RefreshGateway {
        @Nonnull
        ManagedCoopCompositeIndexRefreshService.RefreshResult refresh();
    }
}
