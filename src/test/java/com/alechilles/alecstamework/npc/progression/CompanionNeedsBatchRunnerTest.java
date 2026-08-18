package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
            UUID npcId = new UUID(0L, i + 1L);
            state.register(npcId, -2_000L);
            state.schedule().reschedule(npcId, -130L + i);
        }
        AtomicInteger updates = new AtomicInteger();
        List<UUID> processedOrder = new ArrayList<>();
        CompanionNeedsBatchRunner runner = new CompanionNeedsBatchRunner(npcId -> {
            updates.incrementAndGet();
            processedOrder.add(npcId);
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
        assertEquals(
                processedOrder,
                processedOrder.stream().sorted().toList()
        );
    }

    @Test
    void firstDueUpdateRunsAtBudgetBoundaryAndPolledSpilloverKeepsItsDueTime() {
        CompanionNeedsRuntimeRegistry.WorldState state =
                CompanionNeedsRuntimeRegistry.newStateForTests();
        UUID firstId = new UUID(0L, 1L);
        UUID secondId = new UUID(0L, 2L);
        UUID thirdId = new UUID(0L, 3L);
        state.register(firstId, -2_000L);
        state.register(secondId, -2_000L);
        state.register(thirdId, -2_000L);
        state.schedule().reschedule(firstId, -3L);
        state.schedule().reschedule(secondId, -2L);
        state.schedule().reschedule(thirdId, -1L);
        List<UUID> processedOrder = new ArrayList<>();
        CompanionNeedsBatchRunner runner = new CompanionNeedsBatchRunner(npcId -> {
            processedOrder.add(npcId);
            return CompanionNeedsScheduledUpdate.outcomeForRatiosForTests(
                    0.9,
                    0.9,
                    false,
                    false,
                    2_000L
            );
        });
        AtomicInteger clockReads = new AtomicInteger();

        CompanionNeedsBatchRunner.BatchResult first = runner.run(
                null,
                state,
                0L,
                () -> clockReads.getAndIncrement() == 0
                        ? 0L
                        : CompanionNeedsBatchRunner.MAX_BATCH_NANOS
        );
        CompanionNeedsBatchRunner.BatchResult second = runner.run(
                null,
                state,
                0L,
                () -> 0L
        );

        assertEquals(1, first.processed());
        assertTrue(first.hasRemainingDue());
        assertEquals(2, second.processed());
        assertFalse(second.hasRemainingDue());
        assertEquals(List.of(firstId, secondId, thirdId), processedOrder);
    }

    @Test
    void invalidUpdateRemovesUuidInsteadOfRequeueingIt() {
        CompanionNeedsRuntimeRegistry.WorldState state =
                CompanionNeedsRuntimeRegistry.newStateForTests();
        UUID npcId = new UUID(0L, 99L);
        state.register(npcId, -2_000L);
        state.schedule().reschedule(npcId, 0L);
        state.setSuppressionActive(npcId, true);
        CompanionNeedsBatchRunner runner = new CompanionNeedsBatchRunner(ignored -> null);

        CompanionNeedsBatchRunner.BatchResult result = runner.run(
                null,
                state,
                0L,
                () -> 0L
        );

        assertEquals(0, result.processed());
        assertFalse(result.hasRemainingDue());
        assertFalse(state.membership().contains(npcId));
        assertFalse(state.suppressionIds().contains(npcId));
        assertFalse(state.hasDue(0L));
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
