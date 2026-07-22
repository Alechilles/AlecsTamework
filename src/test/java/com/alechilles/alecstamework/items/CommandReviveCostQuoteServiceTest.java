package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandReviveCostQuoteServiceTest {
    @Test
    void quoteShowsOwnedRequiredAndShortageForEveryOrderedCost() throws Exception {
        TwCompanionConfig.ReviveSettings settings = settings(
                new TwItemCostComponent("Life_Essence", 2),
                new TwItemCostComponent("Gold_Bar", 7));
        Map<String, Integer> owned = new LinkedHashMap<>();
        owned.put("Life_Essence", 5);
        owned.put("Gold_Bar", 3);

        var quote = new CommandReviveCostQuoteService().quote(owned, null, settings);

        assertEquals(2, quote.costs().size());
        assertEquals("Life_Essence", quote.costs().get(0).itemId());
        assertEquals(5, quote.costs().get(0).ownedQuantity());
        assertEquals(2, quote.costs().get(0).requiredQuantity());
        assertEquals(0, quote.costs().get(0).shortageQuantity());
        assertEquals("Gold_Bar", quote.costs().get(1).itemId());
        assertEquals(4, quote.costs().get(1).shortageQuantity());
        assertFalse(quote.affordable());
        assertEquals(1, quote.missingComponentCount());
    }

    @Test
    void fingerprintChangesWhenAnyQuantityChanges() throws Exception {
        var first = settings(new TwItemCostComponent("Life_Essence", 2));
        var second = settings(new TwItemCostComponent("Life_Essence", 3));

        assertNotEquals(CommandReviveCostQuoteService.fingerprint(first),
                CommandReviveCostQuoteService.fingerprint(second));
        assertTrue(new CommandReviveCostQuoteService()
                .quote(Map.of("Life_Essence", 3), null, second).affordable());
    }

    private static TwCompanionConfig.ReviveSettings settings(TwItemCostComponent... costs)
            throws Exception {
        TwCompanionConfig.ReviveSettings settings = new TwCompanionConfig.ReviveSettings();
        Field field = TwCompanionConfig.ReviveSettings.class.getDeclaredField("costs");
        field.setAccessible(true);
        field.set(settings, costs);
        return settings;
    }
}
