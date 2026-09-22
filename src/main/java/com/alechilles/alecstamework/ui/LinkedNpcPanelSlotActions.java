package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Map;
import java.util.UUID;

/** Stable ordinary-card bindings read the displayed identity at click time. */
final class LinkedNpcPanelSlotActions {
    static final String PREFIX = "__card_slot__:";
    static final String TARGET_KEY = "@CardTarget";
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("#LinkButton", CommandSelectionPageEventBinder.LINK_COMMAND_PREFIX),
            Map.entry("#RemoveButton", CommandSelectionPageEventBinder.OPEN_REMOVAL_MENU_COMMAND_PREFIX),
            Map.entry("#UnlinkButton", CommandSelectionPageEventBinder.UNLINK_COMMAND_PREFIX),
            Map.entry("#ActiveToggleActiveButton", CommandSelectionPageEventBinder.TOGGLE_ACTIVE_COMMAND_PREFIX),
            Map.entry("#ActiveToggleInactiveButton", CommandSelectionPageEventBinder.TOGGLE_ACTIVE_COMMAND_PREFIX),
            Map.entry("#BreedingToggleEnabledButton", CommandSelectionPageEventBinder.TOGGLE_BREEDING_COMMAND_PREFIX),
            Map.entry("#BreedingToggleDisabledButton", CommandSelectionPageEventBinder.TOGGLE_BREEDING_COMMAND_PREFIX),
            Map.entry("#RespawnButton", CommandSelectionPageEventBinder.RESPAWN_COMMAND_PREFIX),
            Map.entry("#LocateButton", CommandSelectionPageEventBinder.LOCATE_COMMAND_PREFIX),
            Map.entry("#RecallButton", CommandSelectionPageEventBinder.RECALL_COMMAND_PREFIX),
            Map.entry("#SetHomeButton", CommandSelectionPageEventBinder.SET_HOME_COMMAND_PREFIX),
            Map.entry("#ReturnHomeButton", CommandSelectionPageEventBinder.RETURN_HOME_COMMAND_PREFIX),
            Map.entry("#ReleaseButton", CommandSelectionPageEventBinder.RELEASE_COMMAND_PREFIX),
            Map.entry("#CullButton", CommandSelectionPageEventBinder.CULL_COMMAND_PREFIX),
            Map.entry("#FlightToggleButton", CommandSelectionPageEventBinder.LINKED_FLIGHT_TOGGLE_COMMAND_PREFIX),
            Map.entry("#ShoulderRideButton", CommandSelectionPageEventBinder.LINKED_SHOULDER_RIDE_COMMAND_PREFIX),
            Map.entry("#XpProgressRing #XpTooltip", CommandSelectionPageEventBinder.OPEN_TALENTS_COMMAND_PREFIX),
            Map.entry("#TalentPointAction #TalentPointButton", CommandSelectionPageEventBinder.OPEN_TALENTS_COMMAND_PREFIX),
            Map.entry("#InlineLocation #CopyButton", LinkedNpcLocationCopyControl.PREFIX),
            Map.entry("#GroupSelector", CommandSelectionPageEventBinder.ASSIGN_GROUP_COMMAND_PREFIX));

    // The ordinary presenter still describes conditional events for legacy/managed pages.
    // Reused slots instead install the complete stable binding set exactly once.
    static final UIEventBuilder IGNORE_EVENTS = new UIEventBuilder() {
        @Override
        public UIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector,
                                              EventData data, boolean locksInterface) {
            return this;
        }
    };

    private LinkedNpcPanelSlotActions() { }

    static void bind(UIEventBuilder events, String card) {
        ACTIONS.forEach((selector, prefix) -> {
            EventData data = EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID, PREFIX + selector)
                    .append(TARGET_KEY, card + " #CardTarget.Value");
            boolean group = selector.equals("#GroupSelector");
            if (group) data.append("@CompanionGroups", card + " #GroupSelector.SelectedValues");
            events.addEventBinding(group ? CustomUIEventBindingType.ValueChanged
                    : CustomUIEventBindingType.Activating, card + " " + selector, data, false);
        });
    }

    /** A stale click keeps its old UUID and is rejected, never redirected to the new slot. */
    static String resolve(String command, String target, LinkedNpcEntry[] visible) {
        if (command == null || !command.startsWith(PREFIX) || target == null) return null;
        String action = ACTIONS.get(command.substring(PREFIX.length()));
        if (action == null) return null;
        UUID id;
        try { id = UUID.fromString(target); }
        catch (IllegalArgumentException ignored) { return null; }
        for (LinkedNpcEntry entry : visible) {
            if (id.equals(entry.npcUuid())) return action + id;
        }
        return null;
    }
}
