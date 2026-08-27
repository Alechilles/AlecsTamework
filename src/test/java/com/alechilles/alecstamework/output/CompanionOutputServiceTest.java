package com.alechilles.alecstamework.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Map;
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

        @Override
        public ItemStack cleanCopy() {
            return new TestItemStack(itemId, quantity);
        }
    }
}
