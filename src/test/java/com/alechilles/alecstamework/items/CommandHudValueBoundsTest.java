package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production-boundary tests for HUD contributor value limits. */
class CommandHudValueBoundsTest {
    private static final CommandHudContributorId CONTRIBUTOR =
            CommandHudContributorId.of("example:bounds");

    @Test
    void contributionDepthUsesThePageApiDepthLimit() {
        assertTrue(CommandHudValueBounds.validateContribution(contribution(
                Map.of("value", nestedLists(CommandHudValueBounds.MAX_CONTRIBUTION_DEPTH - 1)))).valid());
        assertFalse(CommandHudValueBounds.validateContribution(contribution(
                Map.of("value", nestedLists(CommandHudValueBounds.MAX_CONTRIBUTION_DEPTH)))).valid());
    }

    @Test
    void contributionChildrenAndCharactersUsePageApiLimits() {
        assertTrue(CommandHudValueBounds.validateContribution(contribution(
                values(CommandHudValueBounds.MAX_CONTRIBUTION_CHILDREN))).valid());
        assertFalse(CommandHudValueBounds.validateContribution(contribution(
                values(CommandHudValueBounds.MAX_CONTRIBUTION_CHILDREN + 1))).valid());
        String exact = "x".repeat(CommandHudValueBounds.MAX_CONTRIBUTION_TOTAL_CHARACTERS
                - "value".length());
        assertTrue(CommandHudValueBounds.validateContribution(contribution(
                Map.of("value", CommandUiValue.string(exact)))).valid());
        assertFalse(CommandHudValueBounds.validateContribution(contribution(
                Map.of("value", CommandUiValue.string(exact + "x")))).valid());
    }

    private static CommandHudContribution contribution(Map<String, CommandUiValue> data) {
        return new CommandHudContribution(CONTRIBUTOR, data);
    }

    private static CommandUiValue nestedLists(int listCount) {
        CommandUiValue value = CommandUiValue.booleanValue(true);
        for (int index = 0; index < listCount; index++) {
            value = CommandUiValue.list(List.of(value));
        }
        return value;
    }

    private static Map<String, CommandUiValue> values(int count) {
        Map<String, CommandUiValue> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            values.put("value." + index, CommandUiValue.booleanValue(true));
        }
        return values;
    }
}
