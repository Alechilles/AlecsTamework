package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A fill objective must tick for a player adding food, never for an animal eating or a trough loading. */
class FeedTroughFillersTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void foodAddedWithTheTroughOpenCountsForItsViewer() {
        assertEquals(Set.of(PLAYER), FeedTroughFoodStateSyncSystem.fillers(0L, 20L, Set.of(PLAYER)));
    }

    @Test
    void foodTakenOutOrEatenCountsForNobody() {
        assertEquals(Set.of(), FeedTroughFoodStateSyncSystem.fillers(20L, 19L, Set.of(PLAYER)));
    }

    @Test
    void foodAlreadyThereWhenTheTroughLoadsCountsForNobody() {
        assertEquals(Set.of(), FeedTroughFoodStateSyncSystem.fillers(null, 20L, Set.of(PLAYER)));
    }

    @Test
    void foodAddedWithNoWindowOpenCountsForNobody() {
        assertEquals(Set.of(), FeedTroughFoodStateSyncSystem.fillers(0L, 20L, Set.of()));
    }
}
