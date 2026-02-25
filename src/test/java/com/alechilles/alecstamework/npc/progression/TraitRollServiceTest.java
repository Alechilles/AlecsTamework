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

    @Test
    void rollTraitsUsesWeightedVariableRollCounts() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                2,
                false,
                4,
                true,
                trait("Trait_Repeatable", "FertilityMultiplier", 1.0, 1.0, 1.0, 1.0)
        );
        TwTraitConfig.SelectionSettings selection =
                (TwTraitConfig.SelectionSettings) readField(config, "selection");
        setField(selection, "rollCountWeights", weights(0.10, 0.20, 0.45, 0.20, 0.05));

        int[] counts = new int[5];
        for (int seed = 1; seed <= 2000; seed++) {
            int rolledCount = TraitRollService.rollTraits(config, seed).length;
            assertTrue(rolledCount >= 0 && rolledCount <= 4);
            counts[rolledCount]++;
        }

        assertTrue(counts[2] > counts[1]);
        assertTrue(counts[2] > counts[3]);
        assertTrue(counts[4] < counts[2]);
    }

    @Test
    void rollTraitsUsesNaturalRangeOnly() throws Exception {
        TwTraitConfig config = createConfig(
                true,
                1,
                true,
                1,
                false,
                traitWithRanges(
                        "Trait_Genetics",
                        "FertilityMultiplier",
                        1.0,
                        0.95,
                        1.05,
                        0.70,
                        1.30,
                        1.0
                )
        );

        for (int seed = 1; seed <= 250; seed++) {
            TameworkTraitsComponent.TraitValue[] rolled = TraitRollService.rollTraits(config, seed);
            assertEquals(1, rolled.length);
            assertTrue(rolled[0].getValue() >= 0.95 && rolled[0].getValue() <= 1.05);
        }
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
        setField(selection, "rollCountWeights", weightsForFixedRollCount(rollsPerSpawn));
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

    private TwTraitConfig.RollCountWeights weights(double count0,
                                                   double count1,
                                                   double count2,
                                                   double count3,
                                                   double count4) throws Exception {
        TwTraitConfig.RollCountWeights weights = new TwTraitConfig.RollCountWeights();
        setField(weights, "count0", count0);
        setField(weights, "count1", count1);
        setField(weights, "count2", count2);
        setField(weights, "count3", count3);
        setField(weights, "count4", count4);
        return weights;
    }

    private TwTraitConfig.TraitDefinition trait(String id,
                                                String effectKey,
                                                double weight,
                                                double min,
                                                double max,
                                                double defaultValue,
                                                String... conflictsWith) throws Exception {
        return traitWithRanges(id, effectKey, weight, min, max, min, max, defaultValue, conflictsWith);
    }

    private TwTraitConfig.TraitDefinition traitWithRanges(String id,
                                                          String effectKey,
                                                          double weight,
                                                          double naturalMin,
                                                          double naturalMax,
                                                          double breedingMin,
                                                          double breedingMax,
                                                          double defaultValue,
                                                          String... conflictsWith) throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "displayName", id);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", weight);
        setField(definition, "naturalMin", naturalMin);
        setField(definition, "naturalMax", naturalMax);
        setField(definition, "breedingMin", breedingMin);
        setField(definition, "breedingMax", breedingMax);
        setField(definition, "defaultValue", defaultValue);
        setField(definition, "conflictsWith", conflictsWith == null ? new String[0] : conflictsWith);
        return definition;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
