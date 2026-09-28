package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Verifies release outcome selection and prevents effects on the persistence completion thread. */
class OwnedNpcTransformationInteractionServiceTest {
    @Test
    void failedReleaseQueuesOnlyRollback() {
        assertQueuedOutcome(CullTerminalOwnerReleaseService.Outcome.UNAVAILABLE, false, "rolled back");
    }

    @Test
    void publishedReleaseQueuesOnlyCommittedContinuation() {
        assertQueuedOutcome(CullTerminalOwnerReleaseService.Outcome.RELEASED, false, "committed");
    }

    @Test
    void exceptionalReleaseQueuesRollback() {
        assertQueuedOutcome(null, true, "rolled back");
    }

    private static void assertQueuedOutcome(CullTerminalOwnerReleaseService.Outcome outcome,
                                            boolean exceptional, String expected) {
        ArrayDeque<Runnable> worldTasks = new ArrayDeque<>();
        List<String> effects = new ArrayList<>();
        CompletableFuture<CullTerminalOwnerReleaseService.Outcome> release = new CompletableFuture<>();
        OwnedNpcTransformationInteractionService.continueAfterRelease(release, worldTasks::add,
                () -> effects.add("committed"), () -> effects.add("rolled back"));

        assertTrue(worldTasks.isEmpty());
        if (exceptional) release.completeExceptionally(new IllegalStateException("persistence unavailable"));
        else release.complete(outcome);
        assertTrue(effects.isEmpty());
        worldTasks.remove().run();
        assertEquals(List.of(expected), effects);
        assertTrue(worldTasks.isEmpty());
    }
}
