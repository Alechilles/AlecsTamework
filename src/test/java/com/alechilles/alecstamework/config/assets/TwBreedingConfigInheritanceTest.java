package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for TwBreedingConfig inheritance policy decisions. */
class TwBreedingConfigInheritanceTest {

    @Test
    void roleOverridesAreNotInheritedWhenChildOmitsSection() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.RoleOverrideSettings roleOverrideSettings = new TwBreedingConfig.RoleOverrideSettings();
        Map<String, TwBreedingConfig.RoleOverrideSettings> parentOverrides = new HashMap<>();
        parentOverrides.put("Tamed_Rat", roleOverrideSettings);

        setField(parent, "roleOverrides", parentOverrides);
        setField(child, "roleOverrides", new HashMap<String, TwBreedingConfig.RoleOverrideSettings>());

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertTrue(child.getRoleOverrides().isEmpty());
    }

    @Test
    void roleIdsContinueToInheritWhenOmitted() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        setField(parent, "roleIds", new String[] { "Tamed_Bear" });

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertArrayEquals(new String[] { "Tamed_Bear" }, child.getRoleIds());
    }

    @Test
    void cooldownMinutesAliasCountsAsExplicitNestedOverride() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.CooldownSettings parentCooldowns = new TwBreedingConfig.CooldownSettings();
        TwBreedingConfig.CooldownSettings childCooldowns = new TwBreedingConfig.CooldownSettings();
        setField(parentCooldowns, "baseCooldownSeconds", 600);
        setField(childCooldowns, "baseCooldownSeconds", 120);
        setField(parent, "cooldowns", parentCooldowns);
        setField(child, "cooldowns", childCooldowns);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Cooldowns", Set.of("BaseCooldownMinutes"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Cooldowns"), nested);

        assertEquals(120, child.getCooldowns().getBaseCooldownSeconds());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
