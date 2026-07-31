package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import javax.annotation.Nonnull;

/**
 * Owns command-selection UI event identifiers and their selector bindings.
 */
final class CommandSelectionPageEventBinder {
    static final String EVENT_COMMAND_ID = "CommandId";
    static final String KEY_PANEL_MODE_VALUE = "@PanelModeValue";
    static final String KEY_PANEL_AUTO_LINK_ENABLED =
            "@PanelAutoLinkEnabled";
    static final String KEY_PANEL_SORT_VALUE = "@PanelSortValue";
    static final String KEY_PANEL_FILTER_MODE_VALUE =
            "@PanelFilterModeValue";
    static final String KEY_PANEL_FILTER_TEXT_INPUT =
            "@PanelFilterTextInput";
    static final String KEY_PANEL_GROUP_ACTIVE_VALUE =
            "@PanelGroupActiveValue";
    static final String KEY_PANEL_GROUP_ASSIGN_VALUE =
            "@PanelGroupAssignValue";
    static final String CLOSE_COMMAND_ID = "__close__";
    static final String LINK_COMMAND_PREFIX = "__link__:";
    static final String UNLINK_COMMAND_PREFIX = "__unlink__:";
    static final String OPEN_GROUP_PICKER_COMMAND_PREFIX =
            "__opengroup__:";
    static final String TOGGLE_ACTIVE_COMMAND_PREFIX = "__active__:";
    static final String TOGGLE_BREEDING_COMMAND_PREFIX =
            "__breeding__:";
    static final String RELEASE_COMMAND_PREFIX = "__release__:";
    static final String CULL_COMMAND_PREFIX = "__cull__:";
    static final String RESPAWN_COMMAND_PREFIX = "__respawn__:";
    static final String LOCATE_COMMAND_PREFIX = "__locate__:";
    static final String RECALL_COMMAND_PREFIX = "__recall__:";
    static final String SET_HOME_COMMAND_PREFIX = "__sethome__:";
    static final String RETURN_HOME_COMMAND_PREFIX = "__returnhome__:";
    static final String OPEN_TALENTS_COMMAND_PREFIX = "__talents__:";
    static final String BONDED_FLIGHT_TOGGLE_COMMAND_PREFIX =
            "__bonded_flight_toggle__:";
    static final String PANEL_RADIUS_DECREASE_COMMAND_ID =
            "__panel_radius_dec__";
    static final String PANEL_RADIUS_INCREASE_COMMAND_ID =
            "__panel_radius_inc__";
    static final String PANEL_MANAGE_GROUPS_COMMAND_ID =
            "__panel_manage_groups__";
    static final String PANEL_FILTER_CLEAR_COMMAND_ID =
            "__panel_filter_clear__";
    static final String PANEL_GROUP_ASSIGN_APPLY_COMMAND_ID =
            "__panel_group_assign_apply__";
    static final String PANEL_GROUP_ASSIGN_CANCEL_COMMAND_ID =
            "__panel_group_assign_cancel__";

    private CommandSelectionPageEventBinder() {
    }

    static void bindOptionEvents(
            @Nonnull UIEventBuilder events,
            @Nonnull CommandSelectionOptionSource.Option[] options,
            int maximumOptions
    ) {
        int boundCount = Math.min(options.length, maximumOptions);
        for (int index = 0; index < boundCount; index++) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#CommandButton" + index,
                    EventData.of(EVENT_COMMAND_ID, options[index].id()),
                    false
            );
        }
    }

    static void bindPanelControls(
            @Nonnull UIEventBuilder events,
            @Nonnull LinkedNpcPanelFeatureController featureController
    ) {
        bindValue(
                events,
                "#TameworkLinkedPanelAutoLinkCheck",
                KEY_PANEL_AUTO_LINK_ENABLED
        );
        bindValue(
                events,
                "#TameworkLinkedPanelModeDropdown",
                KEY_PANEL_MODE_VALUE
        );
        bindAction(
                events,
                "#TameworkLinkedPanelRadiusDec",
                PANEL_RADIUS_DECREASE_COMMAND_ID
        );
        bindAction(
                events,
                "#TameworkLinkedPanelRadiusInc",
                PANEL_RADIUS_INCREASE_COMMAND_ID
        );
        bindAction(
                events,
                "#TameworkLinkedPanelManageGroupsButton",
                PANEL_MANAGE_GROUPS_COMMAND_ID
        );
        bindValue(
                events,
                "#TameworkLinkedPanelSortDropdown",
                KEY_PANEL_SORT_VALUE
        );
        bindValue(
                events,
                "#TameworkLinkedPanelFilterDropdown",
                KEY_PANEL_FILTER_MODE_VALUE
        );
        bindValue(
                events,
                "#TameworkLinkedPanelFilterInput",
                KEY_PANEL_FILTER_TEXT_INPUT
        );
        bindValue(
                events,
                "#TameworkLinkedPanelGroupSelectorDropdown",
                KEY_PANEL_GROUP_ACTIVE_VALUE
        );
        bindValue(
                events,
                "#TameworkLinkedPanelGroupAssignDropdown",
                KEY_PANEL_GROUP_ASSIGN_VALUE
        );
        bindAction(
                events,
                "#TameworkLinkedPanelGroupAssignCancelButton",
                PANEL_GROUP_ASSIGN_CANCEL_COMMAND_ID
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelGroupAssignApplyButton",
                EventData.of(
                        EVENT_COMMAND_ID,
                        PANEL_GROUP_ASSIGN_APPLY_COMMAND_ID
                ).append(
                        KEY_PANEL_GROUP_ASSIGN_VALUE,
                        "#TameworkLinkedPanelGroupAssignDropdown.Value"
                ),
                false
        );
        featureController.bindEvents(events, EVENT_COMMAND_ID);
    }

    static void bindClose(@Nonnull UIEventBuilder events) {
        bindAction(
                events, "#CommandMenuCloseButton", CLOSE_COMMAND_ID
        );
    }

    private static void bindAction(
            UIEventBuilder events,
            String selector,
            String commandId
    ) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector,
                EventData.of(EVENT_COMMAND_ID, commandId),
                false
        );
    }

    private static void bindValue(
            UIEventBuilder events,
            String selector,
            String key
    ) {
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                selector,
                EventData.of(key, selector + ".Value"),
                false
        );
    }
}
