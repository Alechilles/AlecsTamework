package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class CompanionGenderServiceTest {
    @Test
    void configuredAdultRoleGenderOverridesIncompatibleStoredGender() throws Exception {
        TwBreedingConfig config = configWithGenderedFamily(
                adultRole("Deer_Doe", TwBreedingConfig.Gender.Female),
                adultRole("Deer_Stag", TwBreedingConfig.Gender.Male)
        );
        TameworkLifeStageComponent component = new TameworkLifeStageComponent();
        component.setGender("Male");

        assertEquals("Female", CompanionGenderService.resolveConfiguredOrExistingGender(config, "Deer_Doe", component));
    }

    private static TwBreedingConfig configWithGenderedFamily(TwBreedingConfig.AdultRoleChoice... adultRoles)
            throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        TwBreedingConfig.GenderSettings gender = new TwBreedingConfig.GenderSettings();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(gender, "enabled", true);
        setField(family, "adultRoles", adultRoles);
        setField(config, "gender", gender);
        setField(config.getOffspringLifecycle(), "families", new TwBreedingConfig.RoleFamily[] { family });
        return config;
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId,
                                                             TwBreedingConfig.Gender gender) throws Exception {
        TwBreedingConfig.AdultRoleChoice choice = new TwBreedingConfig.AdultRoleChoice();
        setField(choice, "roleId", roleId);
        setField(choice, "gender", gender);
        return choice;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
