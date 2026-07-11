package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
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

    @Test
    void capturedGenderFallsBackToResolvedGenderWhenLifeStageGenderMissing() {
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();

        String capturedGender = SpawnerNpcProgressionMetadataService.resolveCapturedGenderForMetadata(
                component,
                "female"
        );

        assertEquals("Female", capturedGender);
    }

    @Test
    void capturedGenderPrefersExistingLifeStageGenderOverResolvedFallback() {
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();
        component.setGender("Male");

        String capturedGender = SpawnerNpcProgressionMetadataService.resolveCapturedGenderForMetadata(
                component,
                "Female"
        );

        assertEquals("Male", capturedGender);
    }

    @Test
    void restoredNegativeCooldownReconstructsNegativeStart() {
        BreedingTimeService.CooldownTiming timing =
                SpawnerBreedingStateRestoreService.resolveRestoredCooldownTiming(
                        -1_000L,
                        null,
                        -3_000L
                );

        assertEquals(-1_000L, timing.deadlineMs());
        assertEquals(-3_000L, timing.startedAtMs());
        assertEquals(2_000L, timing.durationMs());
    }

    @Test
    void metadataRoundTripKeepsExistingNegativeCooldownWindow() {
        TameworkBreedingComponent existing = new TameworkBreedingComponent(
                "TestConfig",
                50.0,
                -5_000L,
                false,
                true,
                -1_000L,
                null,
                -4_000L,
                3_000L
        );

        BreedingTimeService.CooldownTiming timing =
                SpawnerBreedingStateRestoreService.resolveRestoredCooldownTiming(
                        -1_000L,
                        existing,
                        -2_000L
                );

        assertEquals(-1_000L, timing.deadlineMs());
        assertEquals(-4_000L, timing.startedAtMs());
        assertEquals(3_000L, timing.durationMs());
    }

    @Test
    void zeroCooldownMetadataClearsExistingWindow() {
        TameworkBreedingComponent existing = new TameworkBreedingComponent(
                "TestConfig",
                50.0,
                -5_000L,
                false,
                true,
                -1_000L,
                null,
                -4_000L,
                3_000L
        );

        BreedingTimeService.CooldownTiming timing =
                SpawnerBreedingStateRestoreService.resolveRestoredCooldownTiming(
                        0L,
                        existing,
                        -2_000L
                );

        assertEquals(0L, timing.deadlineMs());
        assertEquals(0L, timing.startedAtMs());
        assertEquals(0L, timing.durationMs());
    }
}
