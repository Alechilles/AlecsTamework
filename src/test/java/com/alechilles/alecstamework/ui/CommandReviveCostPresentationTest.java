package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandReviveCostPresentationTest {
    @Test
    void exactOrderedCostsControlConfirmation() {
        CommandReviveCostPresentation presentation =
                new CommandReviveCostPresentation(
                        PaidCommandRevivalQuote.Status
                                .INSUFFICIENT_COST,
                        0L,
                        List.of(
                                cost("Life_Essence", 5, 2),
                                cost("Gold_Bar", 3, 7)
                        ),
                        "config-7",
                        null,
                        "insufficient-cost"
                );

        assertEquals(2, presentation.costs().size());
        assertEquals("Life_Essence",
                presentation.costs().get(0).itemId());
        assertEquals(4,
                presentation.costs().get(1).shortageQuantity());
        assertEquals(1, presentation.missingComponentCount());
        assertTrue(presentation.actionVisible());
        assertFalse(presentation.confirmEnabled());
    }

    @Test
    void readyEmptyRecipeIsAValidPaidRevivalQuote() {
        CommandReviveCostPresentation presentation =
                new CommandReviveCostPresentation(
                        PaidCommandRevivalQuote.Status.READY,
                        0L,
                        List.of(),
                        "config-free",
                        null,
                        null
                );

        assertTrue(presentation.affordable());
        assertTrue(presentation.confirmEnabled());
    }

    private static CommandReviveCostPresentation.CostLine cost(
            String itemId,
            int owned,
            int required
    ) {
        return new CommandReviveCostPresentation.CostLine(
                itemId, itemId, null, owned, required
        );
    }
}
