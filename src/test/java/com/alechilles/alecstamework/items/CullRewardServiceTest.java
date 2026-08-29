package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.SingleItemDropContainer;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Behavior checks for configured domestic cull rewards. */
class CullRewardServiceTest {

    @Test
    void rollsConfiguredDropListAndReportsPublishedQuantities() {
        ItemDrop drop = new ItemDrop("Food_Beef_Raw", null, 3, 3);
        ItemDropList dropList = new ItemDropList(
                "RH_Slaughter_Cow",
                new SingleItemDropContainer(drop, 100.0)
        );

        CullRewardService.Roll result = CullRewardService.roll(
                dropList,
                new Random(7L)
        );

        assertEquals(1, result.rewards().size());
        assertEquals("Food_Beef_Raw", result.rewards().get(0).itemId());
        assertEquals(3, result.rewards().get(0).quantity());
        assertEquals(Map.of("Food_Beef_Raw", 3), result.itemQuantities());
    }

    @Test
    void normalRewardRollDoesNotResolveCullYieldOutsideCullApply() throws Exception {
        ItemDrop drop = new ItemDrop("Food_Beef_Raw", null, 3, 3);
        ItemDropList dropList = new ItemDropList(
                "RH_Slaughter_Cow",
                new SingleItemDropContainer(drop, 100.0)
        );
        HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        AtomicReference<HusbandryOutcomeKind> observedKind = new AtomicReference<>();
        registry.register(context -> {
            observedKind.set(context.kind());
            return new HusbandryOutcomeModifiers(1.0, 1.0, 1.0, 1.0, 1.0);
        });
        installRuntime(registry);
        try {
            CullRewardService.Roll result = CullRewardService.roll(
                    dropList,
                    new Random(7L)
            );

            assertEquals(Map.of("Food_Beef_Raw", 3), result.itemQuantities());
            assertNull(observedKind.get());
        } finally {
            clearRuntime(registry);
            registry.close();
        }
    }

    @Test
    void cullBatchKeepsNormalQuantitiesWhenPrimaryRollFails() {
        assertEquals(
                Map.of("Food_Beef_Raw", 3, "Hide_Cow", 2),
                cullQuantities(sequence(0.25))
        );
    }

    @Test
    void cullBatchDoublesEveryDropWhenPrimaryRollSucceeds() {
        assertEquals(
                Map.of("Food_Beef_Raw", 6, "Hide_Cow", 4),
                cullQuantities(sequence(0.24, 0.05))
        );
    }

    @Test
    void cullBatchTriplesEveryDropWhenUpgradeRollSucceeds() {
        assertEquals(
                Map.of("Food_Beef_Raw", 9, "Hide_Cow", 6),
                cullQuantities(sequence(0.24, 0.04))
        );
    }

    @Test
    void cullResolvesCullYieldOutcome() throws Exception {
        HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        AtomicReference<HusbandryOutcomeKind> observedKind = new AtomicReference<>();
        registry.register(context -> {
            observedKind.set(context.kind());
            return new HusbandryOutcomeModifiers(1.0, 1.0, 0.25, 0.05, 1.0);
        });
        installRuntime(registry);
        try {
            assertEquals(
                    1,
                    CullRewardService.resolveBonusCopies(
                            null, null, sequence(0.10, 0.90)
                    )
            );
            assertEquals(HusbandryOutcomeKind.CULL_YIELD, observedKind.get());
        } finally {
            clearRuntime(registry);
            registry.close();
        }
    }

    private static Map<String, Integer> cullQuantities(
            java.util.function.DoubleSupplier random
    ) {
        int bonusCopies = CullRewardService.resolveBonusCopies(
                new HusbandryOutcomeModifiers(1.0, 1.0, 0.25, 0.05, 1.0),
                random
        );
        return CompanionOutputService.finalizeDrops(
                List.of(
                        new TestItemStack("Food_Beef_Raw", 3),
                        new TestItemStack("Hide_Cow", 2)
                ),
                bonusCopies
        ).itemQuantities();
    }

    private static java.util.function.DoubleSupplier sequence(double... values) {
        return new java.util.function.DoubleSupplier() {
            private int index;

            @Override
            public double getAsDouble() {
                if (index >= values.length) {
                    throw new AssertionError("Unexpected random roll");
                }
                return values[index++];
            }
        };
    }

    private static void installRuntime(Object api) throws Exception {
        invokeRuntime("install", api);
    }

    private static void clearRuntime(Object api) throws Exception {
        invokeRuntime("clear", api);
    }

    private static void invokeRuntime(String methodName, Object api) throws Exception {
        Method method = HusbandryOutcomeRuntime.class.getDeclaredMethod(
                methodName,
                com.alechilles.alecstamework.api.HusbandryOutcomeApi.class
        );
        method.setAccessible(true);
        method.invoke(null, api);
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
