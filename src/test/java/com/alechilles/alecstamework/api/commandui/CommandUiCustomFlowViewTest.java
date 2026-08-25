package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior contract for immutable custom command UI flow envelopes. */
class CommandUiCustomFlowViewTest {
    private static final UUID FLOW_ID = UUID.fromString(
            "2f4a3f66-2f93-4ef0-9ef7-3ee1ed6fe5a0");
    private static final CommandUiContributorId OWNER =
            CommandUiContributorId.of("runeteria:husbandry");

    @Test
    void copiesAndExposesImmutableFlowState() {
        CommandUiValue pageValue = CommandUiValue.object(Map.of(
                "step", CommandUiValue.string("overview")));
        CommandUiActionView action = new CommandUiActionView(
                "continue", "Continue", true, null, false,
                new CommandUiActionHandle("continue-token"));
        Map<String, CommandUiValue> data = new LinkedHashMap<>();
        data.put("page", pageValue);
        Map<String, CommandUiActionView> actions = new LinkedHashMap<>();
        actions.put("continue", action);

        CommandUiCustomFlowView flow = new CommandUiCustomFlowView(
                FLOW_ID, "Runeteria:Husbandry_UI_Demo/Checklist", OWNER,
                7L, 2L, 11L, data, actions);
        data.put("other", CommandUiValue.string("caller mutation"));
        actions.clear();

        assertEquals(FLOW_ID, flow.flowInstanceId());
        assertEquals("runeteria:husbandry_ui_demo/checklist", flow.flowType());
        assertEquals("runeteria:husbandry_ui_demo/checklist", flow.kind());
        assertSame(OWNER, flow.ownerContributorId());
        assertEquals(7L, flow.ownerGeneration());
        assertEquals(2L, flow.revision());
        assertEquals(11L, flow.actionGeneration());
        assertEquals(Map.of("page", pageValue), flow.data());
        assertEquals(Map.of("continue", action), flow.actions());
        assertTrue(flow.isCustom());
        assertThrows(UnsupportedOperationException.class,
                () -> flow.data().put("other", CommandUiValue.string("nope")));
        assertThrows(UnsupportedOperationException.class,
                () -> flow.actions().clear());
    }

    @Test
    void validatesTypeAndPositiveRevisionGenerations() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiCustomFlowView(
                        FLOW_ID, "checklist", OWNER, 1L, 1L, 1L,
                        Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiCustomFlowView(
                        FLOW_ID, "runeteria:", OWNER, 1L, 1L, 1L,
                        Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiCustomFlowView(
                        FLOW_ID, "runeteria:checklist", OWNER, 0L, 1L, 1L,
                        Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiCustomFlowView(
                        FLOW_ID, "runeteria:checklist", OWNER, 1L, 0L, 1L,
                        Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiCustomFlowView(
                        FLOW_ID, "runeteria:checklist", OWNER, 1L, 1L, 0L,
                        Map.of(), Map.of()));
    }

    @Test
    void actionResultsDescribeCustomFlowOperations() {
        CommandUiCustomFlowView flow = flow(1L, 1L, 1L);

        CommandUiActionResult opened = CommandUiActionResult.openFlow(flow);
        CommandUiActionResult replaced = CommandUiActionResult.replaceFlow(
                flow(1L, 2L, 2L));
        CommandUiActionResult updated = CommandUiActionResult.updateFlow(
                flow(1L, 2L, 2L));
        CommandUiActionResult closed = CommandUiActionResult.closeFlow();

        assertEquals(CommandUiFlowOperation.OPEN, opened.flowOperation());
        assertEquals(CommandUiFlowOperation.REPLACE, replaced.flowOperation());
        assertEquals(CommandUiFlowOperation.UPDATE, updated.flowOperation());
        assertEquals(CommandUiFlowOperation.CLOSE, closed.flowOperation());
        assertSame(flow, opened.flowView());
        assertEquals(CommandUiActionStatus.ACCEPTED, opened.status());
        assertEquals(CommandUiActionStatus.APPLIED, replaced.status());
        assertEquals(CommandUiActionStatus.APPLIED, updated.status());
        assertEquals(CommandUiActionStatus.APPLIED, closed.status());
        assertNull(closed.flowView());
        assertFalse(closed.refreshSnapshot());
        assertFalse(opened.refreshSnapshot());
    }

    @Test
    void rejectsContradictoryFlowResults() {
        CommandUiCustomFlowView flow = flow(1L, 1L, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiActionResult(
                        CommandUiActionStatus.FAILED, null, null, null,
                        Map.of(), flow, CommandUiFlowOperation.OPEN, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiActionResult(
                        CommandUiActionStatus.CONFIRMATION_REQUIRED, null,
                        null, null, Map.of(), null,
                        CommandUiFlowOperation.CLOSE, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiActionResult(
                        CommandUiActionStatus.APPLIED, null, null, null,
                        Map.of(), flow, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandUiActionResult(
                        CommandUiActionStatus.FAILED, null, null, null,
                        Map.of(), flow, false));
    }

    @Test
    void legacyFlowFactoriesRemainCompatibleWithTheSharedContract() {
        CommandUiFlowView flow = () -> "talents";

        CommandUiActionResult presented = CommandUiActionResult.presented(flow);
        CommandUiActionResult updated = CommandUiActionResult.updated(null, flow);

        assertEquals(CommandUiFlowOperation.OPEN, presented.flowOperation());
        assertEquals(CommandUiFlowOperation.UPDATE, updated.flowOperation());
        assertSame(flow, presented.flowView());
        assertSame(flow, updated.flowView());
    }

    private static CommandUiCustomFlowView flow(
            long ownerGeneration, long revision, long actionGeneration) {
        return new CommandUiCustomFlowView(
                FLOW_ID, "runeteria:checklist", OWNER, ownerGeneration,
                revision, actionGeneration, Map.of(), Map.of());
    }
}
