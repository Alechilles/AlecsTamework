package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production-boundary tests for action input and contributor value limits. */
class CommandUiValueBoundsTest {
    private static final CommandUiValue SCALAR =
            CommandUiValue.booleanValue(true);
    private static final CommandUiContributorId CONTRIBUTOR =
            CommandUiContributorId.of("example:bounds");

    @Test
    void actionDepthUsesOneBasedRootAndAcceptsExactLimitOnly() {
        assertValid(CommandUiValueBounds.validateActionInput(
                nestedLists(CommandUiValueBounds.MAX_ACTION_DEPTH - 1)));
        assertInvalid(CommandUiValueBounds.validateActionInput(
                nestedLists(CommandUiValueBounds.MAX_ACTION_DEPTH)));
    }

    @Test
    void actionNodeLimitAcceptsExactLimitAndRejectsOnePast() {
        assertValid(CommandUiValueBounds.validateActionInput(
                actionTreeWithNodes(CommandUiValueBounds.MAX_ACTION_NODES)));
        assertInvalid(CommandUiValueBounds.validateActionInput(
                actionTreeWithNodes(CommandUiValueBounds.MAX_ACTION_NODES + 1)));
    }

    @Test
    void actionChildrenLimitAcceptsExactListAndObjectSizes() {
        assertValid(CommandUiValueBounds.validateActionInput(
                listWithChildren(CommandUiValueBounds.MAX_ACTION_CHILDREN)));
        assertInvalid(CommandUiValueBounds.validateActionInput(
                listWithChildren(CommandUiValueBounds.MAX_ACTION_CHILDREN + 1)));
        assertValid(CommandUiValueBounds.validateActionInput(
                objectWithChildren(CommandUiValueBounds.MAX_ACTION_CHILDREN,
                        1)));
        assertInvalid(CommandUiValueBounds.validateActionInput(
                objectWithChildren(CommandUiValueBounds.MAX_ACTION_CHILDREN + 1,
                        1)));
    }

    @Test
    void actionKeyAndCharacterLimitsAcceptExactValuesOnly() {
        assertValid(CommandUiValueBounds.validateActionInput(
                objectWithChildren(1,
                        CommandUiValueBounds.MAX_ACTION_KEY_LENGTH)));
        assertInvalid(CommandUiValueBounds.validateActionInput(
                objectWithChildren(1,
                        CommandUiValueBounds.MAX_ACTION_KEY_LENGTH + 1)));
        assertValid(CommandUiValueBounds.validateActionInput(
                CommandUiValue.string("x".repeat(
                        CommandUiValueBounds.MAX_ACTION_TOTAL_CHARACTERS))));
        assertInvalid(CommandUiValueBounds.validateActionInput(
                CommandUiValue.string("x".repeat(
                        CommandUiValueBounds.MAX_ACTION_TOTAL_CHARACTERS + 1))));
    }

    @Test
    void contributionDepthUsesOneBasedValueRoots() {
        assertValid(contribution(Map.of("value", nestedLists(
                CommandUiValueBounds.MAX_CONTRIBUTION_DEPTH - 1))));
        assertInvalid(contribution(Map.of("value", nestedLists(
                CommandUiValueBounds.MAX_CONTRIBUTION_DEPTH))));
    }

    @Test
    void contributionNodeLimitIsSharedAcrossPageAndRows() {
        Map<String, CommandUiValue> page = values(256, "page-");
        Map<UUID, Map<String, CommandUiValue>> rows = new LinkedHashMap<>();
        for (int index = 0; index < 7; index++) {
            rows.put(UUID.randomUUID(), values(256, "row-" + index + "-"));
        }
        assertValid(contribution(page, rows));

        rows.put(UUID.randomUUID(), Map.of("extra", SCALAR));
        assertInvalid(contribution(page, rows));
    }

    @Test
    void contributionChildrenLimitAcceptsExactPageMapAndRejectsOnePast() {
        assertValid(contribution(values(
                CommandUiValueBounds.MAX_CONTRIBUTION_CHILDREN, "page-")));
        assertInvalid(contribution(values(
                CommandUiValueBounds.MAX_CONTRIBUTION_CHILDREN + 1, "page-")));
        assertValid(contribution(Map.of("object", objectWithChildren(
                CommandUiValueBounds.MAX_CONTRIBUTION_CHILDREN, 1))));
        assertInvalid(contribution(Map.of("object", objectWithChildren(
                CommandUiValueBounds.MAX_CONTRIBUTION_CHILDREN + 1, 1))));
    }

    @Test
    void contributionRowLimitRejectsUnboundedEmptyRows() {
        Map<UUID, Map<String, CommandUiValue>> rows = new LinkedHashMap<>();
        for (int index = 0;
             index < CommandUiValueBounds.MAX_CONTRIBUTION_CHILDREN;
             index++) {
            rows.put(UUID.randomUUID(), Map.of());
        }
        assertValid(contribution(Map.of(), rows));

        rows.put(UUID.randomUUID(), Map.of());

        assertInvalid(contribution(Map.of(), rows));
    }

    @Test
    void contributionKeyAndCharacterLimitsAcceptExactValuesOnly() {
        assertValid(contribution(Map.of(
                "k".repeat(CommandUiValueBounds.MAX_CONTRIBUTION_KEY_LENGTH),
                SCALAR)));
        assertInvalid(contribution(Map.of(
                "k".repeat(CommandUiValueBounds.MAX_CONTRIBUTION_KEY_LENGTH + 1),
                SCALAR)));

        String exact = "x".repeat(
                CommandUiValueBounds.MAX_CONTRIBUTION_TOTAL_CHARACTERS - 1);
        assertValid(contribution(Map.of("k", CommandUiValue.string(exact))));
        assertInvalid(contribution(Map.of("k", CommandUiValue.string(exact + "x"))));
    }

    private static CommandUiValue nestedLists(int listCount) {
        CommandUiValue value = SCALAR;
        for (int index = 0; index < listCount; index++) {
            value = CommandUiValue.list(List.of(value));
        }
        return value;
    }

    private static CommandUiValue actionTreeWithNodes(int nodes) {
        List<CommandUiValue> children = new ArrayList<>();
        int listCount = Math.min(CommandUiValueBounds.MAX_ACTION_CHILDREN,
                (nodes - 1) / 2);
        int scalarCount = nodes - 1 - listCount * 2;
        for (int index = 0; index < listCount; index++) {
            children.add(CommandUiValue.list(List.of(SCALAR)));
        }
        for (int index = 0; index < scalarCount; index++) {
            children.add(SCALAR);
        }
        if (children.size() > CommandUiValueBounds.MAX_ACTION_CHILDREN) {
            throw new IllegalStateException("test tree exceeds its root child limit");
        }
        return CommandUiValue.list(children);
    }

    private static CommandUiValue listWithChildren(int count) {
        List<CommandUiValue> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(SCALAR);
        return CommandUiValue.list(values);
    }

    private static CommandUiValue objectWithChildren(int count, int keyLength) {
        Map<String, CommandUiValue> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = "k".repeat(Math.max(0, keyLength -
                    Integer.toString(index).length())) + index;
            values.put(key, SCALAR);
        }
        return CommandUiValue.object(values);
    }

    private static Map<String, CommandUiValue> values(int count, String prefix) {
        Map<String, CommandUiValue> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            values.put(prefix + index, SCALAR);
        }
        return values;
    }

    private static CommandUiContribution contribution(
            Map<String, CommandUiValue> pageData
    ) {
        return contribution(pageData, Map.of());
    }

    private static CommandUiContribution contribution(
            Map<String, CommandUiValue> pageData,
            Map<UUID, Map<String, CommandUiValue>> rowData
    ) {
        return CommandUiContribution.ready(CONTRIBUTOR, pageData, rowData);
    }

    private static void assertValid(CommandUiValueBounds.Validation validation) {
        assertTrue(validation.valid(), validation.message());
    }

    private static void assertValid(CommandUiContribution contribution) {
        assertValid(CommandUiValueBounds.validateContribution(contribution));
    }

    private static void assertInvalid(CommandUiValueBounds.Validation validation) {
        assertFalse(validation.valid(), "expected bounds validation to fail");
    }

    private static void assertInvalid(CommandUiContribution contribution) {
        assertInvalid(CommandUiValueBounds.validateContribution(contribution));
    }
}
