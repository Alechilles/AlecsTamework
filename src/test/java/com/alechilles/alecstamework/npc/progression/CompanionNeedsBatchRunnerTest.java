package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Protects bounded needs work and oldest-first spillover between world callbacks. */
class CompanionNeedsBatchRunnerTest {
    @Test
    void processesAtMost128DueIdsAndCarriesDueWorkForward() {
        CompanionNeedsRuntimeRegistry.WorldState state =
                CompanionNeedsRuntimeRegistry.newStateForTests();
        for (int i = 0; i < 130; i++) {
            state.register(new UUID(0L, i + 1L), -2_000L);
        }
        AtomicInteger updates = new AtomicInteger();
        CompanionNeedsBatchRunner runner = new CompanionNeedsBatchRunner(npcId -> {
            updates.incrementAndGet();
            return CompanionNeedsScheduledUpdate.outcomeForRatiosForTests(
                    0.9,
                    0.9,
                    false,
                    false,
                    2_000L
            );
        });

        CompanionNeedsBatchRunner.BatchResult first = runner.run(
                null,
                state,
                0L,
                () -> 0L
        );
        CompanionNeedsBatchRunner.BatchResult second = runner.run(
                null,
                state,
                0L,
                () -> 0L
        );

        assertEquals(128, first.processed());
        assertTrue(first.hasRemainingDue());
        assertEquals(2, second.processed());
        assertFalse(second.hasRemainingDue());
        assertEquals(130, updates.get());
    }

    @Test
    void emptyScheduleDoesNotInvokeUpdater() {
        CompanionNeedsRuntimeRegistry.WorldState state =
                CompanionNeedsRuntimeRegistry.newStateForTests();
        AtomicInteger updates = new AtomicInteger();
        CompanionNeedsBatchRunner runner = new CompanionNeedsBatchRunner(npcId -> {
            updates.incrementAndGet();
            return CompanionNeedsScheduledUpdate.retryOutcome();
        });

        CompanionNeedsBatchRunner.BatchResult result = runner.run(
                null,
                state,
                0L,
                () -> 0L
        );

        assertEquals(0, result.processed());
        assertFalse(result.hasRemainingDue());
        assertEquals(0, updates.get());
    }
}
