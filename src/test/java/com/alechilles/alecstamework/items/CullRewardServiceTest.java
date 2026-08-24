package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.SingleItemDropContainer;
import java.util.Map;
import java.util.Random;
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
}
