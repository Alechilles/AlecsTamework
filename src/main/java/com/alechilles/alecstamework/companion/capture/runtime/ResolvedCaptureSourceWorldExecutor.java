package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Receipt-first exactly-once source spending for a terminal capture roll. */
final class ResolvedCaptureSourceWorldExecutor {

    @Nonnull
    LiveOperationResult execute(@Nonnull Gateway gateway) {
        if (gateway == null) {
            return unknown("source_gateway_missing", null);
        }
        SpendProbe initial = safeProbe(gateway);
        return switch (initial.status()) {
            case SPENT -> confirmed("source_receipt_spent");
            case RECEIPTED_SOURCE -> consume(gateway);
            case SOURCE -> receiptThenConsume(gateway);
            case ABSENT -> unknown(
                    "source_absent_without_receipt", initial.cause()
            );
            case CONFLICT -> unknown(
                    "source_receipt_conflict", initial.cause()
            );
        };
    }

    private LiveOperationResult receiptThenConsume(Gateway gateway) {
        ReceiptAttempt receipt = safeReceipt(gateway);
        if (receipt.status() == ReceiptStatus.AMBIGUOUS) {
            return classifyAfterReceipt(gateway, receipt.cause());
        }
        if (receipt.status() == ReceiptStatus.SOURCE_UNCHANGED) {
            return retryable(
                    "source_receipt_not_installed", receipt.cause()
            );
        }
        SpendProbe after = safeProbe(gateway);
        return switch (after.status()) {
            case SPENT -> confirmed("source_receipt_spent");
            case RECEIPTED_SOURCE -> consume(gateway);
            case SOURCE -> retryable(
                    "source_receipt_not_installed", after.cause()
            );
            case ABSENT -> unknown(
                    "source_absent_without_receipt", after.cause()
            );
            case CONFLICT -> unknown(
                    "source_receipt_conflict", after.cause()
            );
        };
    }

    private LiveOperationResult classifyAfterReceipt(
            Gateway gateway,
            Throwable cause
    ) {
        SpendProbe after = safeProbe(gateway);
        return switch (after.status()) {
            case SPENT -> confirmed("source_receipt_spent");
            case RECEIPTED_SOURCE -> consume(gateway);
            case SOURCE -> retryable(
                    "source_receipt_ambiguous", cause
            );
            case ABSENT, CONFLICT -> unknown(
                    "source_receipt_ambiguous",
                    cause == null ? after.cause() : cause
            );
        };
    }

    private LiveOperationResult consume(Gateway gateway) {
        ConsumptionAttempt consumed = safeConsume(gateway);
        SpendProbe after = safeProbe(gateway);
        if (after.status() == SpendStatus.SPENT) {
            return confirmed("source_receipt_spent");
        }
        if (after.status() == SpendStatus.RECEIPTED_SOURCE
                && consumed.status() != ConsumptionStatus.AMBIGUOUS) {
            return retryable(
                    "source_receipt_not_spent", consumed.cause()
            );
        }
        return unknown(
                "source_spend_ambiguous",
                consumed.cause() == null ? after.cause() : consumed.cause()
        );
    }

    private SpendProbe safeProbe(Gateway gateway) {
        try {
            SpendProbe probe = gateway.probe();
            return probe == null || probe.status() == null
                    ? SpendProbe.conflict(null)
                    : probe;
        } catch (RuntimeException | LinkageError failure) {
            return SpendProbe.conflict(failure);
        }
    }

    private ReceiptAttempt safeReceipt(Gateway gateway) {
        try {
            ReceiptAttempt result = gateway.installReceipt();
            return result == null || result.status() == null
                    ? ReceiptAttempt.ambiguous(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptAttempt.ambiguous(failure);
        }
    }

    private ConsumptionAttempt safeConsume(Gateway gateway) {
        try {
            ConsumptionAttempt result = gateway.consumeReceiptedSource();
            return result == null || result.status() == null
                    ? ConsumptionAttempt.ambiguous(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ConsumptionAttempt.ambiguous(failure);
        }
    }

    private LiveOperationResult confirmed(String code) {
        return LiveOperationResult.confirmed("capture_" + code);
    }

    private LiveOperationResult retryable(
            String code,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.retryable(
                "capture_" + code, cause
        );
    }

    private LiveOperationResult unknown(
            String code,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.unknown("capture_" + code, cause);
    }

    interface Gateway {
        @Nonnull
        SpendProbe probe();

        @Nonnull
        ReceiptAttempt installReceipt();

        @Nonnull
        ConsumptionAttempt consumeReceiptedSource();
    }

    enum SpendStatus {
        SPENT,
        RECEIPTED_SOURCE,
        SOURCE,
        ABSENT,
        CONFLICT
    }

    record SpendProbe(
            @Nonnull SpendStatus status,
            @Nullable Throwable cause
    ) {
        static SpendProbe spent() {
            return new SpendProbe(SpendStatus.SPENT, null);
        }

        static SpendProbe receiptedSource() {
            return new SpendProbe(SpendStatus.RECEIPTED_SOURCE, null);
        }

        static SpendProbe source() {
            return new SpendProbe(SpendStatus.SOURCE, null);
        }

        static SpendProbe absent() {
            return new SpendProbe(SpendStatus.ABSENT, null);
        }

        static SpendProbe conflict(@Nullable Throwable cause) {
            return new SpendProbe(SpendStatus.CONFLICT, cause);
        }
    }

    enum ReceiptStatus {
        RECEIPTED,
        SOURCE_UNCHANGED,
        AMBIGUOUS
    }

    record ReceiptAttempt(
            @Nonnull ReceiptStatus status,
            @Nullable Throwable cause
    ) {
        static ReceiptAttempt receipted() {
            return new ReceiptAttempt(ReceiptStatus.RECEIPTED, null);
        }

        static ReceiptAttempt sourceUnchanged(
                @Nullable Throwable cause
        ) {
            return new ReceiptAttempt(
                    ReceiptStatus.SOURCE_UNCHANGED, cause
            );
        }

        static ReceiptAttempt ambiguous(@Nullable Throwable cause) {
            return new ReceiptAttempt(ReceiptStatus.AMBIGUOUS, cause);
        }
    }

    enum ConsumptionStatus {
        SPENT,
        STILL_PRESENT,
        AMBIGUOUS
    }

    record ConsumptionAttempt(
            @Nonnull ConsumptionStatus status,
            @Nullable Throwable cause
    ) {
        static ConsumptionAttempt spent() {
            return new ConsumptionAttempt(
                    ConsumptionStatus.SPENT, null
            );
        }

        static ConsumptionAttempt stillPresent(
                @Nullable Throwable cause
        ) {
            return new ConsumptionAttempt(
                    ConsumptionStatus.STILL_PRESENT, cause
            );
        }

        static ConsumptionAttempt ambiguous(
                @Nullable Throwable cause
        ) {
            return new ConsumptionAttempt(
                    ConsumptionStatus.AMBIGUOUS, cause
            );
        }
    }
}
