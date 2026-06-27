package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandAutoLinkServiceResultTest {
    @Test
    void linkedResultCarriesAnimalAndToolNames() {
        CommandAutoLinkResult result = CommandAutoLinkResult.linked("Tamed Fox", "Command Whistle");

        Assertions.assertEquals(CommandAutoLinkResult.Status.LINKED, result.status());
        Assertions.assertEquals("Tamed Fox", result.animalDisplayName());
        Assertions.assertEquals("Command Whistle", result.commandItemDisplayName());
    }

    @Test
    void missingToolResultCarriesCraftingHint() {
        CommandAutoLinkResult result = CommandAutoLinkResult.noApplicableTool(
                "Tamed Fox",
                "Command Whistle",
                "Crafting Bench"
        );

        Assertions.assertEquals(CommandAutoLinkResult.Status.NO_APPLICABLE_TOOL, result.status());
        Assertions.assertEquals("Tamed Fox", result.animalDisplayName());
        Assertions.assertEquals("Command Whistle", result.commandItemDisplayName());
        Assertions.assertEquals("Crafting Bench", result.craftingStationDisplayName());
    }
}
