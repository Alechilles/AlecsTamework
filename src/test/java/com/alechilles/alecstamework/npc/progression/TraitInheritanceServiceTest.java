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
    void masteryInheritanceBonusStillRespectsTraitWeight() throws Exception {
        // Applying the bonus after the weight would yield 50%, rather than 40%.
        TwTraitConfig config = createConfig(true, 0.6, 0.0, true, 1, 0.0,
                traitWithRanges("Weighted", "FertilityMultiplier", 0.0, 0.0,
                        0.0, 1.0, 0.0, 0.5));
        TameworkTraitsComponent parent = geneticsParent("Weighted", 1.0);
        int inherited = 0;
        for (int i = 0; i < 4096; i++) {
            long seed = i * 0x9E3779B97F4A7C15L;
            double value = TraitInheritanceService.inheritTraits(
                    config, parent, parent, seed, 1.0, 0.2, 0.0)[0].getValue();
            if (value == 1.0) inherited++;
        }
        assertTrue(inherited > 1474 && inherited < 1803,
                "Expected weighted inheritance near 40%, got " + inherited + "/4096");
    }

    @Test
    void harmfulMutationProtectionKeepsBetterMutationWithoutGuaranteeingParentValue() throws Exception {
        for (String preference : new String[] { "HIGHER", "LOWER" }) {
            TwTraitConfig.TraitDefinition definition = TwTraitConfig.CODEC.decode(
                    org.bson.BsonDocument.parse("""
                            {"Traits":[{"Id":"Quality","EffectKey":"FertilityMultiplier",
                            "NaturalMin":0.0,"NaturalMax":1.0,"BreedingMin":0.0,"BreedingMax":1.0,
                            "Default":0.5,"MutationPreference":"%s"}]}
                            """.formatted(preference)), new com.hypixel.hytale.codec.ExtraInfo()).getTraits()[0];
            TwTraitConfig config = createConfig(true, 1.0, 1.0, true, 1, 0.0, definition);
            TameworkTraitsComponent parent = geneticsParent("Quality", 0.5);
            boolean higher = preference.equals("HIGHER");
            boolean improved = false;
            boolean remainsHarmful = false;
            for (int i = 0; i < 256; i++) {
                long seed = i * 0x9E3779B97F4A7C15L;
                double original = TraitInheritanceService.inheritTraits(config, parent, parent, seed)[0].getValue();
                double protectedValue = TraitInheritanceService.inheritTraits(
                        config, parent, parent, seed, 1.0, 0.0, 1.0)[0].getValue();
                boolean harmful = higher ? original < 0.5 : original > 0.5;
                if (!harmful) {
                    assertEquals(original, protectedValue, "Beneficial mutations must not be rerolled");
                } else {
                    assertTrue(higher ? protectedValue >= original : protectedValue <= original);
                    improved |= protectedValue != original;
                    remainsHarmful |= higher ? protectedValue < 0.5 : protectedValue > 0.5;
                }
            }
            assertTrue(improved, "Protection should sometimes improve a harmful mutation");
            assertTrue(remainsHarmful, "Protection must not guarantee the original parent value");
        }
    }

    @Test
    void mutationProtectionIsOptInAndZeroBonusesPreserveSeededOutcomes() throws Exception {
        TwTraitConfig.TraitDefinition definition = trait("Quality", "FertilityMultiplier", 0.0, 1.0, 0.5);
        TwTraitConfig config = createConfig(true, 1.0, 1.0, true, 1, 0.0, definition);
        TameworkTraitsComponent parent = geneticsParent("Quality", 0.5);
        for (long seed = 0; seed < 64; seed++) {
            double legacy = TraitInheritanceService.inheritTraits(config, parent, parent, seed)[0].getValue();
            assertEquals(legacy, TraitInheritanceService.inheritTraits(
                    config, parent, parent, seed, 1.0, 0.0, 1.0)[0].getValue());
            setField(definition, "mutationPreference", "HIGHER");
            assertEquals(legacy, TraitInheritanceService.inheritTraits(
                    config, parent, parent, seed, 1.0, 0.0, 0.0)[0].getValue());
            setField(definition, "mutationPreference", "NONE");
        }
    }

    private TameworkTraitsComponent geneticsParent(String id, double value) {
        return new TameworkTraitsComponent("Traits_Test", 1L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue(id, value)
                });
    }

    @Test
    void inheritTraitsFallsBackToRollWhenInheritanceDisabled() throws Exception {
        TwTraitConfig config = createConfig(
                false,
                0.6,
                0.1,
                true,
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

    @Test
    void inheritTraitsRespectsPerTraitInheritanceWeight() throws Exception {
        TwTraitConfig.TraitDefinition noInheritanceWeightTrait =
                trait("Trait_A", "FertilityMultiplier", 0.8, 1.2, 1.0, 0.0);
        TwTraitConfig noInheritanceWeightConfig = createConfig(
                true,
                1.0,
                0.0,
                true,
                1,
                noInheritanceWeightTrait
        );
        TwTraitConfig.TraitDefinition fullInheritanceWeightTrait =
                trait("Trait_A", "FertilityMultiplier", 0.8, 1.2, 1.0, 1.0);
        TwTraitConfig fullInheritanceWeightConfig = createConfig(
                true,
                1.0,
                0.0,
                true,
                1,
                fullInheritanceWeightTrait
        );
        TameworkTraitsComponent parentA = new TameworkTraitsComponent(
                "Traits_Test",
                40L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 5.0)
                }
        );

        TameworkTraitsComponent.TraitValue[] noWeight =
                TraitInheritanceService.inheritTraits(noInheritanceWeightConfig, parentA, null, 3210L);
        TameworkTraitsComponent.TraitValue[] fullWeight =
                TraitInheritanceService.inheritTraits(fullInheritanceWeightConfig, parentA, null, 3210L);

        assertEquals(1, noWeight.length);
        assertEquals("Trait_A", noWeight[0].getId());
        assertTrue(noWeight[0].getValue() < 1.2);

        assertEquals(1, fullWeight.length);
        assertEquals("Trait_A", fullWeight[0].getId());
        assertEquals(1.2, fullWeight[0].getValue(), 0.000001);
    }

    @Test
    void inheritTraitsAlignedParentsIncreasePositiveRollRange() throws Exception {
        TwTraitConfig.TraitDefinition definition =
                trait("Trait_A", "FertilityMultiplier", 0.8, 1.4, 1.0, 1.0);
        TwTraitConfig noAlignmentInfluence = createConfig(
                true,
                1.0,
                0.0,
                true,
                1,
                0.0,
                definition
        );
        TwTraitConfig withAlignmentInfluence = createConfig(
                true,
                1.0,
                0.0,
                true,
                1,
                1.0,
                definition
        );
        TameworkTraitsComponent parentA = new TameworkTraitsComponent(
                "Traits_Test",
                50L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 1.30)
                }
        );
        TameworkTraitsComponent parentB = new TameworkTraitsComponent(
                "Traits_Test",
                51L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 1.20)
                }
        );

        TameworkTraitsComponent.TraitValue[] withoutInfluence =
                TraitInheritanceService.inheritTraits(noAlignmentInfluence, parentA, parentB, 6543L);
        TameworkTraitsComponent.TraitValue[] withInfluence =
                TraitInheritanceService.inheritTraits(withAlignmentInfluence, parentA, parentB, 6543L);
        double parentAverage = 1.25;

        assertEquals(1, withoutInfluence.length);
        assertEquals(parentAverage, withoutInfluence[0].getValue(), 0.000001);

        assertEquals(1, withInfluence.length);
        assertTrue(withInfluence[0].getValue() > parentAverage);
        assertTrue(withInfluence[0].getValue() <= 1.4);
    }

    @Test
    void inheritTraitsUsesBreedingRangeNotNaturalRange() throws Exception {
        TwTraitConfig.TraitDefinition definition = traitWithRanges(
                "Trait_A",
                "FertilityMultiplier",
                0.95,
                1.05,
                0.70,
                1.30,
                1.0,
                1.0
        );
        TwTraitConfig config = createConfig(
                true,
                1.0,
                0.0,
                true,
                1,
                1.0,
                definition
        );
        TameworkTraitsComponent parentA = new TameworkTraitsComponent(
                "Traits_Test",
                60L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 1.30)
                }
        );
        TameworkTraitsComponent parentB = new TameworkTraitsComponent(
                "Traits_Test",
                61L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_A", 1.30)
                }
        );

        TameworkTraitsComponent.TraitValue[] inherited =
                TraitInheritanceService.inheritTraits(config, parentA, parentB, 7777L);

        assertEquals(1, inherited.length);
        assertTrue(inherited[0].getValue() >= 0.70 && inherited[0].getValue() <= 1.30);
        assertTrue(inherited[0].getValue() > 1.05);
    }

    @Test
    void mutationChanceMultiplierScalesAndClampsBaseChance() {
        assertEquals(0.18, TraitInheritanceService.resolveEffectiveMutationChance(0.10, 1.8), 0.000001);
        assertEquals(1.0, TraitInheritanceService.resolveEffectiveMutationChance(0.75, 2.0), 0.000001);
        assertEquals(0.10, TraitInheritanceService.resolveEffectiveMutationChance(0.10, 0.0), 0.000001);
        assertEquals(0.10, TraitInheritanceService.resolveEffectiveMutationChance(0.10, Double.NaN), 0.000001);
        assertEquals(0.0, TraitInheritanceService.resolveEffectiveMutationChance(-1.0, 1.8), 0.000001);
    }

    private TwTraitConfig createConfig(boolean allowInheritance,
                                       double inheritanceChance,
                                       double mutationChance,
                                       boolean preferParentTraits,
                                       int maxTraits,
                                       TwTraitConfig.TraitDefinition... traits) throws Exception {
        return createConfig(
                allowInheritance,
                inheritanceChance,
                mutationChance,
                preferParentTraits,
                maxTraits,
                0.6,
                traits
        );
    }

    private TwTraitConfig createConfig(boolean allowInheritance,
                                       double inheritanceChance,
                                       double mutationChance,
                                       boolean preferParentTraits,
                                       int maxTraits,
                                       double pairAlignmentRangeInfluence,
                                       TwTraitConfig.TraitDefinition... traits) throws Exception {
        Constructor<TwTraitConfig> ctor = TwTraitConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwTraitConfig config = ctor.newInstance();
        setField(config, "enabled", true);

        TwTraitConfig.SelectionSettings selection = new TwTraitConfig.SelectionSettings();
        setField(selection, "maxTraitsPerNpc", maxTraits);
        setField(selection, "rollCountWeights", weightsForFixedRollCount(maxTraits));
        setField(selection, "allowDuplicateTraits", false);
        setField(selection, "useSeededRandom", true);
        setField(config, "selection", selection);

        TwTraitConfig.InheritanceSettings inheritance = new TwTraitConfig.InheritanceSettings();
        setField(inheritance, "allowInheritance", allowInheritance);
        setField(inheritance, "inheritanceChance", inheritanceChance);
        setField(inheritance, "mutationChance", mutationChance);
        setField(inheritance, "pairAlignmentRangeInfluence", pairAlignmentRangeInfluence);
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
        return trait(id, effectKey, min, max, defaultValue, 1.0);
    }

    private TwTraitConfig.TraitDefinition trait(String id,
                                                String effectKey,
                                                double min,
                                                double max,
                                                double defaultValue,
                                                double inheritanceWeight) throws Exception {
        return traitWithRanges(id, effectKey, min, max, min, max, defaultValue, inheritanceWeight);
    }

    private TwTraitConfig.TraitDefinition traitWithRanges(String id,
                                                          String effectKey,
                                                          double naturalMin,
                                                          double naturalMax,
                                                          double breedingMin,
                                                          double breedingMax,
                                                          double defaultValue,
                                                          double inheritanceWeight) throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "displayName", id);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", 1.0);
        setField(definition, "inheritanceWeight", inheritanceWeight);
        setField(definition, "naturalMin", naturalMin);
        setField(definition, "naturalMax", naturalMax);
        setField(definition, "breedingMin", breedingMin);
        setField(definition, "breedingMax", breedingMax);
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
