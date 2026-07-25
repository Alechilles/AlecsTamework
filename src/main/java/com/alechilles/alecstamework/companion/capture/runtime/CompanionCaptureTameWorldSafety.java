package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AccessProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MarkerAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.TargetProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Fail-closed invocation and asynchronous re-entry guards for tame capture attempts. */
final class CompanionCaptureTameWorldSafety {

    ResolvedCaptureSourceWorldExecutor.SpendProbe spendProbe(
            AttemptGateway attempts
    ) {
        try {
            var value = attempts.probe();
            return value == null || value.status() == null
                    ? ResolvedCaptureSourceWorldExecutor.SpendProbe
                    .conflict(null)
                    : value;
        } catch (RuntimeException | LinkageError failure) {
            return ResolvedCaptureSourceWorldExecutor.SpendProbe
                    .conflict(failure);
        }
    }

    ResolvedCaptureSourceWorldExecutor.ReceiptAttempt installSourceReceipt(
            AttemptGateway attempts
    ) {
        try {
            var value = attempts.installReceipt();
            return value == null || value.status() == null
                    ? ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
                    .ambiguous(null)
                    : value;
        } catch (RuntimeException | LinkageError failure) {
            return ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
                    .ambiguous(failure);
        }
    }

    AccessProbe accessProbe(AttemptGateway attempts) {
        try {
            AccessProbe value = attempts.probeCommandAccess();
            return value == null || value.status() == null
                    ? AccessProbe.conflict(null)
                    : value;
        } catch (RuntimeException | LinkageError failure) {
            return AccessProbe.conflict(failure);
        }
    }

    TargetProbe targetProbe(AttemptGateway attempts) {
        try {
            TargetProbe value = attempts.probeTarget();
            return value == null || value.status() == null
                    ? TargetProbe.conflict(null)
                    : value;
        } catch (RuntimeException | LinkageError failure) {
            return TargetProbe.conflict(failure);
        }
    }

    boolean roleResolvable(AttemptGateway attempts) {
        try {
            return attempts.targetRoleResolvable();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    MarkerAttempt installMarker(AttemptGateway attempts) {
        try {
            MarkerAttempt value = attempts.installTargetMarker();
            return value == null || value.status() == null
                    ? MarkerAttempt.conflict(null)
                    : value;
        } catch (RuntimeException | LinkageError failure) {
            TargetProbe after = targetProbe(attempts);
            return after.status()
                    == CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING
                    || after.status()
                    == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET
                    ? MarkerAttempt.exact()
                    : after.status()
                    == CompanionCaptureTameWorldAttempt.TargetStatus.UNCHANGED
                    ? MarkerAttempt.retryable(failure)
                    : MarkerAttempt.conflict(failure);
        }
    }

    MutationAttempt converge(AttemptGateway attempts) {
        try {
            MutationAttempt value = attempts.convergeTarget();
            return value == null || value.status() == null
                    ? MutationAttempt.conflict(null)
                    : value;
        } catch (RuntimeException | LinkageError failure) {
            TargetProbe after = targetProbe(attempts);
            return after.status()
                    == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET
                    ? MutationAttempt.applied()
                    : after.status()
                    == CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING
                    ? MutationAttempt.retryable(failure)
                    : MutationAttempt.conflict(failure);
        }
    }

    CompletionStage<ReceiptPersistence> persistActor(
            AttemptGateway attempts
    ) {
        try {
            CompletionStage<ReceiptPersistence> value =
                    attempts.persistActor();
            return value == null
                    ? completed(ReceiptPersistence.retryable(null))
                    : value.exceptionally(ReceiptPersistence::retryable);
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    CompletionStage<ReceiptPersistence> persistTarget(
            AttemptGateway attempts
    ) {
        try {
            CompletionStage<ReceiptPersistence> value =
                    attempts.persistTarget();
            return value == null
                    ? completed(ReceiptPersistence.retryable(null))
                    : value.exceptionally(ReceiptPersistence::retryable);
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    CompletionStage<LiveOperationResult> resume(
            AttemptGateway attempts,
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            String failureCode
    ) {
        try {
            CompletionStage<LiveOperationResult> value =
                    attempts.resumeOnWorldThread(continuation);
            return mapResume(value, failureCode);
        } catch (RuntimeException | LinkageError failure) {
            return completed(LiveOperationResult.retryable(
                    failureCode, failure
            ));
        }
    }

    CompletionStage<LiveOperationResult> resumeAfterTick(
            AttemptGateway attempts,
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            String failureCode
    ) {
        try {
            CompletionStage<LiveOperationResult> value =
                    attempts.resumeAfterWorldTick(continuation);
            return mapResume(value, failureCode);
        } catch (RuntimeException | LinkageError failure) {
            return completed(LiveOperationResult.retryable(
                    failureCode, failure
            ));
        }
    }

    private CompletionStage<LiveOperationResult> mapResume(
            CompletionStage<LiveOperationResult> value,
            String failureCode
    ) {
        return value == null
                ? completed(LiveOperationResult.retryable(
                        failureCode, null
                ))
                : value.exceptionally(failure ->
                        LiveOperationResult.retryable(
                                failureCode, failure
                        ));
    }

    private <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
