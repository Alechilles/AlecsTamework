package com.alechilles.alecstamework.ui;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkedNpcPanelReviveOverlayStateTest {
    @Test
    void costTextNamesEveryRequiredItemAndOwnedRequiredQuantity() {
        String text = LinkedNpcPanelReviveOverlayState.formatCostText(List.of(
                new CommandReviveCostPresentation.CostLine(
                        "Revitalizing_Essence", "Revitalizing Essence", null, 0, 2),
                new CommandReviveCostPresentation.CostLine(
                        "Draconic_Essence", "Draconic Essence", null, 1, 4)
        ), "en-US");

        assertTrue(text.contains("Revitalizing Essence    0 / 2"));
        assertTrue(text.contains("Draconic Essence    1 / 4"));
        assertTrue(text.contains("Missing 2"));
        assertTrue(text.contains("Missing 3"));
        assertTrue(text.contains("\n"), "Each configured item cost should have its own line.");
    }

    @Test
    void satisfiedCostDoesNotClaimAnItemIsMissing() {
        String text = LinkedNpcPanelReviveOverlayState.formatCostText(List.of(
                new CommandReviveCostPresentation.CostLine(
                        "Revitalizing_Essence", "Revitalizing Essence", null, 2, 2)
        ), "en-US");

        assertTrue(text.contains("Revitalizing Essence    2 / 2"));
        assertFalse(text.contains("Missing"));
    }
}
