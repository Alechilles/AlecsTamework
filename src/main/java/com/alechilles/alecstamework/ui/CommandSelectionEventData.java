package com.alechilles.alecstamework.ui;

import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.EVENT_COMMAND_ID;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_AUTO_LINK_ENABLED;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_FILTER_MODE_VALUE;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_FILTER_TEXT_INPUT;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ACTIVE_VALUE;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ASSIGN_VALUE;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_MODE_VALUE;
import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.KEY_PANEL_SORT_VALUE;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** Decoded, untrusted event payload received from the command selection page. */
public final class CommandSelectionEventData {
    public static final BuilderCodec<CommandSelectionEventData> CODEC =
            BuilderCodec.builder(
                    CommandSelectionEventData.class,
                    CommandSelectionEventData::new)
            .<String>append(
                    new KeyedCodec<>(EVENT_COMMAND_ID, Codec.STRING),
                    (event, value) -> event.commandId = value,
                    event -> event.commandId)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_MODE_VALUE, Codec.STRING),
                    (event, value) -> event.panelModeValue = value,
                    event -> event.panelModeValue)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_AUTO_LINK_ENABLED, Codec.BOOLEAN),
                    (event, value) -> event.panelAutoLinkEnabled = value,
                    event -> event.panelAutoLinkEnabled)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_SORT_VALUE, Codec.STRING),
                    (event, value) -> event.panelSortValue = value,
                    event -> event.panelSortValue)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_FILTER_MODE_VALUE, Codec.STRING),
                    (event, value) -> event.panelFilterModeValue = value,
                    event -> event.panelFilterModeValue)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_FILTER_TEXT_INPUT, Codec.STRING),
                    (event, value) -> event.panelFilterTextInput = value,
                    event -> event.panelFilterTextInput)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_GROUP_ACTIVE_VALUE, Codec.STRING),
                    (event, value) -> event.panelGroupActiveValue = value,
                    event -> event.panelGroupActiveValue)
            .add()
            .append(
                    new KeyedCodec<>(KEY_PANEL_GROUP_ASSIGN_VALUE, Codec.STRING),
                    (event, value) -> event.panelGroupAssignValue = value,
                    event -> event.panelGroupAssignValue)
            .add()
            .build();

    String commandId;
    String panelModeValue;
    Boolean panelAutoLinkEnabled;
    String panelSortValue;
    String panelFilterModeValue;
    String panelFilterTextInput;
    String panelGroupActiveValue;
    String panelGroupAssignValue;
}
