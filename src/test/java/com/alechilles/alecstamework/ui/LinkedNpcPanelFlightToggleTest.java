package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for the normal linked-panel flight-toggle control. */
class LinkedNpcPanelFlightToggleTest {
    @Test
    void eligibleLinkedNpcShowsShoulderRideControlAndEvent() {
        UUID npcUuid = UUID.randomUUID();
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        LinkedNpcPanelCardBinder.bind(commands, events, 0,
                entry(npcUuid).withShoulderRide(true, false), false, false,
                bindingConfig(), "en-US");

        assertCommand(commands, "#TameworkLinkedPanelList[0] #ShoulderRideButton.Visible", "true");
        assertCommand(commands, "#TameworkLinkedPanelList[0] #ShoulderRideButton.Style", "ShoulderOff");
        assertCommand(commands, "#TameworkLinkedPanelList[0] #ShoulderRideButton.Text", "");
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event ->
                event.type == CustomUIEventBindingType.Activating
                        && "#TameworkLinkedPanelList[0] #ShoulderRideButton".equals(event.selector)
                        && event.data.contains("__linked_shoulder_ride__:" + npcUuid)));
    }

    @Test
    void eligibleLinkedNpcShowsItsGroundedFlightControlAndEvent() {
        UUID npcUuid = UUID.randomUUID();
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        LinkedNpcPanelCardBinder.bind(commands, events, 0,
                entry(npcUuid).withFlightToggle(true, false), false, false,
                bindingConfig(), "en-US");

        assertCommand(commands, "#TameworkLinkedPanelList[0] #FlightToggleButton.Visible", "true");
        assertCommand(commands, "#TameworkLinkedPanelList[0] #FlightToggleButton.Style", "FlightGrounded");
        assertCommand(commands, "#TameworkLinkedPanelList[0] #FlightModeAirborneIcon.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelList[0] #FlightToggleButton.TooltipText", "Switch to flight");
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event ->
                event.type == CustomUIEventBindingType.Activating
                        && "#TameworkLinkedPanelList[0] #FlightToggleButton".equals(event.selector)
                        && event.data.contains("__linked_flight_toggle__:" + npcUuid)));
    }

    @Test
    void unavailableOrUnlinkedNpcDoesNotExposeFlightControl() {
        for (LinkedNpcEntry entry : new LinkedNpcEntry[] {
                entry(UUID.randomUUID()),
                entry(UUID.randomUUID()).withFlightToggle(true, true).withRecoveryHold("pending")
        }) {
            UICommandBuilder commands = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            LinkedNpcPanelCardBinder.bind(commands, events, 0, entry, false,
                    entry.recoveryHeld(), bindingConfig(), "en-US");
            assertCommand(commands, "#TameworkLinkedPanelList[0] #FlightToggleButton.Visible", "false");
            assertFalse(Arrays.stream(events.getEvents()).anyMatch(event ->
                    "#TameworkLinkedPanelList[0] #FlightToggleButton".equals(event.selector)));
        }
    }

    @Test
    void flightStateRefreshChangesTheButtonGlyphWithoutLegacyOverlays() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry previous = entry(id).withFlightToggle(true, false);
        LinkedNpcEntry current = entry(id).withFlightToggle(true, true);
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardDynamicPresenter.refresh(commands, new UIEventBuilder(),
                "#Card", id, previous, current, null, null, false,
                bindingConfig(), "en-US");
        assertCommand(commands, "#Card #FlightToggleButton.Style", "FlightAirborne");
        assertCommand(commands, "#Card #FlightModeGroundedIcon.Visible", "false");
        assertCommand(commands, "#Card #FlightModeAirborneIcon.Visible", "false");
        assertCommand(commands, "#Card #FlightToggleButton.TooltipText", "Switch to ground");
    }

    private static LinkedNpcEntry entry(UUID npcUuid) {
        return new LinkedNpcEntry(npcUuid, "Nimbus", 100, 100, 0, 0,
                "", 0, 0, 0, 0, true, false, false, false, false,
                false, 0L, LinkedNpcTraitIndicator.EMPTY);
    }

    private static LinkedNpcPanelCardBinder.CardBindingConfig bindingConfig() {
        return new LinkedNpcPanelCardBinder.CardBindingConfig(
                "card.ui", "Command", "link:", "unlink:", "group:",
                "active:", "breed:", "release:", "cull:", "respawn:",
                "summon:", "dismiss:", "locate:", "recall:", "home:",
                "return:", "talents:", true, false);
    }

    private static LinkedNpcPanelCardBinder.CardBindingConfig rosterBindingConfig() {
        return new LinkedNpcPanelCardBinder.CardBindingConfig(
                "card.ui", "Command", "link:", "unlink:", "group:",
                "active:", "breed:", "release:", "cull:", "respawn:",
                "summon:", "dismiss:", "locate:", "recall:", "home:",
                "return:", "talents:", true, true);
    }

    private static void assertCommand(UICommandBuilder commands, String selector,
                                      String expected) {
        assertTrue(Arrays.stream(commands.getCommands()).anyMatch(command ->
                        selector.equals(command.selector)
                                && command.data.contains(expected)),
                () -> "Expected " + selector + " to contain " + expected);
    }
}
