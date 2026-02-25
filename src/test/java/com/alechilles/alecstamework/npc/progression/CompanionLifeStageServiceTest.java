package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import org.junit.jupiter.api.Test;

class CompanionLifeStageServiceTest {
    @Test
    void resolveStageTransitionsFromTimestamps() {
        TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                CompanionLifeStageService.STAGE_BABY,
                1000L,
                2000L,
                3000L,
                4000L,
                0.55,
                0.80,
                0.90,
                0.70,
                1.10,
                1.00,
                true
        );
        assertEquals(CompanionLifeStageService.STAGE_BABY, CompanionLifeStageService.resolveStageId(component, 1500L));
        assertEquals(CompanionLifeStageService.STAGE_ADOLESCENT, CompanionLifeStageService.resolveStageId(component, 2000L));
        assertEquals(CompanionLifeStageService.STAGE_ADULT, CompanionLifeStageService.resolveStageId(component, 3000L));
    }

    @Test
    void resolveScaleInterpolatesAcrossGrowthWindows() {
        TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                CompanionLifeStageService.STAGE_BABY,
                1000L,
                2000L,
                3000L,
                4000L,
                0.55,
                0.80,
                0.90,
                0.70,
                1.10,
                1.20,
                true
        );
        assertEquals(0.55, CompanionLifeStageService.resolveScale(component, 1000L), 0.000001);
        assertEquals(0.725, CompanionLifeStageService.resolveScale(component, 1500L), 0.000001);
        assertEquals(0.95, CompanionLifeStageService.resolveScale(component, 2500L), 0.000001);
        assertEquals(0.95, CompanionLifeStageService.resolveScale(component, 3500L), 0.000001);
        assertEquals(1.20, CompanionLifeStageService.resolveScale(component, 4500L), 0.000001);
    }

    @Test
    void resolveScaleUsesAdultValueWhenGrowthDisabled() {
        TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                CompanionLifeStageService.STAGE_ADULT,
                1000L,
                2000L,
                3000L,
                4000L,
                0.55,
                0.80,
                0.90,
                0.70,
                1.10,
                1.35,
                false
        );
        assertEquals(1.35, CompanionLifeStageService.resolveScale(component, 1500L), 0.000001);
    }
}
