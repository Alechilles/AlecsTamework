package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.AnimalAgingSettings;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class CompanionLifeStageDebugMutationTest {
    private static final long MINUTE_MS = 60_000L;

    @Test
    void babyMutationRebasesGrowthTimelineAtNegativeWorldTime() {
        long nowMs = -5_000L;
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();
        CompanionOffspringLifecycleComputation.Result lifecycle = lifecycle(
                5_000L, 15_000L, 25_000L
        );

        boolean applied = CompanionLifeStageService.applyDebugStageState(
                component,
                "Baby",
                nowMs,
                lifecycle,
                null
        );

        assertTrue(applied);
        assertEquals("Baby", CompanionLifeStageService.resolveStageId(component, nowMs));
        assertEquals(nowMs, component.getBornAtMs());
        assertEquals(5_000L, component.getAdolescentAtMs());
        assertEquals(15_000L, component.getAdultAtMs());
        assertEquals(25_000L, component.getFullyGrownAtMs());
        assertTrue(component.isGrowthScalingEnabled());
        assertFalse(component.isAgingInitialized());
        assertEquals(0.0, component.getAgeProgressMs());
    }

    @Test
    void adolescentMutationStartsAtAdolescentBoundary() {
        long nowMs = 50_000L;
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();
        CompanionOffspringLifecycleComputation.Result lifecycle = lifecycle(
                60_000L, 80_000L, 100_000L
        );

        boolean applied = CompanionLifeStageService.applyDebugStageState(
                component,
                "Adolescent",
                nowMs,
                lifecycle,
                null
        );

        assertTrue(applied);
        assertEquals("Adolescent", CompanionLifeStageService.resolveStageId(component, nowMs));
        assertEquals(50_000L, component.getAdolescentAtMs());
        assertEquals(70_000L, component.getAdultAtMs());
        assertEquals(90_000L, component.getFullyGrownAtMs());
    }

    @Test
    void adultAgingMutationUsesConfiguredPrimeAndSeniorBoundaries() {
        AnimalAgingSettings settings = AnimalAgingSettings.CODEC.decode(BsonDocument.parse("""
                {
                  "Enabled": true,
                  "AdultToPrimeMinutes": 2,
                  "PrimeMinutes": 3,
                  "SeniorMinutes": 4
                }
                """))
                .withRuntimePolicy(AnimalAgingSettings.LifecycleMode.FULL, false);
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();
        component.setGrowthScalingEnabled(true);

        assertTrue(CompanionLifeStageService.applyDebugStageState(
                component, "Prime", 10L, null, settings));
        assertEquals(2 * MINUTE_MS, component.getAgeProgressMs());
        assertEquals(AnimalAgingPolicy.Stage.PRIME,
                AnimalAgingPolicy.resolveStage(settings, component.getAgeProgressMs()));
        assertFalse(component.isGrowthScalingEnabled());

        assertTrue(CompanionLifeStageService.applyDebugStageState(
                component, "Senior", 20L, null, settings));
        assertEquals(5 * MINUTE_MS, component.getAgeProgressMs());
        assertEquals(AnimalAgingPolicy.Stage.SENIOR,
                AnimalAgingPolicy.resolveStage(settings, component.getAgeProgressMs()));
    }

    @Test
    void seniorMutationRejectsFreezeAtPrimePolicyWithoutChangingState() {
        AnimalAgingSettings settings = AnimalAgingSettings.CODEC.decode(BsonDocument.parse("""
                { "Enabled": true }
                """))
                .withRuntimePolicy(AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME, false);
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();
        component.setAgeProgressMs(123.0);

        assertFalse(CompanionLifeStageService.applyDebugStageState(
                component, "Senior", 20L, null, settings));
        assertEquals(123.0, component.getAgeProgressMs());
        assertEquals("Adult", component.getStage());
    }

    private static CompanionOffspringLifecycleComputation.Result lifecycle(
            long adolescentAtMs,
            long adultAtMs,
            long fullyGrownAtMs) {
        return new CompanionOffspringLifecycleComputation.Result(
                0.4,
                0.7,
                0.7,
                0.7,
                1.0,
                1.0,
                adolescentAtMs,
                adultAtMs,
                fullyGrownAtMs
        );
    }
}
