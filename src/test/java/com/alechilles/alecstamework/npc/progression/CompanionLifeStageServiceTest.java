package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import java.lang.reflect.Constructor;
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
    void resolveScaleUsesAdultSwitchWhenNoAdolescentStageExists() {
        TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                CompanionLifeStageService.STAGE_BABY,
                1000L,
                2000L,
                2000L,
                3000L,
                0.80,
                1.00,
                1.00,
                0.80,
                2.00,
                1.20,
                true
        );
        assertEquals(1.40, CompanionLifeStageService.resolveScale(component, 1500L), 0.000001);
        assertEquals(0.80, CompanionLifeStageService.resolveScale(component, 2000L), 0.000001);
    }

    @Test
    void computeLifecycleUsesAdultSwitchForBabyDurationWhenNoAdolescentRoleExists() throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig breedingConfig = constructor.newInstance();
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = breedingConfig.getOffspringLifecycle();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setPrivateField(lifecycle, "enabled", true);
        setPrivateField(lifecycle, "defaultTimeToFullGrownSeconds", 100);
        setPrivateField(lifecycle, "defaultBabyStartScale", 0.80);
        setPrivateField(lifecycle, "defaultAdultStartScale", 0.80);
        setPrivateField(lifecycle, "defaultAdultSwitchScale", 2.00);
        setPrivateField(family, "adultRoleId", "Tamed_Test");
        setPrivateField(family, "babyRoleId", "Tamed_Test_Baby");

        Method computeLifecycle = CompanionLifeStageService.class.getDeclaredMethod(
                "computeLifecycle",
                long.class,
                double.class,
                TwBreedingConfig.class,
                TwBreedingConfig.RoleFamily.class,
                String.class,
                com.hypixel.hytale.component.Store.class
        );
        computeLifecycle.setAccessible(true);

        Object lifecycleResult = computeLifecycle.invoke(null, 1000L, 1.0, breedingConfig, family, "Tamed_Test_Baby", null);
        Method adolescentAtAccessor = lifecycleResult.getClass().getDeclaredMethod("adolescentAtMs");
        Method adultAtAccessor = lifecycleResult.getClass().getDeclaredMethod("adultAtMs");
        Method fullyGrownAccessor = lifecycleResult.getClass().getDeclaredMethod("fullyGrownAtMs");

        long adolescentAtMs = (long) adolescentAtAccessor.invoke(lifecycleResult);
        long adultAtMs = (long) adultAtAccessor.invoke(lifecycleResult);
        long fullyGrownAtMs = (long) fullyGrownAccessor.invoke(lifecycleResult);
        long totalGrowthMs = fullyGrownAtMs - 1000L;
        long expectedBabyDurationMs = Math.max(1L, Math.round(totalGrowthMs * (6.0 / 7.0)));

        assertEquals(adolescentAtMs, adultAtMs);
        assertEquals(expectedBabyDurationMs, adolescentAtMs - 1000L);
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

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
