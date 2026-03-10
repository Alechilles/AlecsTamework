package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.List;
import java.util.Locale;

/**
 * Binds one linked-panel NPC card including visual state and per-row interaction handlers.
 */
final class LinkedNpcPanelCardBinder {
    private LinkedNpcPanelCardBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     int index,
                     LinkedNpcEntry entry,
                     boolean appendCard,
                     boolean pendingUnlink,
                     boolean showGroupPicker,
                     List<LinkedNpcGroupPickerOption> groupPickerOptions,
                     String selectedGroupValue,
                     CardBindingConfig config) {
        String entrySelector = "#TameworkLinkedPanelList[" + index + "]";
        String nameSelector = entrySelector + " #Name";
        String statusUnloadedSelector = entrySelector + " #StatusUnloaded";
        String statusConfirmSelector = entrySelector + " #StatusConfirm";
        String secondaryStatFrameSelector = entrySelector + " #FutureStatAFrame";
        String tertiaryStatFrameSelector = entrySelector + " #FutureStatBFrame";
        String futureActionBarSelector = entrySelector + " #FutureActionBar";
        String traitsButtonSelector = entrySelector + " #TraitsButton";
        String talentsButtonSelector = entrySelector + " #TalentsButton";
        String linkSelector = entrySelector + " #LinkButton";
        String removeSelector = entrySelector + " #RemoveButton";
        String activeToggleActiveSelector = entrySelector + " #ActiveToggleActiveButton";
        String activeToggleInactiveSelector = entrySelector + " #ActiveToggleInactiveButton";
        String inactiveBadgeSelector = entrySelector + " #StatusInactive";
        String groupTabSelector = entrySelector + " #GroupTab";
        String groupTabButtonSelector = entrySelector + " #GroupTabButton";
        String groupPickerContainerSelector = entrySelector + " #GroupPickerContainer";
        String groupPickerListSelector = entrySelector + " #GroupPickerList";
        String respawnSelector = entrySelector + " #RespawnButton";
        String recallSelector = entrySelector + " #RecallButton";
        String setHomeSelector = entrySelector + " #SetHomeButton";
        String returnHomeSelector = entrySelector + " #ReturnHomeButton";

        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", config.linkedPanelCardUiPath());
        }
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        boolean isLinked = entry.linked();
        boolean showRespawn = isLinked && entry.dead() && entry.deadRespawnRemainingMs() == 0L && !pendingUnlink;
        boolean showRecall = isLinked && !entry.dead() && !entry.captured() && !pendingUnlink;
        boolean showSetHome = isLinked && entry.loaded() && !entry.dead() && !entry.captured() && !pendingUnlink;
        boolean showReturnHome = isLinked && !entry.dead() && !entry.captured() && entry.hasHome() && !pendingUnlink;
        boolean showLink = !isLinked;
        boolean showUnlink = isLinked;
        boolean showActiveToggleActive = isLinked && entry.active() && !pendingUnlink;
        boolean showActiveToggleInactive = isLinked && !entry.active() && !pendingUnlink;
        boolean showInactiveBadge = isLinked && !entry.active() && !pendingUnlink;

        commandBuilder.set(statusUnloadedSelector + ".Visible", !entry.loaded() && !pendingUnlink && !showRespawn);
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry));
        commandBuilder.set(statusConfirmSelector + ".Visible", pendingUnlink);
        commandBuilder.set(linkSelector + ".Visible", showLink);
        commandBuilder.set(removeSelector + ".Visible", showUnlink);
        commandBuilder.set(activeToggleActiveSelector + ".Visible", showActiveToggleActive);
        commandBuilder.set(activeToggleInactiveSelector + ".Visible", showActiveToggleInactive);
        commandBuilder.set(inactiveBadgeSelector + ".Visible", showInactiveBadge);
        LinkedNpcPanelGroupTabBinder.bind(
                commandBuilder,
                groupTabSelector,
                entry,
                pendingUnlink
        );
        boolean showGroupPickerContainer = !pendingUnlink && showGroupPicker;
        commandBuilder.set(groupPickerContainerSelector + ".Visible", showGroupPickerContainer);
        commandBuilder.clear(groupPickerListSelector);
        if (showGroupPickerContainer) {
            bindGroupPickerOptions(
                    commandBuilder,
                    eventBuilder,
                    entry,
                    groupPickerListSelector,
                    groupPickerOptions,
                    selectedGroupValue,
                    config
            );
        }
        LinkedNpcPanelVitalsBinder.bind(commandBuilder, entrySelector, entry);
        commandBuilder.set(secondaryStatFrameSelector + ".Visible", entry.hasFutureStatA());
        commandBuilder.set(tertiaryStatFrameSelector + ".Visible", entry.hasFutureStatB());
        commandBuilder.set(futureActionBarSelector + ".Visible", entry.hasAnyFutureAction());
        commandBuilder.set(traitsButtonSelector + ".Visible", entry.isTraitsActionVisible());
        commandBuilder.set(talentsButtonSelector + ".Visible", entry.isTalentsActionVisible());
        commandBuilder.set(respawnSelector + ".Visible", showRespawn);
        commandBuilder.set(recallSelector + ".Visible", showRecall);
        commandBuilder.set(setHomeSelector + ".Visible", showSetHome);
        commandBuilder.set(returnHomeSelector + ".Visible", showReturnHome);
        LinkedNpcTraitIndicatorBinder.bind(commandBuilder, entrySelector, entry.traitIndicators());

        if (showLink) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    linkSelector,
                    EventData.of(config.eventCommandId(), config.linkCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showUnlink) {
            commandBuilder.set(removeSelector + ".Text", "");
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    removeSelector,
                    EventData.of(config.eventCommandId(), config.unlinkCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showActiveToggleActive) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    activeToggleActiveSelector,
                    EventData.of(config.eventCommandId(), config.toggleActiveCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showActiveToggleInactive) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    activeToggleInactiveSelector,
                    EventData.of(config.eventCommandId(), config.toggleActiveCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (!pendingUnlink) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    groupTabButtonSelector,
                    EventData.of(config.eventCommandId(), config.openGroupPickerCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showRespawn) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    respawnSelector,
                    EventData.of(config.eventCommandId(), config.respawnCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showRecall) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    recallSelector,
                    EventData.of(config.eventCommandId(), config.recallCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showSetHome) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    setHomeSelector,
                    EventData.of(config.eventCommandId(), config.setHomeCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showReturnHome) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    returnHomeSelector,
                    EventData.of(config.eventCommandId(), config.returnHomeCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
    }

    private static void bindGroupPickerOptions(UICommandBuilder commandBuilder,
                                               UIEventBuilder eventBuilder,
                                               LinkedNpcEntry entry,
                                               String groupPickerListSelector,
                                               List<LinkedNpcGroupPickerOption> groupPickerOptions,
                                               String selectedGroupValue,
                                               CardBindingConfig config) {
        if (entry == null || groupPickerListSelector == null || config == null) {
            return;
        }
        if (groupPickerOptions == null || groupPickerOptions.isEmpty()) {
            return;
        }
        String normalizedSelectedValue = normalizeOptionValue(selectedGroupValue);
        int visibleCount = 0;
        for (LinkedNpcGroupPickerOption option : groupPickerOptions) {
            if (option == null) {
                continue;
            }
            String optionValue = normalizeOptionValue(option.value());
            if (optionValue == null) {
                continue;
            }
            String optionLabel = resolveOptionLabel(option, optionValue);
            boolean selected = normalizedSelectedValue != null
                    && normalizedSelectedValue.equalsIgnoreCase(optionValue);
            commandBuilder.append(groupPickerListSelector, config.groupPickerOptionRowUiPath());
            String optionSelector = groupPickerListSelector + "[" + visibleCount + "]";
            commandBuilder.set(optionSelector + " #OptionColor.Background", normalizeColor(option.colorHex()));
            commandBuilder.set(optionSelector + " #OptionButton.Text", selected ? "• " + optionLabel : optionLabel);
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    optionSelector + " #OptionButton",
                    EventData.of(config.eventCommandId(), config.setGroupCommandPrefix() + entry.npcUuid())
                            .append(config.keyCardGroupValue(), optionValue),
                    false
            );
            visibleCount++;
        }
    }

    private static String resolveOptionLabel(LinkedNpcGroupPickerOption option, String optionValue) {
        if (option == null) {
            return optionValue == null ? "Group" : optionValue;
        }
        String label = option.label();
        if (label == null || label.isBlank()) {
            return optionValue == null ? "Group" : optionValue;
        }
        return label.trim();
    }

    private static String normalizeOptionValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return "#4B657F";
        }
        String trimmed = raw.trim();
        if (!trimmed.matches("^#[0-9A-Fa-f]{6}$")) {
            return "#4B657F";
        }
        return "#" + trimmed.substring(1).toUpperCase(Locale.ROOT);
    }

    record CardBindingConfig(String linkedPanelCardUiPath,
                             String groupPickerOptionRowUiPath,
                             String eventCommandId,
                             String keyCardGroupValue,
                             String linkCommandPrefix,
                             String unlinkCommandPrefix,
                             String openGroupPickerCommandPrefix,
                             String setGroupCommandPrefix,
                             String toggleActiveCommandPrefix,
                             String respawnCommandPrefix,
                             String recallCommandPrefix,
                             String setHomeCommandPrefix,
                             String returnHomeCommandPrefix) {
    }
}
