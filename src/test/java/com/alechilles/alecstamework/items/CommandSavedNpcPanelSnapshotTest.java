package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionClock;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionRuntimeClock;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the visible last-known vital values used when an NPC is unavailable. */
class CommandSavedNpcPanelSnapshotTest {
    @Test
    void appliesSavedVitalsToAnOfflineCard() {
        CommandSavedNpcPanelSnapshot saved = saved(new Inputs().health(17.6f, 42.4f));

        LinkedNpcEntry applied = saved.apply(baseCard(), null);
        assertEquals(18, applied.currentHealth());
        assertEquals(42, applied.maxHealth());
    }

    @Test
    void appliesSavedProgressionWithoutEnablingOfflineActions() {
        Inputs inputs = new Inputs();
        inputs.needsConfigId = "care-test";
        inputs.hunger = 33.0;
        inputs.thirst = 11.0;
        inputs.levelingConfigId = "level-test";
        inputs.level = 3;
        inputs.currentXp = 77.0;
        inputs.totalXp = 177.0;
        inputs.traitsConfigId = "traits-test";
        inputs.traits = Map.of("Trait_Test", 1.2);
        inputs.talentsConfigId = "talents-test";
        inputs.talentPointsSpent = 2;
        inputs.breeding = new TameworkBreedingComponent("breed-test", 0.0, 0L, true, true, 0L, null);

        LinkedNpcEntry applied = saved(inputs).apply(baseCard(), null);
        assertTrue(applied.breedingEnabled());
        assertTrue(applied.isTraitsActionVisible());
        assertFalse(applied.isTraitsActionEnabled());
        assertFalse(applied.isTalentsActionVisible());
        assertFalse(applied.isTalentsActionEnabled());
    }

    @Test
    void hidesBreedingPresentationForJuvenileSavedCompanions() {
        TameworkLifeStageComponent juvenile = new TameworkLifeStageComponent();
        juvenile.setStage("Baby");
        Inputs inputs = new Inputs();
        inputs.breeding = new TameworkBreedingComponent("breed-test", 0.0, 0L, true, true, 60_000L, null);
        inputs.lifeStage = juvenile;

        LinkedNpcEntry applied = saved(inputs).apply(baseCard(), null);
        assertFalse(applied.breedingEnabled());
        assertFalse(applied.breedingAvailable());
        assertFalse(applied.breedingCooldownKnown());
        assertEquals(-1.0, applied.breedingHappinessRatio());
    }

    @Test
    void exposesBreedingWhenAStaleSavedJuvenileStageHasReachedAdulthood() {
        TameworkLifeStageComponent staleJuvenile = new TameworkLifeStageComponent();
        staleJuvenile.setStage("Baby");
        staleJuvenile.setGrowthScalingEnabled(true);
        staleJuvenile.setJuvenileClockInitialized(true);
        staleJuvenile.setAdultAtMs(100L);
        staleJuvenile.setLifecycleNowMs(100L);
        Inputs inputs = new Inputs();
        inputs.breeding = new TameworkBreedingComponent("breed-test", 0.0, 0L, true, true, 0L, null);
        inputs.lifeStage = staleJuvenile;

        LinkedNpcEntry applied = saved(inputs).apply(baseCard(), null);
        assertTrue(applied.breedingEnabled());
        assertTrue(applied.breedingAvailable());
        assertTrue(applied.breedingCooldownKnown());
    }

    @Test
    void advancesSavedCooldownsWithSignedWorldTimeAndCustomDayLength() {
        long savedWorldMs = -1_000_000L;
        double gameRate = 3.0;
        long durationMs = 30_000L;
        TameworkLifeStageComponent lifeStage = progressionAt(savedWorldMs);
        long clockAtSnapshot = AnimalProgressionClock.get().current(null);
        lifeStage.setProgressionClockMs(clockAtSnapshot);
        CommandSavedNpcPanelSnapshot saved = savedTimers(lifeStage, savedWorldMs, durationMs);
        CompanionRuntimeClock.advanceByDeltaSeconds(4.0f);

        LinkedNpcEntry applied = saved.apply(baseCard(), null, gameRate);

        assertTrue(applied.breedingCooldownKnown());
        assertTrue(applied.breedingCooldownActive());
        assertEquals(6_000L, applied.breedingCooldownRemainingMs());
        assertEquals(0.4, applied.breedingCooldownRatio());
        assertTrue(applied.harvestCooldownKnown());
        assertTrue(applied.harvestCooldownActive());
        assertEquals(6_000L, applied.harvestCooldownRemainingMs());
        assertEquals(0.4, applied.harvestCooldownRatio());

        LinkedNpcEntry otherWorld = saved.apply(baseCard(), null, Double.NaN);
        assertEquals(-1L, otherWorld.breedingCooldownRemainingMs());
        assertEquals(-1L, otherWorld.harvestCooldownRemainingMs());
    }

    @Test
    void keepsCapturedSavedCooldownsFrozenAtTheirSnapshot() {
        long savedWorldMs = -1_000_000L;
        long durationMs = Math.round(BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(null) * 10_000.0);
        CommandSavedNpcPanelSnapshot saved = savedTimers(progressionAt(savedWorldMs), savedWorldMs, durationMs);
        CompanionRuntimeClock.advanceByDeltaSeconds(4.0f);

        LinkedNpcEntry applied = saved.apply(capturedBaseCard(), null);

        assertTrue(applied.breedingCooldownActive());
        assertEquals(10_000L, applied.breedingCooldownRemainingMs());
        assertEquals(0.0, applied.breedingCooldownRatio());
        assertTrue(applied.harvestCooldownActive());
        assertEquals(10_000L, applied.harvestCooldownRemainingMs());
        assertEquals(0.0, applied.harvestCooldownRatio());
    }

    @Test
    void captureLocationDoesNotRequireAStatsSummary() {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Sheep_Pet", CompanionLocation.item())
                .generation(2).build();

        CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.fromSummary(record);

        assertNotNull(saved);
        assertNotNull(saved.storedLocation().capture());
        LinkedNpcEntry card = baseCard();
        assertEquals(card, saved.apply(card, null));
    }

    private static TameworkLifeStageComponent progressionAt(long worldMs) {
        TameworkLifeStageComponent lifeStage = new TameworkLifeStageComponent();
        lifeStage.setProgressionInitialized(true);
        lifeStage.setLastProgressionWorldMs(worldMs);
        lifeStage.setActiveProgressMs(0L);
        lifeStage.setProgressionClockMs(AnimalProgressionClock.get().current(null));
        lifeStage.setStoredProgressionPaused(false);
        return lifeStage;
    }

    private static CommandSavedNpcPanelSnapshot savedTimers(TameworkLifeStageComponent lifeStage,
                                                            long savedWorldMs, long durationMs) {
        Inputs inputs = new Inputs();
        inputs.breeding = new TameworkBreedingComponent("breed-test", 0.0, 0L, true, true,
                savedWorldMs + durationMs, null, savedWorldMs, durationMs);
        TameworkAlarmComponent alarms = new TameworkAlarmComponent();
        String harvestAlarm = CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName();
        alarms.setAlarm(harvestAlarm, savedWorldMs, durationMs, savedWorldMs + durationMs);
        inputs.harvest = alarms.getAlarm(harvestAlarm);
        inputs.lifeStage = lifeStage;
        return saved(inputs);
    }

    private static CommandSavedNpcPanelSnapshot saved(Inputs inputs) {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Sheep_Pet",
                        CompanionLocation.live("world", 0, 0, 0))
                .summary(CompanionSummaries.build(inputs.build(), 1L)).build();
        CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.fromSummary(record);
        assertNotNull(saved);
        return saved;
    }

    /** The summary values one unloaded companion was saved with; unset fields stay absent. */
    private static final class Inputs {
        float healthCurrent;
        float healthMax;
        String needsConfigId;
        double hunger;
        double thirst;
        TameworkBreedingComponent breeding;
        TameworkAlarmComponent.AlarmEntry harvest;
        String levelingConfigId;
        int level;
        double currentXp;
        double totalXp;
        int talentPointsSpent;
        Map<String, Double> traits = Map.of();
        String traitsConfigId;
        String talentsConfigId;
        TameworkLifeStageComponent lifeStage;

        Inputs health(float current, float max) {
            healthCurrent = current;
            healthMax = max;
            return this;
        }

        CompanionSummaries.Inputs build() {
            return new CompanionSummaries.Inputs(null, null, "Sheep_Pet", null, healthCurrent, healthMax,
                    null, 0.0, needsConfigId, hunger, thirst,
                    breeding != null, breeding != null && breeding.isEnabled(),
                    breeding == null ? 0L : breeding.getCooldownUntilMs(),
                    breeding == null ? 0L : breeding.getCooldownStartedAtMs(),
                    breeding == null ? 0L : breeding.getCooldownDurationMs(),
                    harvest == null ? 0L : harvest.getUntilMs(),
                    levelingConfigId, level, currentXp, totalXp, talentPointsSpent, traits,
                    harvest == null ? 0L : harvest.getStartedAtMs(),
                    harvest == null ? 0L : harvest.getDurationMs(),
                    traitsConfigId, talentsConfigId, CompanionSummaries.progression(lifeStage));
        }
    }

    private static LinkedNpcEntry baseCard() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 1, 1, 0, 0,
                null, 0, 0, 0, 0, false, false, false, false, false, false,
                0L, new com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator[0]);
    }

    private static LinkedNpcEntry capturedBaseCard() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 1, 1, 0, 0,
                null, 0, 0, 0, 0, false, false, false, true, false, false,
                0L, new com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator[0]);
    }
}
