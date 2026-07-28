package com.alechilles.alecstamework.persistence.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drain accounting tests for accepted and already-completed workflows. */
class PublicPersistenceWorkflowTrackerTest {
    @Test
    void pendingWorkflowMustFinishBeforeDrainCompletes() {
        PublicPersistenceWorkflowTracker tracker =
                new PublicPersistenceWorkflowTracker();
        CompletableFuture<String> pending = new CompletableFuture<>();
        tracker.track(pending);

        var timedOut = tracker.drain(Duration.ZERO);
        assertFalse(timedOut.drained());
        assertEquals(1, timedOut.outstanding());

        pending.complete("done");
        assertTrue(tracker.drain(Duration.ZERO).drained());
    }

    @Test
    void synchronousCompletionCannotEscapeOrUnderflowAccounting() {
        PublicPersistenceWorkflowTracker tracker =
                new PublicPersistenceWorkflowTracker();

        tracker.track(CompletableFuture.completedFuture("done"));

        assertEquals(0, tracker.outstanding());
        assertTrue(tracker.drain(Duration.ZERO).drained());
    }
}
