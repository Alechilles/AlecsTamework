package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryRewardEntry;
import com.alechilles.alecstamework.api.HusbandryRewardTable;
import com.alechilles.alecstamework.api.HusbandryRewardTableApi;
import com.alechilles.alecstamework.api.internal.HusbandryRewardTableRegistry;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.lang.reflect.Field;
import java.util.Set;
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

    @Test
    void aProviderTableReplacesTheConfiguredListAndAnEmptyOneRollsNothing() {
        ItemDropList dropList = new ItemDropList(
                "RH_Slaughter_Cow",
                new SingleItemDropContainer(new ItemDrop("Food_Beef_Raw", null, 3, 3), 100.0)
        );
        HusbandryRewardTable table = new HusbandryRewardTable(List.of(
                new HusbandryRewardEntry("Hide_Cow", 2, 4, 1.0),
                new HusbandryRewardEntry("Food_Beef_Raw", 5, 5, 0.0)));

        Map<String, Integer> fromTable = CullRewardService.baseRoll(table, dropList, new Random(3L))
                .itemQuantities();

        assertEquals(Set.of("Hide_Cow"), fromTable.keySet());
        assertTrue(fromTable.get("Hide_Cow") >= 2 && fromTable.get("Hide_Cow") <= 4);
        assertEquals(Map.of(), CullRewardService.baseRoll(
                HusbandryRewardTable.empty(), dropList, new Random(3L)).itemQuantities());
        assertEquals(Map.of("Food_Beef_Raw", 3), CullRewardService.baseRoll(
                null, dropList, new Random(3L)).itemQuantities());
        assertNull(CullRewardService.baseRoll(null, null, new Random(3L)));
    }

    @Test
    void aProviderTablePaysAdultsAndPaysJuvenilesNothing() throws Exception {
        HusbandryRewardTableRegistry tables = new HusbandryRewardTableRegistry();
        AtomicReference<HusbandryOutcomeKind> observedKind = new AtomicReference<>();
        tables.register(context -> {
            observedKind.set(context.kind());
            return new HusbandryRewardTable(List.of(new HusbandryRewardEntry("Test_Meat", 3, 3, 1.0)));
        });
        Field itemStore = Item.class.getDeclaredField("ASSET_STORE");
        itemStore.setAccessible(true);
        Object previousItems = itemStore.get(null);
        itemStore.set(null, new TestItemAssetStore(
                new DefaultAssetMap<>(Map.of("Test_Meat", new Item("Test_Meat")))));
        invokeRuntime("installRewardTables", HusbandryRewardTableApi.class, tables);
        try {
            // No drop list is configured: the provider's table alone is the base roll.
            CullRewardService.PreparedOutcome adult = CullRewardService.prepare(
                    null, null, null, "Test_Cow", null, null);
            CullRewardService.PreparedOutcome juvenile = CullRewardService.prepare(
                    null, null, null, "Test_Cow_Baby", null, null);

            assertTrue(adult.domesticDropsPrepared());
            assertEquals(Map.of("Test_Meat", 3), adult.itemQuantities());
            assertTrue(juvenile.domesticDropsPrepared());
            assertEquals(Map.of(), juvenile.itemQuantities());
            assertEquals(HusbandryOutcomeKind.CULL_YIELD, observedKind.get());
        } finally {
            invokeRuntime("clearRewardTables", HusbandryRewardTableApi.class, tables);
            itemStore.set(null, previousItems);
            tables.close();
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
        invokeRuntime(methodName, com.alechilles.alecstamework.api.HusbandryOutcomeApi.class, api);
    }

    private static void invokeRuntime(String methodName, Class<?> apiType, Object api) throws Exception {
        Method method = HusbandryOutcomeRuntime.class.getDeclaredMethod(methodName, apiType);
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
