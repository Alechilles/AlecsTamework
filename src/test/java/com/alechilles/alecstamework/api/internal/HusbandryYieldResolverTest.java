package com.alechilles.alecstamework.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Verifies the fleece-only product filter at the resolved-output boundary. */
class HusbandryYieldResolverTest {
    @Test
    void includesPremiumFleeceProductsButExcludesManure() {
        CompanionOutputService.FinalizedOutput output = CompanionOutputService.finalizeExpectedQuantity(
                List.of(
                        new TestItemStack("Ingredient_Fabric_Scrap_Wool", 2),
                        new TestItemStack("Ingredient_Fabric_Scrap_Silk", 2),
                        new TestItemStack("Ingredient_Fabric_Scrap_Cindercloth", 2),
                        new TestItemStack("Ingredient_Fabric_Scrap_Shadoweave", 2),
                        new TestItemStack("Animal_Manure", 3)),
                stack -> HusbandryYieldResolver.isFleeceOrFiberProduct(stack.getItemId()) ? 0.5 : 0.0,
                () -> 0.0);

        assertEquals(Map.of(
                "Ingredient_Fabric_Scrap_Wool", 3,
                "Ingredient_Fabric_Scrap_Silk", 3,
                "Ingredient_Fabric_Scrap_Cindercloth", 3,
                "Ingredient_Fabric_Scrap_Shadoweave", 3,
                "Animal_Manure", 3), output.itemQuantities());
    }

    @Test
    void preservesGatedLegacyProviderExpectation() {
        assertEquals(0.0, HusbandryYieldResolver.expectedLegacyProviderBonusCopies(
                new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 1.0, 1.0)));
        assertEquals(0.75, HusbandryYieldResolver.expectedLegacyProviderBonusCopies(
                new HusbandryOutcomeModifiers(1.0, 1.0, 0.5, 0.5, 1.0)));
    }

    @Test
    void appliesAnimalProductTraitsToRenewableHarvestsButNotCullProducts() throws Exception {
        TwTraitConfig positiveConfig = traitConfig(trait("productivity", "AnimalProductYieldMultiplier"));
        TameworkTraitsComponent positive = traits("productivity", 1.2);
        for (String product : List.of("Ingredient_Fabric_Scrap_Wool", "Ingredient_Egg_Chicken", "Ingredient_Milk_Cow")) {
            assertEquals(0.2, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                    positive, positiveConfig, product), 0.000001, product);
        }
        TameworkTraitsComponent negative = traits("productivity", 0.8);
        assertEquals(-0.2, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                negative, positiveConfig, "Ingredient_Milk_Cow"), 0.000001);
        assertEquals(0.0, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                positive, positiveConfig, "Ingredient_Meat_Mutton"), 0.000001);
    }

    @Test
    void preservesLegacyFleeceTraitsWithoutApplyingThemToEggsOrMilk() throws Exception {
        TwTraitConfig legacyConfig = traitConfig(trait("fleece", "FleeceFiberYieldMultiplier"));
        TameworkTraitsComponent legacy = traits("fleece", 1.5);
        assertEquals(0.5, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                legacy, legacyConfig, "Ingredient_Fabric_Scrap_Wool"), 0.000001);
        assertEquals(0.0, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                legacy, legacyConfig, "Ingredient_Egg_Chicken"), 0.000001);
        assertEquals(0.0, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                legacy, legacyConfig, "Ingredient_Milk_Cow"), 0.000001);
    }

    @Test
    void stacksDistinctGeneralAndLegacyTraitsOnlyOnce() throws Exception {
        TwTraitConfig config = traitConfig(
                trait("productivity", "AnimalProductYieldMultiplier"),
                trait("fleece", "FleeceFiberYieldMultiplier"));
        TameworkTraitsComponent traits = new TameworkTraitsComponent("Traits_Test", 1L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("productivity", 1.2),
                        new TameworkTraitsComponent.TraitValue("fleece", 1.1)
                });
        assertEquals(0.3, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                traits, config, "Ingredient_Fabric_Scrap_Wool"), 0.000001);
        assertEquals(0.2, HusbandryYieldResolver.resolveTraitHarvestYieldBonus(
                traits, config, "Ingredient_Egg_Chicken"), 0.000001);
    }

    @Test
    void treatsAnEmptyCapturedToolAsNoToolForAutomaticHarvests() throws Exception {
        AtomicReference<HusbandryToolContext> observedTool = new AtomicReference<>();
        HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        registry.register(context -> {
            observedTool.set(context.tool());
            return HusbandryOutcomeModifiers.identity();
        });
        HusbandryOutcomeRuntime.install(registry);
        try {
            HusbandryOutcomeRuntime.resolve(
                    HusbandryOutcomeKind.HARVEST_YIELD,
                    null,
                    null,
                    null,
                    null,
                    new HusbandryToolContext(null, 0, 0.0, 0.0, null),
                    UUID.fromString("10000000-0000-0000-0000-000000000001"));
            assertNull(observedTool.get());
        } finally {
            HusbandryOutcomeRuntime.clear(registry);
            registry.close();
        }
    }

    private static TwTraitConfig traitConfig(TwTraitConfig.TraitDefinition... definitions) throws Exception {
        Constructor<TwTraitConfig> ctor = TwTraitConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwTraitConfig config = ctor.newInstance();
        setField(config, "enabled", true);
        setField(config, "traits", definitions);
        return config;
    }

    private static TwTraitConfig.TraitDefinition trait(String id, String effectKey) throws Exception {
        TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
        setField(definition, "id", id);
        setField(definition, "effectKey", effectKey);
        setField(definition, "weight", 1.0);
        setField(definition, "naturalMin", 1.0);
        setField(definition, "naturalMax", 1.0);
        setField(definition, "breedingMin", 0.5);
        setField(definition, "breedingMax", 1.5);
        setField(definition, "defaultValue", 1.0);
        return definition;
    }

    private static TameworkTraitsComponent traits(String id, double value) {
        return new TameworkTraitsComponent("Traits_Test", 1L,
                new TameworkTraitsComponent.TraitValue[] { new TameworkTraitsComponent.TraitValue(id, value) });
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestItemStack extends ItemStack {
        private final String itemId;
        private final int quantity;

        private TestItemStack(String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }

        @Override
        public String getItemId() {
            return itemId;
        }

        @Override
        public int getQuantity() {
            return quantity;
        }

        @Override
        public boolean isEmpty() {
            return quantity <= 0 || itemId == null || itemId.isBlank();
        }

        @Override public double getDurability() { return 0.0; }
        @Override public double getMaxDurability() { return 0.0; }
        @Override public BsonDocument getMetadata() { return null; }
        @Override public ItemStack withQuantity(int nextQuantity) {
            return new TestItemStack(itemId, nextQuantity);
        }

        @Override
        public ItemStack cleanCopy() {
            return new TestItemStack(itemId, quantity);
        }
    }
}
