package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudTameRequirementResolverTest {
    @Test
    void buildsNoRowWhenNoTranquilizerRequirementExists() {
        CommandTargetHudTameRequirementResolver resolver = new CommandTargetHudTameRequirementResolver();

        Assertions.assertNull(resolver.fromRequiredRemainingSeconds(0.0, null));
    }

    @Test
    void convertsRequiredSecondsToStacks() {
        CommandTargetHudTameRequirementResolver resolver = new CommandTargetHudTameRequirementResolver();

        CommandTargetHudViewModel.TameRequirementRow row =
                resolver.fromRequiredRemainingSeconds(80.0, null);

        Assertions.assertNotNull(row);
        Assertions.assertTrue(row.tranquilizerRequired());
        Assertions.assertEquals(3, row.requiredStacks());
        Assertions.assertNull(row.currentStacksText());
    }

    @Test
    void includesCurrentStackTextWhenAvailable() {
        CommandTargetHudTameRequirementResolver resolver = new CommandTargetHudTameRequirementResolver();

        CommandTargetHudViewModel.TameRequirementRow row =
                resolver.fromRequiredRemainingSeconds(90.0, "2 (42s)");

        Assertions.assertNotNull(row);
        Assertions.assertEquals(3, row.requiredStacks());
        Assertions.assertEquals("2 (42s)", row.currentStacksText());
    }
}
