package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression: replayed offspring must not reinterpret their persisted lifecycle selection. */
class CompanionLifeStageStrictSelectionTest {
    @Test
    void plannedSelectionOnlyDoesNotSubstituteTheCurrentRoleFamily() throws Exception {
        TwBreedingConfig config = configWithFamily("current-family", "child-role");

        TwBreedingConfig.RoleFamily selected =
                CompanionLifeStageService.resolveLifecycleFamilyForInitialization(
                        config,
                        null,
                        "child-role",
                        CompanionLifeStageService.LifecycleFamilyResolution.PLANNED_SELECTION_ONLY
                );

        assertNull(selected);
    }

    @Test
    void compatibilityModeStillAllowsCurrentConfigFamilyResolution() throws Exception {
        TwBreedingConfig config = configWithFamily("current-family", "child-role");
        TwBreedingConfig.RoleFamily expected = config.resolveLifecycleFamilyForRole("child-role");

        TwBreedingConfig.RoleFamily selected =
                CompanionLifeStageService.resolveLifecycleFamilyForInitialization(
                        config,
                        null,
                        "child-role",
                        CompanionLifeStageService.LifecycleFamilyResolution.CURRENT_CONFIG_FALLBACK
                );

        assertSame(expected, selected);
    }

    @Test
    void selectedAdultRoleSurvivesAnUnresolvedPlannedFamily() {
        String adultRole = CompanionLifeStageService.resolveOffspringAdultRoleId(
                "child-role", "persisted-adult-role", null
        );

        assertEquals("persisted-adult-role", adultRole);
    }

    @Test
    void offspringTimelineSaturatesInsteadOfWrappingAtLongMax() {
        CompanionOffspringLifecycleComputation.Result result =
                CompanionOffspringLifecycleComputation.compute(
                        Long.MAX_VALUE - 1L, 1.0, null, null, "child-role", null
                );

        assertEquals(Long.MAX_VALUE, result.adolescentAtMs());
        assertEquals(Long.MAX_VALUE, result.adultAtMs());
        assertEquals(Long.MAX_VALUE, result.fullyGrownAtMs());
    }

    private static TwBreedingConfig configWithFamily(String familyId,
                                                      String childRoleId) throws Exception {
        Constructor<TwBreedingConfig> constructor =
                TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "id", familyId);
        setField(family, "babyRoleId", childRoleId);
        setField(family, "adultRoleId", "current-adult-role");
        setField(config.getOffspringLifecycle(), "families", new TwBreedingConfig.RoleFamily[] {
                family
        });
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
