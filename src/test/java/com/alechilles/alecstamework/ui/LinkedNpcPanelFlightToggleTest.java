package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertCommand(commands, "#TameworkLinkedPanelList[0] #ShoulderRideIcon.Visible", "true");
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
        assertCommand(commands, "#TameworkLinkedPanelList[0] #FlightModeGroundedIcon.Visible", "true");
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
    void cardAssetIncludesTheSharedFlightIcons() throws Exception {
        String asset = Files.readString(Path.of("src", "main", "resources", "Common",
                "UI", "Custom", "TameworkLinkedNpcPanelCard.ui"), StandardCharsets.UTF_8);
        assertTrue(asset.contains("#FlightToggleButton"));
        assertTrue(asset.contains("#FlightModeGroundedIcon"));
        assertTrue(asset.contains("#FlightModeAirborneIcon"));
        assertTrue(asset.contains("Tamework/LinkedPanelIcons/FlightMode_Grounded.png"));
        assertTrue(asset.contains("Tamework/LinkedPanelIcons/FlightMode_Airborne.png"));
    }

    @Test
    void normalCardsStayCompactWhileOwnerRosterCardsKeepTheirDetailsLane() {
        UICommandBuilder normalCommands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(normalCommands, new UIEventBuilder(), 0,
                entry(UUID.randomUUID()), false, false, bindingConfig(), "en-US");

        UICommandBuilder rosterCommands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(rosterCommands, new UIEventBuilder(), 0,
                entry(UUID.randomUUID()), false, false, rosterBindingConfig(), "en-US");

        assertCommand(normalCommands, "#TameworkLinkedPanelList[0].Anchor", "88");
        assertCommand(rosterCommands, "#TameworkLinkedPanelList[0].Anchor", "126");
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
