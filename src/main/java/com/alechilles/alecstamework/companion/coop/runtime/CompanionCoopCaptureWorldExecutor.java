package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure receipt-first ordering for one live companion-to-coop capture.
 *
 * <p>The exact receipt-bearing chunk is force-saved on every resolution attempt. Source
 * retirement is reachable only after that save completes and execution returns to the owning
 * world thread.</p>
 */
final class CompanionCoopCaptureWorldExecutor {

    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable CompanionCoopCaptureRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable AttemptGateway attempts
    ) {
        if (!validOperation(request, operation) || attempts == null) {
            return completed(unknown("operation_invariant_mismatch", null));
        }

        ReceiptProbe receipt = safeReceiptProbe(attempts);
        if (receipt.status() == ReceiptStatus.UNAVAILABLE) {
            return completed(retryable(
                    "receipt_store_unavailable", receipt.cause()
            ));
        }
        SourceProbe source = safeSourceProbe(attempts);
        if (source.status() == SourceStatus.CONFLICT) {
            return completed(unknown(
                    "source_identity_conflict", source.cause()
            ));
        }
        if (source.status() == SourceStatus.ABSENT
                && receipt.status() != ReceiptStatus.EXACT) {
            return completed(unknown(
                    "source_absent_without_receipt", receipt.cause()
            ));
        }
        return persistThenFinish(attempts);
    }

    private CompletionStage<LiveOperationResult> persistThenFinish(
            AttemptGateway attempts
    ) {
        CompletionStage<ReceiptPersistence> persistence;
        try {
            persistence = attempts.persistExactReceipt();
        } catch (RuntimeException | LinkageError failure) {
            return completed(retryable("receipt_save_failed", failure));
        }
        if (persistence == null) {
            return completed(retryable("receipt_save_missing", null));
        }

        CompletableFuture<ReceiptPersistence> normalized =
                new CompletableFuture<>();
        persistence.whenComplete((result, failure) -> {
            if (failure != null) {
                normalized.complete(new ReceiptPersistence(
                        ReceiptPersistenceStatus.RETRYABLE, failure
                ));
            } else {
                normalized.complete(result);
            }
        });
        return normalized.thenCompose(result -> afterPersistence(
                attempts, result
        ));
    }

    private CompletionStage<LiveOperationResult> afterPersistence(
            AttemptGateway attempts,
            @Nullable ReceiptPersistence persistence
    ) {
        if (persistence == null || persistence.status() == null) {
            return completed(retryable("receipt_save_missing", null));
        }
        if (persistence.status() == ReceiptPersistenceStatus.RETRYABLE) {
            return completed(retryable(
                    "receipt_save_failed", persistence.cause()
            ));
        }
        if (persistence.status() == ReceiptPersistenceStatus.CONFLICT) {
            return completed(unknown(
                    "receipt_identity_conflict", persistence.cause()
            ));
        }
        return resumeOnWorldThread(attempts);
    }

    private CompletionStage<LiveOperationResult> resumeOnWorldThread(
            AttemptGateway attempts
    ) {
        CompletionStage<LiveOperationResult> resumed;
        try {
            resumed = attempts.resumeOnWorldThread(
                    () -> finishAfterDurableReceipt(attempts)
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(retryable("world_resume_failed", failure));
        }
        if (resumed == null) {
            return completed(retryable("world_resume_missing", null));
        }
        CompletableFuture<LiveOperationResult> normalized =
                new CompletableFuture<>();
        resumed.whenComplete((result, failure) -> {
            if (failure != null) {
                normalized.complete(retryable(
                        "world_resume_failed", failure
                ));
            } else if (result == null) {
                normalized.complete(retryable(
                        "world_resume_missing", null
                ));
            } else {
                normalized.complete(result);
            }
        });
        return normalized;
    }

    private LiveOperationResult finishAfterDurableReceipt(
            AttemptGateway attempts
    ) {
        ReceiptProbe receipt = safeReceiptProbe(attempts);
        if (receipt.status() == ReceiptStatus.UNAVAILABLE) {
            return retryable("receipt_readback_unavailable", receipt.cause());
        }
        if (receipt.status() != ReceiptStatus.EXACT) {
            return unknown("receipt_readback_conflict", receipt.cause());
        }

        SourceProbe source = safeSourceProbe(attempts);
        if (source.status() == SourceStatus.ABSENT) {
            return confirmed("durable_receipt_source_absent");
        }
        if (source.status() == SourceStatus.CONFLICT) {
            return unknown("source_identity_conflict", source.cause());
        }

        RetirementAttempt retirement = safeRetirement(attempts);
        return switch (retirement.status()) {
            case ABSENT -> confirmed("durable_receipt_source_retired");
            case STILL_PRESENT -> retryable(
                    "durable_receipt_source_still_present",
                    retirement.cause()
            );
            case RETRYABLE -> retryable(
                    "durable_receipt_source_retirement_failed",
                    retirement.cause()
            );
            case CONFLICT -> unknown(
                    "source_identity_conflict", retirement.cause()
            );
        };
    }

    private boolean validOperation(
            @Nullable CompanionCoopCaptureRequest request,
            @Nullable OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && CompanionCoopCaptureDefinition.KIND.equals(operation.kind())
                && request.expectedLifecycleRevision().equals(
                        operation.expectedLifecycleRevision()
                )
                && operation.participants().contains(
                        OperationScope.profile(request.profileId())
                )
                && operation.participants().contains(
                        OperationScope.coop(request.targetSlot().toString())
                );
    }

    private ReceiptProbe safeReceiptProbe(AttemptGateway attempts) {
        try {
            ReceiptProbe result = attempts.probeReceipt();
            return result == null || result.status() == null
                    ? ReceiptProbe.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptProbe.conflict(failure);
        }
    }

    private SourceProbe safeSourceProbe(AttemptGateway attempts) {
        try {
            SourceProbe result = attempts.probeSource();
            return result == null || result.status() == null
                    ? SourceProbe.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return SourceProbe.conflict(failure);
        }
    }

    private RetirementAttempt safeRetirement(AttemptGateway attempts) {
        try {
            RetirementAttempt result = attempts.retireExactSource();
            return result == null || result.status() == null
                    ? RetirementAttempt.retryable(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return RetirementAttempt.retryable(failure);
        }
    }

    private LiveOperationResult confirmed(String suffix) {
        return LiveOperationResult.confirmed(code(suffix));
    }

    private LiveOperationResult retryable(
            String suffix,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.retryable(code(suffix), cause);
    }

    private LiveOperationResult unknown(
            String suffix,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.unknown(code(suffix), cause);
    }

    private String code(String suffix) {
        return "coop_capture_" + suffix;
    }

    private CompletionStage<LiveOperationResult> completed(
            LiveOperationResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    enum ReceiptStatus {
        EXACT,
        ABSENT,
        CONFLICT,
        UNAVAILABLE
    }

    record ReceiptProbe(
            @Nonnull ReceiptStatus status,
            @Nullable Throwable cause
    ) {
        static ReceiptProbe exact() {
            return new ReceiptProbe(ReceiptStatus.EXACT, null);
        }

        static ReceiptProbe absent() {
            return new ReceiptProbe(ReceiptStatus.ABSENT, null);
        }

        static ReceiptProbe conflict(@Nullable Throwable cause) {
            return new ReceiptProbe(ReceiptStatus.CONFLICT, cause);
        }

        static ReceiptProbe unavailable(@Nullable Throwable cause) {
            return new ReceiptProbe(ReceiptStatus.UNAVAILABLE, cause);
        }
    }

    enum SourceStatus {
        EXACT,
        ABSENT,
        CONFLICT
    }

    record SourceProbe(
            @Nonnull SourceStatus status,
            @Nullable Throwable cause
    ) {
        static SourceProbe exact() {
            return new SourceProbe(SourceStatus.EXACT, null);
        }

        static SourceProbe absent() {
            return new SourceProbe(SourceStatus.ABSENT, null);
        }

        static SourceProbe conflict(@Nullable Throwable cause) {
            return new SourceProbe(SourceStatus.CONFLICT, cause);
        }
    }

    enum ReceiptPersistenceStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record ReceiptPersistence(
            @Nonnull ReceiptPersistenceStatus status,
            @Nullable Throwable cause
    ) {
        static ReceiptPersistence saved() {
            return new ReceiptPersistence(ReceiptPersistenceStatus.SAVED, null);
        }

        static ReceiptPersistence retryable(@Nullable Throwable cause) {
            return new ReceiptPersistence(
                    ReceiptPersistenceStatus.RETRYABLE, cause
            );
        }

        static ReceiptPersistence conflict(@Nullable Throwable cause) {
            return new ReceiptPersistence(
                    ReceiptPersistenceStatus.CONFLICT, cause
            );
        }
    }

    enum RetirementStatus {
        ABSENT,
        STILL_PRESENT,
        RETRYABLE,
        CONFLICT
    }

    record RetirementAttempt(
            @Nonnull RetirementStatus status,
            @Nullable Throwable cause
    ) {
        static RetirementAttempt absent() {
            return new RetirementAttempt(RetirementStatus.ABSENT, null);
        }

        static RetirementAttempt stillPresent() {
            return new RetirementAttempt(
                    RetirementStatus.STILL_PRESENT, null
            );
        }

        static RetirementAttempt retryable(@Nullable Throwable cause) {
            return new RetirementAttempt(
                    RetirementStatus.RETRYABLE, cause
            );
        }

        static RetirementAttempt conflict(@Nullable Throwable cause) {
            return new RetirementAttempt(
                    RetirementStatus.CONFLICT, cause
            );
        }
    }

    interface AttemptGateway {
        @Nonnull
        ReceiptProbe probeReceipt();

        @Nonnull
        SourceProbe probeSource();

        @Nonnull
        CompletionStage<ReceiptPersistence> persistExactReceipt();

        @Nonnull
        CompletionStage<LiveOperationResult> resumeOnWorldThread(
                @Nonnull Supplier<LiveOperationResult> continuation
        );

        @Nonnull
        RetirementAttempt retireExactSource();
    }
}
