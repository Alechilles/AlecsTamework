package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * Binds one linked-panel NPC card including visual state and per-row interaction handlers.
 */
final class LinkedNpcPanelCardBinder {
    private static final int CARD_HEIGHT = 92;

    private LinkedNpcPanelCardBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     int index,
                     LinkedNpcEntry entry,
                     boolean appendCard,
                     boolean pendingUnlink,
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
        String breedingToggleEnabledSelector = entrySelector + " #BreedingToggleEnabledButton";
        String breedingToggleDisabledSelector = entrySelector + " #BreedingToggleDisabledButton";
        String inactiveBadgeSelector = entrySelector + " #StatusInactive";
        String groupTabSelector = entrySelector + " #GroupTab";
        String groupTabButtonSelector = entrySelector + " #GroupTabButton";
        String respawnSelector = entrySelector + " #RespawnButton";
        String recallSelector = entrySelector + " #RecallButton";
        String setHomeSelector = entrySelector + " #SetHomeButton";
        String returnHomeSelector = entrySelector + " #ReturnHomeButton";
        String releaseSelector = entrySelector + " #ReleaseButton";
        String cullSelector = entrySelector + " #CullButton";

        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", config.linkedPanelCardUiPath());
        }
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        boolean isLinked = entry.linked();
        boolean showRespawn = isLinked
                && (entry.dead() || entry.lost())
                && entry.deadRespawnRemainingMs() == 0L
                && !pendingUnlink;
        boolean showRecall = isLinked
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        boolean showSetHome = isLinked
                && entry.loaded()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        boolean showReturnHome =
                isLinked
                        && !entry.dead()
                        && !entry.captured()
                        && !entry.inCoop()
                        && !entry.lost()
                        && entry.hasHome()
                        && !pendingUnlink;
        boolean canOpenReleaseActions =
                !isLinked && entry.loaded() && !entry.dead() && !entry.captured() && !entry.inCoop() && !entry.lost();
        boolean showLink = !isLinked && !pendingUnlink;
        boolean showUnlink = isLinked || canOpenReleaseActions;
        boolean showRelease = pendingUnlink && canOpenReleaseActions;
        boolean showCull = pendingUnlink && canOpenReleaseActions;
        boolean showActiveToggleActive = isLinked && entry.active() && !pendingUnlink;
        boolean showActiveToggleInactive = isLinked && !entry.active() && !pendingUnlink;
        boolean showBreedingToggleEnabled =
                isLinked && entry.loaded() && entry.breedingCooldownKnown() && entry.breedingEnabled() && !pendingUnlink;
        boolean showBreedingToggleDisabled =
                isLinked && entry.loaded() && entry.breedingCooldownKnown() && !entry.breedingEnabled() && !pendingUnlink;
        boolean showInactiveBadge = isLinked && !entry.active() && !pendingUnlink;

        commandBuilder.set(statusUnloadedSelector + ".Visible", !entry.loaded() && !pendingUnlink && !showRespawn);
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry));
        commandBuilder.set(statusConfirmSelector + ".Visible", pendingUnlink);
        commandBuilder.set(statusConfirmSelector + ".Text", isLinked ? "CONFIRM REMOVE" : "RELEASE OR CULL");
        commandBuilder.set(linkSelector + ".Visible", showLink);
        commandBuilder.set(removeSelector + ".Visible", showUnlink);
        commandBuilder.set(activeToggleActiveSelector + ".Visible", showActiveToggleActive);
        commandBuilder.set(activeToggleInactiveSelector + ".Visible", showActiveToggleInactive);
        commandBuilder.set(breedingToggleEnabledSelector + ".Visible", showBreedingToggleEnabled);
        commandBuilder.set(breedingToggleDisabledSelector + ".Visible", showBreedingToggleDisabled);
        commandBuilder.set(inactiveBadgeSelector + ".Visible", showInactiveBadge);
        LinkedNpcPanelGroupTabBinder.bind(
                commandBuilder,
                groupTabSelector,
                entry,
                pendingUnlink
        );
        commandBuilder.setObject(entrySelector + ".Anchor", buildCardAnchor());
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
        commandBuilder.set(releaseSelector + ".Visible", showRelease);
        commandBuilder.set(cullSelector + ".Visible", showCull);
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
        if (showBreedingToggleEnabled) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    breedingToggleEnabledSelector,
                    EventData.of(config.eventCommandId(), config.toggleBreedingCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showBreedingToggleDisabled) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    breedingToggleDisabledSelector,
                    EventData.of(config.eventCommandId(), config.toggleBreedingCommandPrefix() + entry.npcUuid()),
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
        if (showRelease) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    releaseSelector,
                    EventData.of(config.eventCommandId(), config.releaseCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showCull) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    cullSelector,
                    EventData.of(config.eventCommandId(), config.cullCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
    }

    private static Anchor buildCardAnchor() {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(3));
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(0));
        anchor.setHeight(Value.of(CARD_HEIGHT));
        return anchor;
    }

    record CardBindingConfig(String linkedPanelCardUiPath,
                             String eventCommandId,
                             String linkCommandPrefix,
                             String unlinkCommandPrefix,
                             String openGroupPickerCommandPrefix,
                             String toggleActiveCommandPrefix,
                             String toggleBreedingCommandPrefix,
                             String releaseCommandPrefix,
                             String cullCommandPrefix,
                             String respawnCommandPrefix,
                             String recallCommandPrefix,
                             String setHomeCommandPrefix,
                             String returnHomeCommandPrefix) {
    }
}
