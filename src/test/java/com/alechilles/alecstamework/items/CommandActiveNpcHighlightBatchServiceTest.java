package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for bounded highlight work on large command rosters. */
class CommandActiveNpcHighlightBatchServiceTest {
    @Test
    void largeRosterIsSplitAcrossConsecutiveRefreshes() {
        CommandActiveNpcHighlightBatchService<String> service =
                new CommandActiveNpcHighlightBatchService<>(2);
        Object store = new Object();
        UUID playerUuid = UUID.randomUUID();
        List<String> targets = List.of("cow-a", "cow-b", "cow-c", "cow-d", "cow-e");

        List<String> first = service.select(
                store, playerUuid, "flute-a", 0L, 800L, () -> targets
        );
        List<String> second = service.select(
                store, playerUuid, "flute-a", 100L, 800L, () -> targets
        );
        List<String> third = service.select(
                store, playerUuid, "flute-a", 200L, 800L, () -> targets
        );

        assertEquals(List.of("cow-a", "cow-b"), first);
        assertEquals(List.of("cow-c", "cow-d"), second);
        assertEquals(List.of("cow-e"), third);
    }

    @Test
    void changingToolsStartsAtTheBeginningOfTheNewRoster() {
        CommandActiveNpcHighlightBatchService<String> service =
                new CommandActiveNpcHighlightBatchService<>(1);
        Object store = new Object();
        UUID playerUuid = UUID.randomUUID();
        List<String> targets = new ArrayList<>(List.of("cow-a", "cow-b"));

        service.select(store, playerUuid, "flute-a", 0L, 800L, () -> targets);

        assertEquals(
                List.of("cow-a"),
                service.select(store, playerUuid, "flute-b", 100L, 800L, () -> targets)
        );
    }

    @Test
    void supportedLargeRosterCompletesBeforeParticlesExpire() {
        CommandActiveNpcHighlightBatchService<Integer> service =
                new CommandActiveNpcHighlightBatchService<>(
                        CommandActiveNpcHighlightSystem.MAX_TARGETS_PER_PLAYER_SWEEP
                );
        Object store = new Object();
        UUID playerUuid = UUID.randomUUID();
        List<Integer> targets = java.util.stream.IntStream.range(0, 650).boxed().toList();
        ArrayList<Integer> emitted = new ArrayList<>();

        for (long nowMs = 0L; nowMs < 800L; nowMs += 100L) {
            emitted.addAll(service.select(
                    store, playerUuid, "flute-a", nowMs, 800L, () -> targets
            ));
        }

        assertEquals(650, emitted.stream().distinct().count());
        assertTrue(emitted.size() <= 650);
    }
}
