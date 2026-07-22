package com.alechilles.alecstamework.config.assets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TwItemCostComponentTest {
    @Test
    void orderedMultiItemCostIsDefensivelyCopied() {
        TwItemCostComponent[] source = {
                new TwItemCostComponent("Life_Essence", 2),
                new TwItemCostComponent("Gold_Bar", 7)
        };

        TwItemCostComponent[] result = TwItemCostComponent.validateAndCopy(source);
        source[0] = new TwItemCostComponent("Changed", 1);

        assertEquals("Life_Essence", result[0].getItemId());
        assertEquals(2, result[0].getQuantity());
        assertEquals("Gold_Bar", result[1].getItemId());
        assertEquals(7, result[1].getQuantity());
    }

    @Test
    void duplicateItemIdsAreRejected() {
        TwItemCostComponent[] duplicate = {
                new TwItemCostComponent("Life_Essence", 1),
                new TwItemCostComponent("Life_Essence", 3)
        };

        assertThrows(IllegalArgumentException.class, () -> TwItemCostComponent.validateAndCopy(duplicate));
    }

    @Test
    void blankIdsAndNonPositiveQuantitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TwItemCostComponent(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> new TwItemCostComponent("Life_Essence", 0));
        assertThrows(IllegalArgumentException.class, () -> new TwItemCostComponent("Life_Essence", -1));
    }
}
