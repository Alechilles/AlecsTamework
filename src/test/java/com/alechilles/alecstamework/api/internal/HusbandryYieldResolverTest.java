package com.alechilles.alecstamework.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
