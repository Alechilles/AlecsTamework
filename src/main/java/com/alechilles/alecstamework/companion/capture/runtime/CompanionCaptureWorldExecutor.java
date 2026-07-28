package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure receipt-first state machine for exact inventory replacement and target retirement.
 *
 * <p>The captured artifact is the only positive receipt. Once it exists, target retirement can
 * be retried safely. Before it exists, target absence is never interpreted as success.</p>
 */
final class CompanionCaptureWorldExecutor {
    private final ResolvedCaptureSourceWorldExecutor sourceSpend =
            new ResolvedCaptureSourceWorldExecutor();

    @Nonnull
    LiveOperationResult execute(
            @Nullable CompanionCaptureRequest request,
            @Nullable OperationEnvelope operation,
            @Nonnull AttemptGateway attempts
    ) {
        if (!validOperation(request, operation) || attempts == null) {
            return unknown("operation_invariant_mismatch", null);
        }
        if (request.failedAttempt()) {
            return sourceSpend.execute(attempts);
        }

        InventoryProbe inventory = safeInventoryProbe(attempts);
        if (inventory.status() == InventoryStatus.CONFLICT) {
            return unknown("inventory_conflict", inventory.cause());
        }

        TargetProbe target = safeTargetProbe(attempts);
        if (target.status() == TargetStatus.CONFLICT) {
            return unknown("target_identity_conflict", target.cause());
        }

        if (inventory.status() == InventoryStatus.SOURCE) {
            if (target.status() == TargetStatus.ABSENT) {
                return retryable("target_absent_before_receipt", null);
            }
            ReplacementAttempt replacement = safeReplace(attempts);
            if (replacement.status() == ReplacementStatus.SOURCE_UNCHANGED) {
                return retryable("inventory_source_unchanged", replacement.cause());
            }
            if (replacement.status() == ReplacementStatus.AMBIGUOUS) {
                return unknown("inventory_mutation_ambiguous", replacement.cause());
            }
        }

        return finishRetirement(attempts);
    }

    @Nonnull
    private LiveOperationResult finishRetirement(AttemptGateway attempts) {
        TargetProbe target = safeTargetProbe(attempts);
        if (target.status() == TargetStatus.ABSENT) {
            return confirmed("artifact_receipt_target_absent");
        }
        if (target.status() == TargetStatus.CONFLICT) {
            return unknown("target_identity_conflict", target.cause());
        }

        RetirementAttempt retirement = safeRetire(attempts);
        return switch (retirement.status()) {
            case ABSENT -> confirmed("artifact_receipt_target_retired");
            case STILL_PRESENT -> retryable(
                    "artifact_receipt_target_still_present",
                    retirement.cause()
            );
            case RETRYABLE_FAILURE -> retryable(
                    "artifact_receipt_target_retirement_failed",
                    retirement.cause()
            );
            case CONFLICT -> unknown(
                    "target_identity_conflict",
                    retirement.cause()
            );
        };
    }

    private boolean validOperation(
            @Nullable CompanionCaptureRequest request,
            @Nullable OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && !request.tameAndCommandLink()
                && CompanionCaptureDefinition.KIND.equals(operation.kind())
                && request.expectedLifecycleRevision().equals(
                        operation.expectedLifecycleRevision()
                );
    }

    @Nonnull
    private InventoryProbe safeInventoryProbe(AttemptGateway attempts) {
        try {
            InventoryProbe result = attempts.probeInventory();
            return result == null || result.status() == null
                    ? InventoryProbe.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return InventoryProbe.conflict(failure);
        }
    }

    @Nonnull
    private TargetProbe safeTargetProbe(AttemptGateway attempts) {
        try {
            TargetProbe result = attempts.probeTarget();
            return result == null || result.status() == null
                    ? TargetProbe.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return TargetProbe.conflict(failure);
        }
    }

    @Nonnull
    private ReplacementAttempt safeReplace(AttemptGateway attempts) {
        try {
            ReplacementAttempt result = attempts.replaceSourceWithArtifact();
            return result == null || result.status() == null
                    ? ReplacementAttempt.ambiguous(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReplacementAttempt.ambiguous(failure);
        }
    }

    @Nonnull
    private RetirementAttempt safeRetire(AttemptGateway attempts) {
        try {
            RetirementAttempt result = attempts.retireExactTarget();
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
        return "capture_" + suffix;
    }

    enum InventoryStatus {
        ARTIFACT,
        SOURCE,
        CONFLICT
    }

    record InventoryProbe(
            @Nonnull InventoryStatus status,
            @Nullable Throwable cause
    ) {
        static InventoryProbe artifact() {
            return new InventoryProbe(InventoryStatus.ARTIFACT, null);
        }

        static InventoryProbe source() {
            return new InventoryProbe(InventoryStatus.SOURCE, null);
        }

        static InventoryProbe conflict(@Nullable Throwable cause) {
            return new InventoryProbe(InventoryStatus.CONFLICT, cause);
        }
    }

    enum TargetStatus {
        EXACT,
        ABSENT,
        CONFLICT
    }

    record TargetProbe(
            @Nonnull TargetStatus status,
            @Nullable Throwable cause
    ) {
        static TargetProbe exact() {
            return new TargetProbe(TargetStatus.EXACT, null);
        }

        static TargetProbe absent() {
            return new TargetProbe(TargetStatus.ABSENT, null);
        }

        static TargetProbe conflict(@Nullable Throwable cause) {
            return new TargetProbe(TargetStatus.CONFLICT, cause);
        }
    }

    enum ReplacementStatus {
        ARTIFACT,
        SOURCE_UNCHANGED,
        AMBIGUOUS
    }

    record ReplacementAttempt(
            @Nonnull ReplacementStatus status,
            @Nullable Throwable cause
    ) {
        static ReplacementAttempt artifact() {
            return new ReplacementAttempt(ReplacementStatus.ARTIFACT, null);
        }

        static ReplacementAttempt sourceUnchanged(@Nullable Throwable cause) {
            return new ReplacementAttempt(
                    ReplacementStatus.SOURCE_UNCHANGED,
                    cause
            );
        }

        static ReplacementAttempt ambiguous(@Nullable Throwable cause) {
            return new ReplacementAttempt(ReplacementStatus.AMBIGUOUS, cause);
        }
    }

    enum RetirementStatus {
        ABSENT,
        STILL_PRESENT,
        RETRYABLE_FAILURE,
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
                    RetirementStatus.STILL_PRESENT,
                    null
            );
        }

        static RetirementAttempt retryable(@Nullable Throwable cause) {
            return new RetirementAttempt(
                    RetirementStatus.RETRYABLE_FAILURE,
                    cause
            );
        }

        static RetirementAttempt conflict(@Nullable Throwable cause) {
            return new RetirementAttempt(RetirementStatus.CONFLICT, cause);
        }
    }

    interface AttemptGateway
            extends ResolvedCaptureSourceWorldExecutor.Gateway {
        @Override
        default ResolvedCaptureSourceWorldExecutor.SpendProbe probe() {
            return ResolvedCaptureSourceWorldExecutor.SpendProbe
                    .conflict(null);
        }

        @Override
        default ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
        installReceipt() {
            return ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
                    .ambiguous(null);
        }

        @Override
        default ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt
        consumeReceiptedSource() {
            return ResolvedCaptureSourceWorldExecutor
                    .ConsumptionAttempt.ambiguous(null);
        }

        @Nonnull
        InventoryProbe probeInventory();

        @Nonnull
        TargetProbe probeTarget();

        @Nonnull
        ReplacementAttempt replaceSourceWithArtifact();

        @Nonnull
        RetirementAttempt retireExactTarget();
    }
}
