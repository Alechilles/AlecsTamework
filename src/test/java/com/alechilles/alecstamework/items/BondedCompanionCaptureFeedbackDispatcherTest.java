package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

        feedback.success(null);

        assertEquals(1, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(0, sink.messages.size());
    }

    @Test
    void durableSuccessStillEmitsEffectOnceWhenItemFinalizationFails() {
        RecordingSink sink = new RecordingSink();
        sink.spendSuccessful = false;
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        feedback.success(null);

        assertEquals(1, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(1, sink.messages.size());
    }

    @Test
    void synchronousItemFinalizerExceptionBecomesOneActionableFailure() {
        RecordingSink sink = new RecordingSink();
        sink.throwOnSpend = true;
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        boolean completed = feedback.success(null);

        assertEquals(false, completed);
        assertEquals(1, sink.spends);
        assertEquals(1, sink.effects);
        assertEquals(1, sink.messages.size());
    }

    @Test
    void productionCompletionWithoutCurrentWorldThreadContextFailsClosed() {
        var feedback = BondedCompanionCaptureFeedbackDispatcher.production();
        var intent = new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", "attempt",
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "world", 1, "fingerprint",
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "Dragon_Fire", "hydragon:companions", 4L,
                null, null, true, true, true, true, true, true
        );

        boolean completed = feedback.success(intent, null);

        assertEquals(false, completed);
    }

    private static final class RecordingSink
            implements BondedCompanionCaptureFeedbackDispatcher.Sink {
        private final List<String> messages = new ArrayList<>();
        private int spends;
        private int effects;
        private boolean spendSuccessful = true;
        private boolean throwOnSpend;

        @Override public boolean spend(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            spends++;
            if (throwOnSpend) throw new IllegalStateException("item changed");
            return spendSuccessful;
        }
        @Override public void effect(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            effects++;
        }
        @Override public void message(BondedCompanionCaptureIntent intent,
                                      BondedCompanionCaptureFeedbackDispatcher.CompletionContext context,
                                      String message) {
            messages.add(message);
        }
    }
}
