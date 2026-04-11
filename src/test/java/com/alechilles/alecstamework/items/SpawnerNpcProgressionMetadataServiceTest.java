package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import org.junit.jupiter.api.Test;

class SpawnerNpcProgressionMetadataServiceTest {

    @Test
    void restoredNeedsComponentResetsTimersFromCapturedState() {
        TameworkNeedsComponent existing = new TameworkNeedsComponent("existing_cfg", 80.0, 70.0, 0.5, 123L, 456L);

        TameworkNeedsComponent restored = SpawnerNpcProgressionMetadataService.buildPausedRestoredNeedsComponent(
                existing,
                "captured_cfg",
                0.0,
                5.0,
                1.25
        );

        assertEquals("captured_cfg", restored.getConfigId());
        assertEquals(0.0, restored.getHunger(), 0.000001);
        assertEquals(5.0, restored.getThirst(), 0.000001);
        assertEquals(1.25, restored.getAppliedHappinessPenalty(), 0.000001);
        assertEquals(0L, restored.getLastUpdateMs());
        assertEquals(0L, restored.getLastPassiveSweepMs());
    }

    @Test
    void restoredNeedsComponentFallsBackToExistingValuesWhenMetadataMissing() {
        TameworkNeedsComponent existing = new TameworkNeedsComponent("existing_cfg", 40.0, 30.0, 0.75, 321L, 654L);

        TameworkNeedsComponent restored = SpawnerNpcProgressionMetadataService.buildPausedRestoredNeedsComponent(
                existing,
                null,
                null,
                null,
                null
        );

        assertEquals("existing_cfg", restored.getConfigId());
        assertEquals(40.0, restored.getHunger(), 0.000001);
        assertEquals(30.0, restored.getThirst(), 0.000001);
        assertEquals(0.75, restored.getAppliedHappinessPenalty(), 0.000001);
        assertEquals(0L, restored.getLastUpdateMs());
        assertEquals(0L, restored.getLastPassiveSweepMs());
    }

    @Test
    void restoredNeedsComponentUsesSafeDefaultsWithoutExistingState() {
        TameworkNeedsComponent restored = SpawnerNpcProgressionMetadataService.buildPausedRestoredNeedsComponent(
                null,
                null,
                null,
                null,
                null
        );

        assertNull(restored.getConfigId());
        assertEquals(0.0, restored.getHunger(), 0.000001);
        assertEquals(0.0, restored.getThirst(), 0.000001);
        assertEquals(0.0, restored.getAppliedHappinessPenalty(), 0.000001);
        assertEquals(0L, restored.getLastUpdateMs());
        assertEquals(0L, restored.getLastPassiveSweepMs());
    }
}
