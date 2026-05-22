package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.TraitEffectContext;
import com.alechilles.alecstamework.api.TraitEffectContribution;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraitEffectRegistryTest {
    @Test
    void registersListsReplacesClosesAndRejectsBlankKeys() throws Exception {
        TraitEffectRegistry registry = new TraitEffectRegistry(null, null);
        AutoCloseable first = registry.registerEffectKey("Example.Genetics:ScalePattern", context -> true);

        assertEquals(1, registry.listEffectKeys().size());
        assertTrue(registry.listEffectKeys().contains("example.genetics:scalepattern"));

        AutoCloseable second = registry.registerEffectKey("example.genetics:scalepattern", context -> true);
        assertEquals(1, registry.listEffectKeys().size());

        first.close();
        assertTrue(registry.listEffectKeys().contains("example.genetics:scalepattern"));

        second.close();
        assertFalse(registry.listEffectKeys().contains("example.genetics:scalepattern"));
        assertThrows(IllegalArgumentException.class, () -> registry.registerEffectKey("   ", context -> true));
        assertThrows(NullPointerException.class, () -> registry.registerEffectKey("example.valid", null));
    }

    @Test
    void handlerFailuresAreCaughtAndReturnFalse() {
        TraitEffectRegistry registry = new TraitEffectRegistry(null, null);
        TraitEffectContext context = new TraitEffectContext(
                "throwing.effect",
                1.0,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertFalse(registry.invokeHandler("throwing.effect", ignored -> {
            throw new IllegalStateException("boom");
        }, context));
    }

    @Test
    void resolveEffectCombinesMatchingTraitValuesAndContributorDetails() throws Exception {
        TwTraitConfig config = createConfig(
                trait("Trait_Scale", "Scale Pattern", "Example.Genetics:ScalePattern"),
                trait("Trait_Pattern", "Pattern", "example.genetics:scalepattern"),
                trait("Trait_Other", "Other", "OtherEffect"),
                trait("Trait_Nan", "Bad", "Example.Genetics:ScalePattern"),
                trait("Trait_Infinite", "Bad Infinite", "Example.Genetics:ScalePattern"),
                trait("Trait_Blank", "Blank", " ")
        );
        TameworkTraitsComponent component = new TameworkTraitsComponent(
                "Traits_Test",
                123L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_Scale", 1.2),
                        new TameworkTraitsComponent.TraitValue("trait_pattern", 0.5),
                        new TameworkTraitsComponent.TraitValue("Trait_Other", 3.0),
                        new TameworkTraitsComponent.TraitValue("Trait_Missing", 8.0),
                        new TameworkTraitsComponent.TraitValue("Trait_Nan", Double.NaN),
                        new TameworkTraitsComponent.TraitValue("Trait_Infinite", Double.POSITIVE_INFINITY),
                        new TameworkTraitsComponent.TraitValue("Trait_Blank", 9.0)
                }
        );

        TraitEffectRegistry.ResolvedTraitEffect resolved =
                TraitEffectRegistry.resolveEffect("example.genetics:scalepattern", component, config);
        List<TraitEffectContribution> contributions = resolved.contributions();

        assertEquals(0.6, resolved.value(), 0.000001);
        assertEquals(2, contributions.size());
        assertEquals("Trait_Scale", contributions.get(0).traitId());
        assertEquals("Scale Pattern", contributions.get(0).displayName());
        assertEquals(1.2, contributions.get(0).value(), 0.000001);
        assertEquals("Example.Genetics:ScalePattern", contributions.get(0).effectKey());
        assertEquals("Trait_Pattern", contributions.get(1).traitId());
        assertEquals("Pattern", contributions.get(1).displayName());
        assertEquals(0.5, contributions.get(1).value(), 0.000001);
    }

    @Test
    void resolveEffectReturnsDefaultWhenRegisteredKeyHasNoContributors() throws Exception {
        TwTraitConfig config = createConfig(
                trait("Trait_Other", "Other", "OtherEffect")
        );
        TameworkTraitsComponent component = new TameworkTraitsComponent(
                "Traits_Test",
                123L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Trait_Other", 2.0)
                }
        );

        TraitEffectRegistry.ResolvedTraitEffect resolved =
                TraitEffectRegistry.resolveEffect("example.genetics:scalepattern", component, config);

        assertEquals(1.0, resolved.value(), 0.000001);
        assertTrue(resolved.contributions().isEmpty());
    }

    private TwTraitConfig createConfig(TwTraitConfig.TraitDefinition... definitions) throws Exception {
        Constructor<TwTraitConfig> ctor = TwTraitConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwTraitConfig config = ctor.newInstance();
        setField(config, "id", "Traits_Test");
        setField(config, "enabled", true);
        setField(config, "traits", definitions);
        return config;
    }

    private TwTraitConfig.TraitDefinition trait(String id, String displayName, String effectKey) throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "displayName", displayName);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", 1.0);
        setField(definition, "naturalMin", 1.0);
        setField(definition, "naturalMax", 1.0);
        setField(definition, "breedingMin", 1.0);
        setField(definition, "breedingMax", 1.0);
        setField(definition, "defaultValue", 1.0);
        return definition;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
