package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandItemDisplayResolverTest {
    @Test
    void humanizesCommandItemWithoutLoadedLocalization() {
        CommandItemDisplayResolver resolver = new CommandItemDisplayResolver();

        Assertions.assertEquals(
                "Runeteria Husbandry Whistle",
                resolver.resolveItemDisplayName(null, "Runeteria_Husbandry_Whistle")
        );
    }

    @Test
    void fallsBackToDefaultCommandItemLabel() {
        CommandItemDisplayResolver resolver = new CommandItemDisplayResolver();

        Assertions.assertEquals("command item", resolver.resolveItemDisplayName(null, null));
    }

    @Test
    void resolvesDefaultCraftingStationLabel() {
        CommandItemDisplayResolver resolver = new CommandItemDisplayResolver();

        Assertions.assertEquals("crafting bench", resolver.resolveCraftingStationDisplayName(null));
    }
}
