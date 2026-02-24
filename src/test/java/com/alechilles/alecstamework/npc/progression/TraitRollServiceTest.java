package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests deterministic rolling and conflict handling for trait assignment. */
class TraitRollServiceTest {

    @Test
    void rollTraitsIsDeterministicForSeededConfigs() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                3,
                true,
                2,
                false,
                trait("Trait_A", "FertilityMultiplier", 1.0, 0.8, 1.2, 1.0),
                trait("Trait_B", "HappinessGainMultiplier", 1.0, 0.7, 1.3, 1.0),
                trait("Trait_C", "BreedCooldownMultiplier", 1.0, 0.9, 1.1, 1.0)
        );

        TameworkTraitsComponent.TraitValue[] first = TraitRollService.rollTraits(config, 1337L);
        TameworkTraitsComponent.TraitValue[] second = TraitRollService.rollTraits(config, 1337L);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.length, second.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i].getId(), second[i].getId());
            assertEquals(first[i].getValue(), second[i].getValue(), 0.000001);
        }
    }

    @Test
    void rollTraitsEnforcesDuplicateAndConflictRules() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                4,
                true,
                4,
                false,
                trait("Trait_Aggressive", "FertilityMultiplier", 1.0, 0.9, 1.1, 1.0, "Trait_Shy"),
                trait("Trait_Shy", "HappinessGainMultiplier", 1.0, 0.9, 1.1, 1.0, "Trait_Aggressive"),
                trait("Trait_Calm", "HappinessDecayMultiplier", 1.0, 0.8, 1.2, 1.0),
                trait("Trait_Healthy", "MaxHealthMultiplier", 1.0, 0.85, 1.25, 1.0)
        );

        for (int seed = 1; seed <= 20; seed++) {
            TameworkTraitsComponent.TraitValue[] rolled = TraitRollService.rollTraits(config, seed);
            Set<String> ids = new HashSet<>();
            boolean hasAggressive = false;
            boolean hasShy = false;
            for (TameworkTraitsComponent.TraitValue value : rolled) {
                assertNotNull(value);
                assertNotNull(value.getId());
                assertFalse(value.getId().isBlank());
                assertTrue(ids.add(value.getId().toLowerCase()));
                if ("Trait_Aggressive".equalsIgnoreCase(value.getId())) {
                    hasAggressive = true;
                    assertTrue(value.getValue() >= 0.9 && value.getValue() <= 1.1);
                }
                if ("Trait_Shy".equalsIgnoreCase(value.getId())) {
                    hasShy = true;
                    assertTrue(value.getValue() >= 0.9 && value.getValue() <= 1.1);
                }
            }
            assertFalse(hasAggressive && hasShy);
            assertTrue(rolled.length <= 4);
        }
    }

    @Test
    void rollTraitsCanRepeatWhenDuplicatesAreAllowed() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                3,
                false,
                3,
                true,
                trait("Trait_Repeatable", "FertilityMultiplier", 1.0, 1.0, 1.0, 1.0)
        );

        TameworkTraitsComponent.TraitValue[] rolled = TraitRollService.rollTraits(config, 42L);
        assertEquals(3, rolled.length);
        assertEquals("Trait_Repeatable", rolled[0].getId());
        assertEquals("Trait_Repeatable", rolled[1].getId());
        assertEquals("Trait_Repeatable", rolled[2].getId());
    }

    private TwTraitConfig createConfig(boolean seeded,
                                       int rollsPerSpawn,
                                       boolean rerollDuplicates,
                                       int maxTraits,
                                       boolean allowDuplicates,
                                       TwTraitConfig.TraitDefinition... traits) throws Exception {
        Constructor<TwTraitConfig> ctor = TwTraitConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwTraitConfig config = ctor.newInstance();
        setField(config, "enabled", true);
        TwTraitConfig.SelectionSettings selection = new TwTraitConfig.SelectionSettings();
        setField(selection, "rollsPerSpawn", rollsPerSpawn);
        setField(selection, "rerollDuplicates", rerollDuplicates);
        setField(selection, "useSeededRandom", seeded);
        setField(config, "selection", selection);
        TwTraitConfig.StackingSettings stacking = new TwTraitConfig.StackingSettings();
        setField(stacking, "maxTraitsPerNpc", maxTraits);
        setField(stacking, "allowDuplicateTraits", allowDuplicates);
        setField(config, "stacking", stacking);
        setField(config, "traits", traits);
        return config;
    }

    private TwTraitConfig.TraitDefinition trait(String id,
                                                String effectKey,
                                                double weight,
                                                double min,
                                                double max,
                                                double defaultValue,
                                                String... conflictsWith) throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "displayName", id);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", weight);
        setField(definition, "min", min);
        setField(definition, "max", max);
        setField(definition, "defaultValue", defaultValue);
        setField(definition, "conflictsWith", conflictsWith == null ? new String[0] : conflictsWith);
        return definition;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
