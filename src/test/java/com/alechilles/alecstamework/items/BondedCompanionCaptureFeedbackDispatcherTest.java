package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
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
    void failedSpendDoesNotEmitAFalseSuccessEffect() {
        RecordingSink sink = new RecordingSink();
        sink.spendSuccessful = false;
        var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

        feedback.success(null);

        assertEquals(1, sink.spends);
        assertEquals(0, sink.effects);
        assertEquals(1, sink.messages.size());
    }

    private static final class RecordingSink
            implements BondedCompanionCaptureFeedbackDispatcher.Sink {
        private final List<String> messages = new ArrayList<>();
        private int spends;
        private int effects;
        private boolean spendSuccessful = true;

        @Override public boolean spend(BondedCompanionCaptureIntent intent) {
            spends++;
            return spendSuccessful;
        }
        @Override public void effect(BondedCompanionCaptureIntent intent) {
            effects++;
        }
        @Override public void message(BondedCompanionCaptureIntent intent,
                                      String message) {
            messages.add(message);
        }
    }
}
