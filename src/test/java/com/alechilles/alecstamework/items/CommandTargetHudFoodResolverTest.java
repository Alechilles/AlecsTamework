package com.alechilles.alecstamework.items;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudFoodResolverTest {
    @Test
    void choosesFirstNonBlankItemId() {
        CommandTargetHudFoodResolver resolver = new CommandTargetHudFoodResolver(
                (player, itemId) -> "Name:" + itemId,
                itemId -> "Icon:" + itemId
        );

        CommandTargetHudViewModel.FoodRow row = resolver.resolveFavoriteFood(
                null,
                new String[] { "", "Tw_Feed_Herbivore", "Apple" }
        );

        Assertions.assertNotNull(row);
        Assertions.assertEquals("Tw_Feed_Herbivore", row.itemId());
        Assertions.assertEquals("Name:Tw_Feed_Herbivore", row.displayName());
        Assertions.assertEquals("Icon:Tw_Feed_Herbivore", row.iconPath());
    }

    @Test
    void returnsNullWhenNoFoodItemsExist() {
        CommandTargetHudFoodResolver resolver = new CommandTargetHudFoodResolver(
                (player, itemId) -> "Name:" + itemId,
                itemId -> "Icon:" + itemId
        );

        Assertions.assertNull(resolver.resolveFavoriteFood(null, new String[0]));
    }

    @Test
    void resolvesAllCompatibleFoodRowsInOrder() {
        CommandTargetHudFoodResolver resolver = new CommandTargetHudFoodResolver(
                (player, itemId) -> "Name:" + itemId,
                itemId -> "Icon:" + itemId
        );

        List<CommandTargetHudViewModel.FoodRow> rows = resolver.resolveFoods(
                null,
                new String[] { "Tool_Feedbag", "", "Tw_Feed_Herbivore" }
        );

        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals("Tool_Feedbag", rows.get(0).itemId());
        Assertions.assertEquals("Name:Tool_Feedbag", rows.get(0).displayName());
        Assertions.assertEquals("Tw_Feed_Herbivore", rows.get(1).itemId());
    }
}
