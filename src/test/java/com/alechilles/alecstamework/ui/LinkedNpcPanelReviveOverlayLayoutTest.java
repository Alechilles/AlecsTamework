package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for compact, row-driven paid-revival modal layout. */
class LinkedNpcPanelReviveOverlayLayoutTest {
    @Test
    void modalHeightAndCostViewportFollowQuotedItemRows() {
        LinkedNpcPanelReviveOverlayState overlay =
                new LinkedNpcPanelReviveOverlayState();
        overlay.open(entry(), quote(
                new CommandReviveCostPresentation.CostLine(
                        "Revitalizing_Essence", "Revitalizing_Essence", null, 100, 2),
                new CommandReviveCostPresentation.CostLine(
                        "Draconic_Essence", "Draconic_Essence", null, 100, 4)
        ));
        UICommandBuilder commands = new UICommandBuilder();

        overlay.applyTo(commands, "en-US");

        assertCommand(commands, "#TameworkLinkedPanelReviveModal.Anchor", "180");
        assertCommand(commands, "#TameworkLinkedPanelReviveCostViewport.Anchor", "88");
        assertCommand(commands, "#TameworkLinkedPanelReviveActions.Anchor", "146");
    }

    private static LinkedNpcEntry entry() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Nordic Drake", 0, 400,
                0, 0, "", 0, 0, 0, 0, false, false,
                true, false, false, false, 0L,
                new LinkedNpcTraitIndicator[0]);
    }

    private static CommandReviveCostPresentation quote(
            CommandReviveCostPresentation.CostLine... costs
    ) {
        return new CommandReviveCostPresentation(PaidCommandRevivalQuote.Status.READY,
                0L, List.of(costs), "policy-1", null, null);
    }

    private static void assertCommand(
            UICommandBuilder commands, String selector, String expected
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)
                                && command.data.contains(expected)),
                () -> "Expected " + selector + " to contain " + expected);
    }
}
