package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests offspring trait inheritance blending and fallback behavior. */
class TraitInheritanceServiceTest {

    @Test
    void inheritTraitsFallsBackToRollWhenInheritanceDisabled() throws Exception {
        TwTraitConfig config = createConfig(
                false,
                0.6,
                0.1,
                true,
                2,
                2,
                trait("Trait_A", "FertilityMultiplier", 0.8, 1.2, 1.0),
                trait("Trait_B", "HappinessGainMultiplier", 0.8, 1.2, 1.0)
        );
        TameworkTraitsComponent parentA = new TameworkTraitsComponent(
                "Traits_Test",
                10L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 1.2)
                }
        );
        TameworkTraitsComponent parentB = new TameworkTraitsComponent(
                "Traits_Test",
                11L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_B", 0.9)
                }
        );

        TameworkTraitsComponent.TraitValue[] expected = TraitRollService.rollTraits(config, 1234L);
        TameworkTraitsComponent.TraitValue[] actual = TraitInheritanceService.inheritTraits(config, parentA, parentB, 1234L);

        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].getId(), actual[i].getId());
            assertEquals(expected[i].getValue(), actual[i].getValue(), 0.000001);
        }
    }

    @Test
    void inheritTraitsPrefersParentTraitsWhenConfigured() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                1.0,
                0.0,
                true,
                2,
                2,
                trait("Trait_A", "FertilityMultiplier", 0.8, 1.2, 1.0),
                trait("Trait_B", "HappinessGainMultiplier", 0.8, 1.2, 1.0),
                trait("Trait_C", "MaxHealthMultiplier", 0.8, 1.2, 1.0)
        );
        TameworkTraitsComponent parentA = new TameworkTraitsComponent(
                "Traits_Test",
                20L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 1.15)
                }
        );
        TameworkTraitsComponent parentB = new TameworkTraitsComponent(
                "Traits_Test",
                21L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_B", 0.87)
                }
        );

        TameworkTraitsComponent.TraitValue[] actual = TraitInheritanceService.inheritTraits(config, parentA, parentB, 5678L);
        Set<String> ids = new HashSet<>();
        for (TameworkTraitsComponent.TraitValue value : actual) {
            ids.add(value.getId());
        }

        assertTrue(ids.contains("Trait_A"));
        assertTrue(ids.contains("Trait_B"));
    }

    @Test
    void inheritTraitsMutationStaysWithinDefinitionBounds() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                1.0,
                1.0,
                true,
                2,
                2,
                trait("Trait_A", "FertilityMultiplier", 0.9, 1.1, 1.0),
                trait("Trait_B", "HappinessGainMultiplier", 0.8, 1.2, 1.0)
        );
        TameworkTraitsComponent parentA = new TameworkTraitsComponent(
                "Traits_Test",
                30L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 5.0)
                }
        );
        TameworkTraitsComponent parentB = new TameworkTraitsComponent(
                "Traits_Test",
                31L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_B", -3.0)
                }
        );

        TameworkTraitsComponent.TraitValue[] actual = TraitInheritanceService.inheritTraits(config, parentA, parentB, 9012L);
        for (TameworkTraitsComponent.TraitValue value : actual) {
            if ("Trait_A".equals(value.getId())) {
                assertTrue(value.getValue() >= 0.9 && value.getValue() <= 1.1);
            }
            if ("Trait_B".equals(value.getId())) {
                assertTrue(value.getValue() >= 0.8 && value.getValue() <= 1.2);
            }
        }
    }

    private TwTraitConfig createConfig(boolean allowInheritance,
                                       double inheritanceChance,
                                       double mutationChance,
                                       boolean preferParentTraits,
                                       int maxTraits,
                                       int rollsPerSpawn,
                                       TwTraitConfig.TraitDefinition... traits) throws Exception {
        Constructor<TwTraitConfig> ctor = TwTraitConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwTraitConfig config = ctor.newInstance();
        setField(config, "enabled", true);

        TwTraitConfig.SelectionSettings selection = new TwTraitConfig.SelectionSettings();
        setField(selection, "rollsPerSpawn", rollsPerSpawn);
        setField(selection, "rollCountWeights", weightsForFixedRollCount(rollsPerSpawn));
        setField(selection, "rerollDuplicates", true);
        setField(selection, "useSeededRandom", true);
        setField(config, "selection", selection);

        TwTraitConfig.StackingSettings stacking = new TwTraitConfig.StackingSettings();
        setField(stacking, "maxTraitsPerNpc", maxTraits);
        setField(stacking, "allowDuplicateTraits", false);
        setField(config, "stacking", stacking);

        TwTraitConfig.InheritanceSettings inheritance = new TwTraitConfig.InheritanceSettings();
        setField(inheritance, "allowInheritance", allowInheritance);
        setField(inheritance, "inheritanceChance", inheritanceChance);
        setField(inheritance, "mutationChance", mutationChance);
        setField(inheritance, "preferParentTraits", preferParentTraits);
        setField(config, "inheritance", inheritance);

        setField(config, "traits", traits);
        return config;
    }

    private TwTraitConfig.RollCountWeights weightsForFixedRollCount(int rollCount) throws Exception {
        int clamped = Math.max(0, Math.min(4, rollCount));
        TwTraitConfig.RollCountWeights weights = new TwTraitConfig.RollCountWeights();
        setField(weights, "count0", clamped == 0 ? 1.0 : 0.0);
        setField(weights, "count1", clamped == 1 ? 1.0 : 0.0);
        setField(weights, "count2", clamped == 2 ? 1.0 : 0.0);
        setField(weights, "count3", clamped == 3 ? 1.0 : 0.0);
        setField(weights, "count4", clamped == 4 ? 1.0 : 0.0);
        return weights;
    }

    private TwTraitConfig.TraitDefinition trait(String id,
                                                String effectKey,
                                                double min,
                                                double max,
                                                double defaultValue) throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "displayName", id);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", 1.0);
        setField(definition, "min", min);
        setField(definition, "max", max);
        setField(definition, "defaultValue", defaultValue);
        setField(definition, "conflictsWith", new String[0]);
        return definition;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
