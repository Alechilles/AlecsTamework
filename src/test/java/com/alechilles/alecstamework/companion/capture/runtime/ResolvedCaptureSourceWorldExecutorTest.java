package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResolvedCaptureSourceWorldExecutorTest {
    private final ResolvedCaptureSourceWorldExecutor executor =
            new ResolvedCaptureSourceWorldExecutor();

    @Test
    void installsReceiptBeforeSpendingAndConfirmsExactReadback() {
        FakeSpend spend = new FakeSpend();
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe.source()
        );
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe
                        .receiptedSource()
        );
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe.spent()
        );

        LiveOperationResult result = executor.execute(spend);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(1, spend.receipts);
        assertEquals(1, spend.consumptions);
    }

    @Test
    void absenceWithoutPositiveReceiptFailsClosed() {
        FakeSpend spend = new FakeSpend();
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe.absent()
        );

        LiveOperationResult result = executor.execute(spend);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "capture_source_absent_without_receipt",
                result.code()
        );
    }

    @Test
    void receiptWithUnspentSourceResumesOnlyConsumption() {
        FakeSpend spend = new FakeSpend();
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe
                        .receiptedSource()
        );
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe.spent()
        );

        LiveOperationResult result = executor.execute(spend);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(0, spend.receipts);
        assertEquals(1, spend.consumptions);
    }

    @Test
    void exactSpentReceiptIsIdempotent() {
        FakeSpend spend = new FakeSpend();
        spend.probes.add(
                ResolvedCaptureSourceWorldExecutor.SpendProbe.spent()
        );

        LiveOperationResult result = executor.execute(spend);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(0, spend.receipts);
        assertEquals(0, spend.consumptions);
    }

    private static final class FakeSpend
            implements ResolvedCaptureSourceWorldExecutor.Gateway {
        private final ArrayDeque<
                ResolvedCaptureSourceWorldExecutor.SpendProbe> probes =
                new ArrayDeque<>();
        private int receipts;
        private int consumptions;

        @Override
        public ResolvedCaptureSourceWorldExecutor.SpendProbe probe() {
            return probes.removeFirst();
        }

        @Override
        public ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
        installReceipt() {
            receipts++;
            return ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
                    .receipted();
        }

        @Override
        public ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt
        consumeReceiptedSource() {
            consumptions++;
            return ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt
                    .spent();
        }
    }
}
