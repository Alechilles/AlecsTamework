package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression coverage for offspring role and gender reconciliation. */
class BreedingOffspringRoleResolverTest {
    private final BreedingOffspringRoleResolver resolver = new BreedingOffspringRoleResolver();

    @Test
    void selectedAdultRoleGenderOverridesSampledGender() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("Deer_Stag", TwBreedingConfig.Gender.Male)
        );

        assertEquals(
                TwBreedingConfig.Gender.Male,
                BreedingOffspringRoleResolver.resolveSelectedGender(
                        family,
                        "Deer_Stag",
                        TwBreedingConfig.Gender.Female
                )
        );
    }

    @Test
    void sampledGenderIsKeptWhenAdultRoleHasNoGender() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(adultRole("Deer_Adult", null));

        assertEquals(
                TwBreedingConfig.Gender.Female,
                BreedingOffspringRoleResolver.resolveSelectedGender(
                        family,
                        "Deer_Adult",
                        TwBreedingConfig.Gender.Female
                )
        );
    }

    @Test
    void parentLineModeCanInheritParentALine() throws Exception {
        TwBreedingConfig config = parentLineConfig(0.0, 1.0,
                line("Standard", "Cat_Pet", "Kitten_Pet"),
                line("Longhair", "Cat_Longhair_Pet", "Kitten_Longhair_Pet")
        );

        BreedingOffspringRoleResolver.OffspringRoleSelection selection = resolver.selectOffspringRole(
                "Cat_Pet",
                "Cat_Longhair_Pet",
                config,
                null,
                0.0,
                0.99,
                0.0,
                0.0
        );

        assertNotNull(selection);
        assertEquals("Kitten_Pet", selection.roleId());
        assertEquals("Cat_Pet", selection.adultRoleId());
        assertEquals("Standard", selection.lifecycleFamily().getSelectedLineId());
    }

    @Test
    void parentLineModeCanInheritParentBLine() throws Exception {
        TwBreedingConfig config = parentLineConfig(0.0, 1.0,
                line("Standard", "Cat_Pet", "Kitten_Pet"),
                line("Longhair", "Cat_Longhair_Pet", "Kitten_Longhair_Pet")
        );

        BreedingOffspringRoleResolver.OffspringRoleSelection selection = resolver.selectOffspringRole(
                "Cat_Pet",
                "Cat_Longhair_Pet",
                config,
                null,
                0.75,
                0.99,
                0.0,
                0.0
        );

        assertNotNull(selection);
        assertEquals("Kitten_Longhair_Pet", selection.roleId());
        assertEquals("Cat_Longhair_Pet", selection.adultRoleId());
        assertEquals("Longhair", selection.lifecycleFamily().getSelectedLineId());
    }

    @Test
    void parentLineMutationPrefersNonParentLineWhenAvailable() throws Exception {
        TwBreedingConfig config = parentLineConfig(1.0, 1.0,
                line("Standard", "Cat_Pet", "Kitten_Pet"),
                line("Shorthair", "Cat_Shorthair_Pet", "Kitten_Shorthair_Pet"),
                line("Longhair", "Cat_Longhair_Pet", "Kitten_Longhair_Pet")
        );

        BreedingOffspringRoleResolver.OffspringRoleSelection selection = resolver.selectOffspringRole(
                "Cat_Pet",
                "Cat_Shorthair_Pet",
                config,
                null,
                0.0,
                0.0,
                0.0,
                0.0
        );

        assertNotNull(selection);
        assertEquals("Kitten_Longhair_Pet", selection.roleId());
        assertEquals("Cat_Longhair_Pet", selection.adultRoleId());
        assertEquals("Longhair", selection.lifecycleFamily().getSelectedLineId());
    }

    private static TwBreedingConfig.RoleFamily weightedFamily(TwBreedingConfig.AdultRoleChoice... choices)
            throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoles", choices);
        return family;
    }

    private static TwBreedingConfig parentLineConfig(double mutationChance,
                                                     double parentWeight,
                                                     TwBreedingConfig.RoleLine... lines) throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = config.getOffspringLifecycle();
        TwBreedingConfig.RoleInheritanceSettings roleInheritance = new TwBreedingConfig.RoleInheritanceSettings();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(roleInheritance, "mode", TwBreedingConfig.RoleInheritanceMode.PARENT_LINE);
        setField(roleInheritance, "parentWeight", parentWeight);
        setField(roleInheritance, "mutationChance", mutationChance);
        setField(family, "id", "Cat");
        setField(family, "lines", lines);
        setField(lifecycle, "roleInheritance", roleInheritance);
        setField(lifecycle, "families", new TwBreedingConfig.RoleFamily[] { family });
        setField(config, "offspringLifecycle", lifecycle);
        return config;
    }

    private static TwBreedingConfig.RoleLine line(String id, String adultRoleId, String babyRoleId) throws Exception {
        TwBreedingConfig.RoleLine line = new TwBreedingConfig.RoleLine();
        setField(line, "id", id);
        setField(line, "adultRoleId", adultRoleId);
        setField(line, "babyRoleId", babyRoleId);
        return line;
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
