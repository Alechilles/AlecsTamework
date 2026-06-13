package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NeedsResourceMovementProgressTrackerTest {
    private static final long START_MS = 1_000L;

    @BeforeEach
    void setUp() {
        NeedsResourceMovementProgressTracker.clearForTests();
    }

    @AfterEach
    void tearDown() {
        NeedsResourceMovementProgressTracker.clearForTests();
    }

    @Test
    void doesNotReportStallBeforeGracePeriod() {
        UUID npc = UUID.randomUUID();
        NeedsResourceMovementProgressTracker.recordStart(
                npc,
                "Water",
                new Vector3d(10.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                START_MS
        );

        assertFalse(NeedsResourceMovementProgressTracker.isStalled(
                npc,
                "Water",
                new Vector3d(0.0, 0.0, 0.0),
                START_MS + 7_999L,
                8.0,
                0.75
        ));
    }

    @Test
    void reportsStallAfterGracePeriodWithoutEnoughProgress() {
        UUID npc = UUID.randomUUID();
        NeedsResourceMovementProgressTracker.recordStart(
                npc,
                "FoodContainer",
                new Vector3d(10.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                START_MS
        );

        assertTrue(NeedsResourceMovementProgressTracker.isStalled(
                npc,
                "FoodContainer",
                new Vector3d(0.1, 0.0, 0.0),
                START_MS + 8_000L,
                8.0,
                0.75
        ));
    }

    @Test
    void enoughProgressPreventsStall() {
        UUID npc = UUID.randomUUID();
        NeedsResourceMovementProgressTracker.recordStart(
                npc,
                "Water",
                new Vector3d(10.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                START_MS
        );

        assertFalse(NeedsResourceMovementProgressTracker.isStalled(
                npc,
                "Water",
                new Vector3d(1.5, 0.0, 0.0),
                START_MS + 8_000L,
                8.0,
                0.75
        ));
    }

    @Test
    void clearRemovesTrackedResourceRecord() {
        UUID npc = UUID.randomUUID();
        NeedsResourceMovementProgressTracker.recordStart(
                npc,
                "Water",
                new Vector3d(10.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                START_MS
        );

        NeedsResourceMovementProgressTracker.clear(npc, "Water");

        assertFalse(NeedsResourceMovementProgressTracker.isStalled(
                npc,
                "Water",
                new Vector3d(0.0, 0.0, 0.0),
                START_MS + 8_000L,
                8.0,
                0.75
        ));
        assertEquals(0, NeedsResourceMovementProgressTracker.trackedNpcCountForTests());
    }
}
