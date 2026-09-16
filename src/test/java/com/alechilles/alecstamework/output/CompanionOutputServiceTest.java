package com.alechilles.alecstamework.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.alechilles.alecstamework.api.HusbandryOutputConversion;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Behavior checks for the finalized companion-output boundary. */
class CompanionOutputServiceTest {

    @Test
    void bonusCopiesAreIncludedInFinalPublishedQuantities() {
        CompanionOutputService.FinalizedOutput output =
                CompanionOutputService.finalizeDrops(
                        List.of(new TestItemStack(
                                "Ingredient_Fabric_Scrap_Wool", 2)),
                        1
                );

        assertEquals(2, output.itemStacks().size());
        assertEquals(
                Map.of("Ingredient_Fabric_Scrap_Wool", 4),
                output.itemQuantities()
        );
    }

    @Test
    void doubleBonusCopiesApplyToEveryBaseStack() {
        CompanionOutputService.FinalizedOutput output =
                CompanionOutputService.finalizeDrops(
                        List.of(
                                new TestItemStack("Ingredient_Fabric_Scrap_Wool", 2),
                                new TestItemStack("Ingredient_Fabric_Scrap_Cloth", 3)
                        ),
                        2
                );

        assertEquals(6, output.itemStacks().size());
        assertEquals(
                Map.of(
                        "Ingredient_Fabric_Scrap_Wool", 6,
                        "Ingredient_Fabric_Scrap_Cloth", 9
                ),
                output.itemQuantities()
        );
    }

    @Test
    void bonusCopiesAreClampedToTheSupportedRuntimeRange() {
        CompanionOutputService.FinalizedOutput output =
                CompanionOutputService.finalizeDrops(
                        List.of(new TestItemStack(
                                "Ingredient_Fabric_Scrap_Wool", 2)),
                        4
                );

        assertEquals(4, output.itemStacks().size());
        assertEquals(
                Map.of("Ingredient_Fabric_Scrap_Wool", 8),
                output.itemQuantities()
        );
    }

    @Test
    void expectedYieldScalesOnlyResolvedEligibleProducts() {
        CompanionOutputService.FinalizedOutput output = CompanionOutputService.finalizeExpectedQuantity(
                List.of(
                        new TestItemStack("Ingredient_Fabric_Scrap_Wool", 2),
                        new TestItemStack("Animal_Manure", 3)
                ),
                stack -> stack.getItemId().contains("Wool") ? 0.25 : 0.0,
                () -> 0.40
        );

        assertEquals(Map.of("Ingredient_Fabric_Scrap_Wool", 3, "Animal_Manure", 3),
                output.itemQuantities());
    }

    @Test
    void conversionConsumesOnlySuccessfulCompleteBatchesAndLeavesTheRemainder() {
        Map<String, Integer> output = CompanionOutputService.resolveOutputConversions(
                Map.of("wool", 5),
                itemId -> new HusbandryOutputConversion("wool", "cloth", 2, 1, 0.5),
                sequence(0.25, 0.75)
        );

        assertEquals(Map.of("wool", 3, "cloth", 1), output);
    }

    @Test
    void conversionNeverCreatesOutputWhenItsChanceFails() {
        Map<String, Integer> output = CompanionOutputService.resolveOutputConversions(
                Map.of("wool", 2),
                itemId -> new HusbandryOutputConversion("wool", "cloth", 2, 1, 0.25),
                () -> 0.25
        );

        assertEquals(Map.of("wool", 2), output);
    }

    private static java.util.function.DoubleSupplier sequence(double... values) {
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
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
