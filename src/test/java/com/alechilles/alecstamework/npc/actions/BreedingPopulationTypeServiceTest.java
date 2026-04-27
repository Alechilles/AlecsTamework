package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for breeding population type keys. */
class BreedingPopulationTypeServiceTest {
    private final BreedingPopulationTypeService service = new BreedingPopulationTypeService();

    @Test
    void weightedAdultRolesSharePrimaryFamilyPopulationKey() throws Exception {
        TwBreedingConfig config = configWithFamily(
                adultRole("Deer_Stag", 1.0),
                adultRole("Deer_Doe", 1.0)
        );

        assertEquals("deer_stag", service.resolveTypeKey("Deer_Stag", config));
        assertEquals("deer_stag", service.resolveTypeKey("Deer_Doe", config));
        assertEquals("deer_stag", service.resolveTypeKey("Deer_Fawn", config));
    }

    private static TwBreedingConfig configWithFamily(TwBreedingConfig.AdultRoleChoice... choices) throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = config.getOffspringLifecycle();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoles", choices);
        setField(family, "babyRoleId", "Deer_Fawn");
        setField(lifecycle, "families", new TwBreedingConfig.RoleFamily[] { family });
        setField(config, "offspringLifecycle", lifecycle);
        return config;
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId, double weight) throws Exception {
        TwBreedingConfig.AdultRoleChoice choice = new TwBreedingConfig.AdultRoleChoice();
        setField(choice, "roleId", roleId);
        setField(choice, "weight", weight);
        return choice;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
