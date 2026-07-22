package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandReviveInventoryPaymentServiceTest {
    @Test
    void plansEveryComponentAcrossSplitStacksInStableOrder() {
        List<CommandReviveInventoryPaymentService.SlotStack> slots = List.of(
                slot("backpack", 0, "Life_Essence", 2, "a"),
                slot("backpack", 1, "Gold_Bar", 3, "b"),
                slot("storage", 0, "Life_Essence", 4, "c"),
                slot("hotbar", 2, "Gold_Bar", 8, "d"));

        var result = CommandReviveInventoryPaymentService.plan(slots, List.of(
                new ItemCostComponentView("Life_Essence", 5),
                new ItemCostComponentView("Gold_Bar", 7)), 11L);

        assertTrue(result.ready());
        assertEquals(List.of(2, 3, 3, 4),
                result.reservations().stream().map(r -> r.quantity()).toList());
        assertEquals(List.of(0, 0, 1, 1),
                result.reservations().stream().map(r -> r.costOrdinal()).toList());
        assertEquals(List.of("backpack", "storage", "backpack", "hotbar"),
                result.reservations().stream().map(r -> r.compartmentId()).toList());
    }

    @Test
    void missingOneComponentProducesNoPartialReservation() {
        var result = CommandReviveInventoryPaymentService.plan(List.of(
                slot("backpack", 0, "Life_Essence", 99, "a"),
                slot("storage", 0, "Gold_Bar", 1, "b")), List.of(
                new ItemCostComponentView("Life_Essence", 2),
                new ItemCostComponentView("Gold_Bar", 4)), 1L);

        assertEquals(CommandReviveInventoryPaymentService.Status.INSUFFICIENT, result.status());
        assertEquals("Gold_Bar", result.missingItemId());
        assertEquals(3, result.shortage());
        assertTrue(result.reservations().isEmpty());
    }

    @Test
    void emptyRecipeIsAValidFreeRevival() {
        var result = CommandReviveInventoryPaymentService.plan(List.of(), List.of(), 0L);
        assertTrue(result.ready());
        assertTrue(result.reservations().isEmpty());
    }

    private static CommandReviveInventoryPaymentService.SlotStack slot(
            String compartment, int slot, String itemId, int quantity, String fingerprint) {
        return new CommandReviveInventoryPaymentService.SlotStack(
                compartment, slot, itemId, quantity, fingerprint);
    }
}
