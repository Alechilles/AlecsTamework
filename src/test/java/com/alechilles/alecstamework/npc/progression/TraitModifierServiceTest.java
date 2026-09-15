package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests trait multiplier lookup by effect key. */
class TraitModifierServiceTest {

    @Test
    void resolveMultiplierCombinesMatchingTraitValues() throws Exception {
        TwTraitConfig config = createConfig(
                trait("Trait_Fertility", "FertilityMultiplier"),
                trait("Trait_Fertility_Bonus", "FertilityMultiplier"),
                trait("Trait_Disposition", "HappinessGainMultiplier")
        );
        TameworkTraitsComponent component = new TameworkTraitsComponent(
                "Traits_Test",
                123L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_Fertility", 1.2),
                        new TameworkTraitsComponent.TraitValue("Trait_Fertility_Bonus", 1.1),
                        new TameworkTraitsComponent.TraitValue("Trait_Disposition", 0.8)
                }
        );

        double fertility = TraitModifierService.resolveMultiplier(component, config, "FertilityMultiplier", 1.0);
        double happinessGain = TraitModifierService.resolveMultiplier(component, config, "HappinessGainMultiplier", 1.0);
        double fallback = TraitModifierService.resolveMultiplier(component, config, "UnknownEffect", 1.5);

        assertEquals(1.32, fertility, 0.000001);
        assertEquals(0.8, happinessGain, 0.000001);
        assertEquals(1.5, fallback, 0.000001);
    }

    @Test
    void resolvesSignedMeritForInverseNeedsAndBoundedSizeYield() throws Exception {
        TwTraitConfig.TraitDefinition appetite = trait(
                "Trait_Appetite", "NeedsHungerDecayMultiplier", 0.75, 1.25, 1.0
        );
        TwTraitConfig.TraitDefinition size = trait(
                "Trait_Size", "SizeMultiplier", 0.75, 1.25, 1.0
        );
        TwTraitConfig config = createConfig(appetite, size);
        TameworkTraitsComponent component = new TameworkTraitsComponent(
                "Traits_Test",
                123L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_Appetite", 0.75),
                        new TameworkTraitsComponent.TraitValue("Trait_Size", 1.25)
                }
        );

        assertEquals(1.0, TraitModifierService.resolveSignedMerit(appetite, 0.75), 0.000001);
        assertEquals(0.25, TraitModifierService.resolveSizeMeatHideYieldBonus(component, config), 0.000001);
    }

    private TwTraitConfig createConfig(TwTraitConfig.TraitDefinition... definitions) throws Exception {
        Constructor<TwTraitConfig> ctor = TwTraitConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwTraitConfig config = ctor.newInstance();
        setField(config, "enabled", true);
        setField(config, "traits", definitions);
        return config;
    }

    private TwTraitConfig.TraitDefinition trait(String id, String effectKey) throws Exception {
        return trait(id, effectKey, 1.0, 1.0, 1.0);
    }

    private TwTraitConfig.TraitDefinition trait(String id, String effectKey,
                                                 double breedingMin, double breedingMax, double defaultValue)
            throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "displayName", id);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", 1.0);
        setField(definition, "naturalMin", 1.0);
        setField(definition, "naturalMax", 1.0);
        setField(definition, "breedingMin", breedingMin);
        setField(definition, "breedingMax", breedingMax);
        setField(definition, "defaultValue", defaultValue);
        return definition;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
