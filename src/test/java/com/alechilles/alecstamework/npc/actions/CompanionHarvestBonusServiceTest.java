package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionHarvestBonusServiceTest {
    @Test
    void dropDuplicateModeCanProcDropDuplication() {
        assertTrue(CompanionHarvestBonusService.shouldDuplicateDrops("DropDuplicate", 1.25, () -> 0.10));
        assertFalse(CompanionHarvestBonusService.shouldDuplicateDrops("DropDuplicate", 1.25, () -> 0.30));
    }

    @Test
    void cooldownPreserveModeDoesNotDuplicateDrops() {
        assertFalse(CompanionHarvestBonusService.shouldDuplicateDrops("CooldownPreserve", 1.95, () -> 0.0));
    }

    @Test
    void cooldownPreserveModeCanProcCooldownSkip() {
        assertTrue(CompanionHarvestBonusService.shouldPreserveCooldown("CooldownPreserve", 1.25, () -> 0.10));
        assertFalse(CompanionHarvestBonusService.shouldPreserveCooldown("CooldownPreserve", 1.25, () -> 0.30));
    }

    @Test
    void unknownModeFallsBackToDropDuplication() {
        assertTrue(CompanionHarvestBonusService.shouldDuplicateDrops("SomethingElse", 1.25, () -> 0.10));
        assertFalse(CompanionHarvestBonusService.shouldPreserveCooldown("SomethingElse", 1.25, () -> 0.10));
    }

    @Test
    void traitAndHusbandryBonusCopiesCompose() {
        assertEquals(
                3,
                CompanionHarvestBonusService.resolveBonusCopies(
                        true,
                        new HusbandryOutcomeModifiers(1.0, 1.0, 1.0, 1.0, 1.0),
                        sequence(0.10, 0.20)
                )
        );
    }

    @Test
    void failedPrimaryHarvestRollKeepsNormalOutput() {
        assertEquals(
                Map.of("Product_Milk", 2),
                harvestQuantities(sequence(0.25))
        );
    }

    @Test
    void successfulPrimaryHarvestRollDoublesOutput() {
        assertEquals(
                Map.of("Product_Milk", 4),
                harvestQuantities(sequence(0.24, 0.05))
        );
    }

    @Test
    void successfulPrimaryAndUpgradeHarvestRollTriplesOutput() {
        assertEquals(
                Map.of("Product_Milk", 6),
                harvestQuantities(sequence(0.24, 0.04))
        );
    }

    @Test
    void harvestResolvesHarvestYieldOutcome() throws Exception {
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
                    CompanionHarvestBonusService.resolveBonusCopies(
                            null, null, null, "Product_Milk", sequence(0.10, 0.90)
                    )
            );
            assertEquals(HusbandryOutcomeKind.HARVEST_YIELD, observedKind.get());
        } finally {
            clearRuntime(registry);
            registry.close();
        }
    }

    @Test
    void looseHarvestFinalizationRetainsExactCommittedQuantities() {
        InteractionInventoryEffects.DropItemOutcome output =
                InteractionInventoryEffects.finalizeDropOutput(
                        List.of(new TestItemStack("Product_Milk", 2)),
                        2
                );

        assertTrue(output.applied());
        assertEquals(Map.of("Product_Milk", 6), output.itemQuantities());
    }

    @Test
    void providerThrowFallsBackToBaselineHarvestCopies() throws Exception {
        HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        registry.register(context -> {
            throw new IllegalStateException("provider failure");
        });
        installRuntime(registry);
        HusbandryOutcomeContext context = new HusbandryOutcomeContext(
                HusbandryOutcomeKind.HARVEST_YIELD,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "Tamed_Cow",
                "runeteria:husbandry",
                Set.of("runeteria:husbandry"),
                "Product_Milk"
        );
        try {
            assertEquals(
                    0,
                    CompanionHarvestBonusService.resolveBonusCopies(
                            false, context, sequence(0.0)
                    )
            );
        } finally {
            clearRuntime(registry);
            registry.close();
        }
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

    private static Map<String, Integer> harvestQuantities(
            java.util.function.DoubleSupplier random
    ) {
        int bonusCopies = CompanionHarvestBonusService.resolveBonusCopies(
                false,
                new HusbandryOutcomeModifiers(1.0, 1.0, 0.25, 0.05, 1.0),
                random
        );
        return CompanionOutputService.finalizeDrops(
                List.of(new TestItemStack("Product_Milk", 2)),
                bonusCopies
        ).itemQuantities();
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
