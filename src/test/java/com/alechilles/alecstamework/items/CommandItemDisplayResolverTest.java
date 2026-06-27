package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandItemDisplayResolverTest {
    @Test
    void resolvesBundledCommandItemName() {
        CommandItemDisplayResolver resolver = new CommandItemDisplayResolver();

        Assertions.assertEquals(
                "Tamework Example Command Whistle",
                resolver.resolveItemDisplayName(null, "Tamework_Command_Whistle_Example")
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
