package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    @Test
    void inferStageFromFamilyRoleTreatsConfiguredBabyAsBaby() throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setPrivateField(family, "adultRoleId", "Tamed_Cow");
        setPrivateField(family, "babyRoleId", "Tamed_Cow_Calf");

        Method inferStage = CompanionLifeStageService.class.getDeclaredMethod(
                "inferStageFromFamilyRole",
                String.class,
                TwBreedingConfig.RoleFamily.class
        );
        inferStage.setAccessible(true);

        Object stage = inferStage.invoke(null, "Tamed_Cow_Calf", family);
        assertEquals(CompanionLifeStageService.STAGE_BABY, stage);
    }

    @Test
    void inferStageFromFamilyRoleReturnsNullWhenRoleNotInFamily() throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setPrivateField(family, "adultRoleId", "Tamed_Cow");
        setPrivateField(family, "babyRoleId", "Tamed_Cow_Calf");

        Method inferStage = CompanionLifeStageService.class.getDeclaredMethod(
                "inferStageFromFamilyRole",
                String.class,
                TwBreedingConfig.RoleFamily.class
        );
        inferStage.setAccessible(true);

        Object stage = inferStage.invoke(null, "Tamed_Piglet", family);
        assertNull(stage);
    }

    @Test
    void isUninitializedAdultComponentDetectsDefaultAdultShell() throws Exception {
        TameworkLifeStageComponent uninitialized = new TameworkLifeStageComponent(
                CompanionLifeStageService.STAGE_ADULT,
                0L,
                0L,
                0L,
                0L,
                0.55,
                0.80,
                0.90,
                0.80,
                1.00,
                1.00,
                false
        );
        TameworkLifeStageComponent initialized = new TameworkLifeStageComponent(
                CompanionLifeStageService.STAGE_BABY,
                10L,
                20L,
                30L,
                40L,
                0.55,
                0.80,
                0.90,
                0.80,
                1.00,
                1.00,
                true
        );

        Method detector = CompanionLifeStageService.class.getDeclaredMethod(
                "isUninitializedAdultComponent",
                TameworkLifeStageComponent.class
        );
        detector.setAccessible(true);

        assertTrue((boolean) detector.invoke(null, uninitialized));
        assertFalse((boolean) detector.invoke(null, initialized));
    }

    private static void setPrivateField(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
