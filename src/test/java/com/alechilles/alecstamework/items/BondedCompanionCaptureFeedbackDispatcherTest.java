package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BondedCompanionCaptureFeedbackDispatcherTest {
    @Test
    void failureEmitsExactlyOneActionableMessage() {
        RecordingSink sink = new RecordingSink();
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        feedback.failure(null, BondedCompanionCaptureAuthor.Status.DATABASE_FAILED);

        assertEquals(1, sink.messages.size());
        assertEquals(0, sink.effects);
        assertEquals(0, sink.spends);
    }

    @Test
    void appliedSuccessSpendsAndEmitsCompletionEffectOnce() {
        RecordingSink sink = new RecordingSink();
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        var result = feedback.success(null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus.APPLIED,
                result.status());
        assertEquals(1, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(0, sink.messages.size());
    }

    @Test
    void durableSuccessStillEmitsEffectOnceWhenItemFinalizationFails() {
        RecordingSink sink = new RecordingSink();
        sink.spendSuccessful = false;
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        var result = feedback.success(null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus
                .FINALIZATION_FAILED, result.status());
        assertEquals(1, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(1, sink.messages.size());
    }

    @Test
    void synchronousItemFinalizerExceptionBecomesOneActionableFailure() {
        RecordingSink sink = new RecordingSink();
        sink.throwOnSpend = true;
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        var result = feedback.success(null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus
                .FINALIZATION_FAILED, result.status());
        assertEquals(1, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(1, sink.messages.size());
    }

    /** Regression: an unacknowledged required effect must not consume the item. */
    @Test
    void failedCompletionEffectDoesNotSpendOrReportApplied() {
        RecordingSink sink = new RecordingSink();
        sink.effectSuccessful = false;
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        var result = feedback.success(null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus
                .EFFECT_FAILED, result.status());
        assertEquals(0, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(1, sink.messages.size());
        assertEquals(true, result.feedbackDelivered());
    }

    /** Regression: a bonded config with no completion outputs is not success. */
    @Test
    void bondedPlanWithoutParticleOrSoundDoesNotEffectOrSpend() {
        RecordingSink sink = new RecordingSink();
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);
        var noOutput = new SpawnerPublishedEffect(
                1, 2, 3, " ", null);

        var result = feedback.success(intent(noOutput), null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus
                .EFFECT_FAILED, result.status());
        assertEquals(0, sink.effects);
        assertEquals(0, sink.spends);
        assertEquals(1, sink.messages.size());
    }

    /** Regression: the concrete production sink must reject a skipped effect. */
    @Test
    void productionSinkAcknowledgesUnavailableEffectAsFailure() {
        var feedback = BondedCompanionCaptureFeedbackDispatcher.production();
        var intent = intent();

        var result = feedback.success(intent, null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus
                .EFFECT_FAILED, result.status());
        assertEquals(false, result.feedbackDelivered());
    }

    @Test
    void falseMessageDeliveryIsReportedAndDiagnosedOnce() {
        RecordingSink sink = new RecordingSink();
        sink.messageSuccessful = false;
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(
                sink, diagnostics);

        boolean delivered = feedback.failure(
                null, BondedCompanionCaptureAuthor.Status.DATABASE_FAILED);

        assertEquals(false, delivered);
        assertEquals(1, sink.messageAttempts);
        assertEquals(1, diagnostics.unavailable);
        assertEquals(null, diagnostics.failure);
    }

    @Test
    void throwingMessageDeliveryIsReportedAndDiagnosedOnce() {
        RecordingSink sink = new RecordingSink();
        sink.throwOnMessage = true;
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(
                sink, diagnostics);

        boolean delivered = feedback.failure(
                null, BondedCompanionCaptureAuthor.Status.DATABASE_FAILED);

        assertEquals(false, delivered);
        assertEquals(1, sink.messageAttempts);
        assertEquals(1, diagnostics.unavailable);
        assertEquals("delivery failed", diagnostics.failure.getMessage());
    }

    @Test
    void deliveryWarningIsContextualAndThrottled() {
        AtomicLong clock = new AtomicLong(1_000L);
        List<String> warnings = new ArrayList<>();
        var diagnostics = new BondedCompanionCaptureFeedbackDispatcher
                .ThrottledDiagnostics(
                clock::get,
                (message, failure) -> warnings.add(message));
        var intent = intent();

        diagnostics.feedbackUnavailable(intent, "first", null);
        diagnostics.feedbackUnavailable(intent, "duplicate", null);
        clock.addAndGet(10_000L);
        diagnostics.feedbackUnavailable(intent, "next-window", null);

        assertEquals(2, warnings.size());
        assertEquals(true, warnings.getFirst().contains(
                "actor=10000000-0000-0000-0000-000000000001"));
        assertEquals(true, warnings.getFirst().contains(
                "roster=hydragon:companions"));
        assertEquals(true, warnings.getFirst().contains("world=world"));
    }

    @Test
    void productionCompletionWithoutCurrentWorldThreadContextFailsClosed() {
        var feedback = BondedCompanionCaptureFeedbackDispatcher.production();
        var intent = intent();

        var result = feedback.success(intent, null);

        assertEquals(BondedCompanionCaptureFeedbackDispatcher.SuccessStatus
                .EFFECT_FAILED, result.status());
    }

    private static BondedCompanionCaptureIntent intent() {
        return intent(null);
    }

    private static BondedCompanionCaptureIntent intent(
            SpawnerPublishedEffect completionEffect
    ) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", "attempt",
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "world", 1, "fingerprint",
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "Dragon_Fire", "hydragon:companions", 4L,
                null, completionEffect,
                true, true, true, true, true, true
        );
    }

    private static final class RecordingSink
            implements BondedCompanionCaptureFeedbackDispatcher.Sink {
        private final List<String> messages = new ArrayList<>();
        private int spends;
        private int effects;
        private int messageAttempts;
        private boolean spendSuccessful = true;
        private boolean effectSuccessful = true;
        private boolean messageSuccessful = true;
        private boolean throwOnSpend;
        private boolean throwOnMessage;

        @Override public boolean spend(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            spends++;
            if (throwOnSpend) throw new IllegalStateException("item changed");
            return spendSuccessful;
        }
        @Override public boolean effect(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            effects++;
            return effectSuccessful;
        }
        @Override public boolean message(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context,
                String message) {
            messageAttempts++;
            if (throwOnMessage) {
                throw new IllegalStateException("delivery failed");
            }
            messages.add(message);
            return messageSuccessful;
        }
    }

    private static final class RecordingDiagnostics
            implements BondedCompanionCaptureFeedbackDispatcher.Diagnostics {
        private int unavailable;
        private Throwable failure;

        @Override public void feedbackUnavailable(
                BondedCompanionCaptureIntent intent, String message,
                Throwable failure) {
            unavailable++;
            this.failure = failure;
        }
    }
}
