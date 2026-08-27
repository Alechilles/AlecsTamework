package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;

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
                        new HusbandryOutcomeModifiers(1.0, 1.0, 1.0, 1.0),
                        sequence(0.10, 0.20)
                )
        );
    }

    @Test
    void providerThrowFallsBackToBaselineProductCopies() throws Exception {
        HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        registry.register(context -> {
            throw new IllegalStateException("provider failure");
        });
        HusbandryOutcomeRuntime.install(registry);
        HusbandryOutcomeContext context = new HusbandryOutcomeContext(
                HusbandryOutcomeKind.PRODUCT_BONUS,
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
            HusbandryOutcomeRuntime.clear(registry);
            registry.close();
        }
    }

    private static java.util.function.DoubleSupplier sequence(double... values) {
        return new java.util.function.DoubleSupplier() {
            private int index;

            @Override
            public double getAsDouble() {
                return values[Math.min(index++, values.length - 1)];
            }
        };
    }
}
