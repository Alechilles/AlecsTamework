package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
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
    private static final int NORMAL_CARD_HEIGHT = 252;
    private static final int ROSTER_CARD_HEIGHT = 282;

    static void bindBreedingTooltips(UICommandBuilder commands, String selector,
                                     LinkedNpcEntry entry, String language) {
        String requirement = entry.hasHappiness() && entry.breedingHappinessRatio() > 0.0
                ? "\n" + LocalizedText.format(language,
                        "tamework.ui.linkedPanel.card.tooltip.breedingRequirement",
                        Math.round(entry.breedingHappinessRatio() * entry.maxHappiness()))
                : "";
        commands.set(selector + " #BreedingToggleEnabledButton.TooltipText",
                LocalizedText.resolve(language,
                        "tamework.ui.linkedPanel.card.tooltip.breedingEnabled") + requirement);
        commands.set(selector + " #BreedingToggleDisabledButton.TooltipText",
                LocalizedText.resolve(language,
                        "tamework.ui.linkedPanel.card.tooltip.breedingDisabled") + requirement);
    }

    private LinkedNpcPanelCardBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     int index,
                     LinkedNpcEntry entry,
                     boolean appendCard,
                     boolean pendingUnlink,
                     CardBindingConfig config) {
        bind(commandBuilder, eventBuilder, index, entry, appendCard, pendingUnlink, config, null);
    }

    static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     int index,
                     LinkedNpcEntry entry,
                     boolean appendCard,
                     boolean pendingUnlink,
                     CardBindingConfig config,
                     String language) {
        bind(
                commandBuilder,
                eventBuilder,
                index,
                entry,
                appendCard,
                pendingUnlink,
                config,
                language,
                null
        );
    }

    static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     int index,
                     LinkedNpcEntry entry,
                     boolean appendCard,
                     boolean pendingUnlink,
                     CardBindingConfig config,
                     String language,
                     CommandPanelFeaturePresentation feature) {
        String entrySelector = "#TameworkLinkedPanelList[" + index + "]";
        if (feature != null && feature.bonded() != null) {
            if (appendCard) {
                commandBuilder.append("#TameworkLinkedPanelList",
                        BondedCompanionCardPresenter.CARD_UI_PATH);
            }
            BondedCompanionCardPresenter.bind(
                    commandBuilder, eventBuilder, entrySelector,
                    entry.npcUuid(), feature.bonded(), pendingUnlink, config,
                    language
            );
            return;
        }
        String nameSelector = entrySelector + " #Name";
        String maleIconSelector = entrySelector + " #GenderMaleIcon";
        String femaleIconSelector = entrySelector + " #GenderFemaleIcon";
        String statusUnloadedSelector = entrySelector + " #StatusUnloaded";
        String recallCountdownSelector = entrySelector + " #RecallCountdown";
        String statusConfirmSelector = entrySelector + " #StatusConfirm";
        String xpProgressRingSelector = entrySelector + " #XpProgressRing";
        String xpLevelTextSelector = xpProgressRingSelector + " #XpLevelText";
        String xpTooltipSelector = xpProgressRingSelector + " #XpTooltip";
        String talentPointActionSelector = entrySelector + " #TalentPointAction";
        String talentPointCountSelector = talentPointActionSelector + " #TalentPointCount";
        String talentPointCountShadowSelector = talentPointActionSelector + " #TalentPointCountShadow";
        String talentPointButtonSelector = talentPointActionSelector + " #TalentPointButton";
        String linkSelector = entrySelector + " #LinkButton";
        String removeSelector = entrySelector + " #RemoveButton";
        String unlinkSelector = entrySelector + " #UnlinkButton";
        String unlinkDisabledSelector = entrySelector + " #UnlinkButtonDisabled";
        String activeToggleActiveSelector = entrySelector + " #ActiveToggleActiveButton";
        String activeToggleInactiveSelector = entrySelector + " #ActiveToggleInactiveButton";
        String breedingToggleEnabledSelector = entrySelector + " #BreedingToggleEnabledButton";
        String breedingToggleDisabledSelector = entrySelector + " #BreedingToggleDisabledButton";
        String inactiveBadgeSelector = entrySelector + " #StatusInactive";
        String groupTabSelector = entrySelector + " #GroupTab";
        String groupTabButtonSelector = entrySelector + " #GroupTabButton";
        String respawnSelector = entrySelector + " #RespawnButton";
        String locateSelector = entrySelector + " #LocateButton";
        String recallSelector = entrySelector + " #RecallButton";
        String setHomeSelector = entrySelector + " #SetHomeButton";
        String returnHomeSelector = entrySelector + " #ReturnHomeButton";
        String flightToggleSelector = entrySelector + " #FlightToggleButton";
        String shoulderRideSelector = entrySelector + " #ShoulderRideButton";
        String shoulderRideIconSelector = entrySelector + " #ShoulderRideIcon";
        String flightModeGroundedSelector = entrySelector + " #FlightModeGroundedIcon";
        String flightModeAirborneSelector = entrySelector + " #FlightModeAirborneIcon";
        String releaseSelector = entrySelector + " #ReleaseButton";
        String releaseDisabledSelector = entrySelector + " #ReleaseButtonDisabled";
        String cullSelector = entrySelector + " #CullButton";

        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", config.linkedPanelCardUiPath());
        }
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        commandBuilder.set(maleIconSelector + ".Visible", entry.isMale());
        commandBuilder.set(femaleIconSelector + ".Visible", entry.isFemale());
        boolean isLinked = entry.linked();
        // Feature-managed roster membership is canonical. Its synthetic card
        // identity must never fall through to legacy per-item link actions.
        boolean managedRoster = config.ownerCommandFamilyRoster()
                || feature != null && feature.managesRosterRow();
        boolean legacyLinked = isLinked && !managedRoster;
        boolean genericLinkedOrOwned = !managedRoster
                && (legacyLinked || entry.ownedActions());
        boolean paidRevivalManaged = feature != null
                && feature.managesPaidRevival();
        boolean showReviveAction = !paidRevivalManaged && genericLinkedOrOwned
                && (entry.dead() || entry.lost())
                && entry.deadRespawnRemainingMs() == 0L
                && !pendingUnlink;
        boolean showLocate = genericLinkedOrOwned
                && !entry.dead() && !entry.lost() && !pendingUnlink
                && (legacyLinked || !entry.captured() && !entry.inCoop());
        boolean showRecall = genericLinkedOrOwned
                && config.recallActionEnabled()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        boolean showSetHome = legacyLinked
                && entry.loaded()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        boolean showReturnHome =
                legacyLinked
                        && !entry.dead()
                        && !entry.captured()
                        && !entry.inCoop()
                        && !entry.lost()
                        && entry.hasHome()
                        && !pendingUnlink;
        boolean removalMenuAvailable = !managedRoster;
        boolean canRelease = removalMenuAvailable && !entry.captured() && !entry.inCoop();
        boolean canCull = canRelease && entry.loaded() && !entry.dead() && !entry.lost();
        boolean showLink = !legacyLinked && !managedRoster && entry.loaded()
                && !entry.dead() && !entry.captured() && !entry.inCoop() && !entry.lost() && !pendingUnlink;
        boolean showRemovalMenu = removalMenuAvailable && pendingUnlink;
        boolean showUnlink = showRemovalMenu && legacyLinked;
        boolean showUnlinkDisabled = showRemovalMenu && !legacyLinked;
        boolean showRelease = showRemovalMenu && canRelease;
        boolean showReleaseDisabled = showRemovalMenu && !canRelease;
        boolean showCull = showRemovalMenu && canCull;
        boolean showActiveToggleActive = legacyLinked && entry.active() && !pendingUnlink;
        boolean showActiveToggleInactive = legacyLinked && !entry.active() && !pendingUnlink;
        boolean showBreedingToggleEnabled =
                legacyLinked && entry.loaded() && entry.breedingAvailable() && entry.breedingEnabled() && !pendingUnlink;
        boolean showBreedingToggleDisabled =
                legacyLinked && entry.loaded() && entry.breedingAvailable() && !entry.breedingEnabled() && !pendingUnlink;
        boolean showFlightToggle = legacyLinked && entry.loaded()
                && entry.flightToggleAvailable() && !pendingUnlink;
        boolean showShoulderRide = legacyLinked && entry.loaded()
                && entry.shoulderRideAvailable() && !pendingUnlink;
        boolean showRespawn = paidRevivalManaged
                ? LinkedNpcPanelFeatureBinder.paidReviveVisible(feature)
                : showReviveAction;
        boolean showInactiveBadge = legacyLinked && !entry.active()
                && !showRespawn && !pendingUnlink;
        boolean showRecallCountdown = legacyLinked
                && entry.recallPending()
                && !entry.loaded()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        commandBuilder.set(
                statusUnloadedSelector + ".Visible",
                !entry.loaded() && !pendingUnlink && !showRespawn
        );
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry, language));
        commandBuilder.set(recallCountdownSelector + ".Visible", showRecallCountdown);
        commandBuilder.set(
                recallCountdownSelector + ".Text",
                LocalizedText.format(
                        language,
                        "tamework.ui.linkedPanel.card.recallCountdown",
                        (entry.recallLostRemainingMs() + 999L) / 1000L
                )
        );
        commandBuilder.set(statusConfirmSelector + ".Visible", pendingUnlink);
        commandBuilder.set(
                statusConfirmSelector + ".Text",
                LocalizedText.resolve(language, "tamework.ui.linkedPanel.card.releaseOrCull")
        );
        commandBuilder.set(linkSelector + ".Visible", showLink);
        LinkedNpcPanelIconStyles.visible(commandBuilder, removeSelector, removalMenuAvailable);
        LinkedNpcPanelIconStyles.visible(commandBuilder, unlinkSelector, showUnlink);
        LinkedNpcPanelIconStyles.visible(commandBuilder, unlinkDisabledSelector, showUnlinkDisabled);
        commandBuilder.set(activeToggleActiveSelector + ".Visible", showActiveToggleActive);
        commandBuilder.set(activeToggleInactiveSelector + ".Visible", showActiveToggleInactive);
        LinkedNpcPanelIconStyles.visible(commandBuilder, breedingToggleEnabledSelector, showBreedingToggleEnabled);
        LinkedNpcPanelIconStyles.visible(commandBuilder, breedingToggleDisabledSelector, showBreedingToggleDisabled);
        bindBreedingTooltips(commandBuilder, entrySelector, entry, language);
        LinkedNpcPanelIconStyles.visible(commandBuilder, flightToggleSelector, showFlightToggle);
        commandBuilder.set(flightModeGroundedSelector + ".Visible",
                showFlightToggle && !entry.flightToggleAirborne());
        commandBuilder.set(flightModeAirborneSelector + ".Visible",
                showFlightToggle && entry.flightToggleAirborne());
        commandBuilder.set(flightToggleSelector + ".TooltipText", showFlightToggle
                ? LocalizedText.resolve(language, entry.flightToggleAirborne()
                        ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                        : "tamework.ui.linkedPanel.bonded.flight.switchToFlight")
                : "");
        LinkedNpcPanelIconStyles.visible(commandBuilder, shoulderRideSelector, showShoulderRide);
        commandBuilder.set(shoulderRideIconSelector + ".Visible", showShoulderRide);
        commandBuilder.set(shoulderRideSelector + ".Text", "");
        commandBuilder.set(shoulderRideSelector + ".TooltipText", showShoulderRide
                ? LocalizedText.resolve(language, entry.shoulderRideMounted()
                ? "tamework.ui.linkedPanel.bonded.shoulder.down.tooltip"
                : "tamework.ui.linkedPanel.bonded.shoulder.toMe.tooltip") : "");
        commandBuilder.set(inactiveBadgeSelector + ".Visible", showInactiveBadge);
        LinkedNpcPanelGroupTabBinder.bind(
                commandBuilder,
                groupTabSelector,
                entry,
                pendingUnlink
        );
        commandBuilder.setObject(entrySelector + ".Anchor",
                buildCardAnchor(managedRoster));
        LinkedNpcPanelVitalsBinder.bind(commandBuilder, entrySelector, entry, language);
        LinkedNpcPanelProgressionBinder.bindXpProgressRing(
                commandBuilder,
                xpProgressRingSelector,
                xpLevelTextSelector,
                xpTooltipSelector,
                entry.futureStatA()
        );
        boolean canOpenTalentsFromLevelIndicator =
                entry.isTalentsActionVisible()
                        && entry.isTalentsActionEnabled()
                        && entry.futureStatA() != null
                        && !pendingUnlink;
        boolean showTalentPointAction =
                entry.isTalentsActionVisible()
                        && entry.isTalentsActionEnabled()
                        && LinkedNpcPanelProgressionBinder.availableTalentPoints(entry.futureStatB()) > 0
                        && !pendingUnlink;
        LinkedNpcPanelProgressionBinder.bindTalentPointIndicator(
                commandBuilder,
                talentPointActionSelector,
                talentPointCountSelector,
                talentPointCountShadowSelector,
                entry.futureStatB(),
                showTalentPointAction
        );
        LinkedNpcPanelIconStyles.visible(commandBuilder, respawnSelector, showRespawn);
        LinkedNpcPanelFeatureBinder.bind(
                commandBuilder,
                eventBuilder,
                entrySelector,
                entry.npcUuid(),
                feature,
                config,
                language
        );
        LinkedNpcPanelIconStyles.apply(commandBuilder, entrySelector, entry);
        int behaviorRight = 386;
        if (showShoulderRide) {
            LinkedNpcPanelIconStyles.placeBehavior(commandBuilder, shoulderRideSelector, behaviorRight);
            behaviorRight -= 76;
        }
        if (showFlightToggle) {
            LinkedNpcPanelIconStyles.placeBehavior(commandBuilder, flightToggleSelector, behaviorRight);
            behaviorRight -= 76;
        }
        if (showBreedingToggleEnabled || showBreedingToggleDisabled) {
            LinkedNpcPanelIconStyles.placeBehavior(commandBuilder,
                    showBreedingToggleEnabled ? breedingToggleEnabledSelector : breedingToggleDisabledSelector,
                    behaviorRight);
        }
        LinkedNpcPanelIconStyles.visible(commandBuilder, locateSelector, showLocate);
        LinkedNpcPanelIconStyles.visible(commandBuilder, recallSelector, showRecall);
        LinkedNpcPanelIconStyles.visible(commandBuilder, setHomeSelector, showSetHome);
        LinkedNpcPanelIconStyles.visible(commandBuilder, returnHomeSelector, showReturnHome);
        LinkedNpcPanelIconStyles.visible(commandBuilder, releaseSelector, showRelease);
        LinkedNpcPanelIconStyles.visible(commandBuilder, releaseDisabledSelector, showReleaseDisabled);
        commandBuilder.set(releaseSelector + ".Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.card.button.release"));
        LinkedNpcPanelIconStyles.visible(commandBuilder, cullSelector, showCull);
        LinkedNpcTraitIndicatorBinder.bind(commandBuilder, entrySelector, entry.traitIndicators());

        if (showLink) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    linkSelector,
                    EventData.of(config.eventCommandId(), config.linkCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (removalMenuAvailable) {
            commandBuilder.set(removeSelector + ".Text", "");
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    removeSelector,
                    EventData.of(config.eventCommandId(), config.removalMenuCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showUnlink) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    unlinkSelector,
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
        if (showLocate) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    locateSelector,
                    EventData.of(config.eventCommandId(), config.locateCommandPrefix() + entry.npcUuid()),
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
        if (showFlightToggle) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    flightToggleSelector,
                    EventData.of(config.eventCommandId(),
                            CommandSelectionPageEventBinder
                                    .LINKED_FLIGHT_TOGGLE_COMMAND_PREFIX
                                    + entry.npcUuid()),
                    false
            );
        }
        if (showShoulderRide) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    shoulderRideSelector,
                    EventData.of(config.eventCommandId(),
                            CommandSelectionPageEventBinder
                                    .LINKED_SHOULDER_RIDE_COMMAND_PREFIX
                                    + entry.npcUuid()),
                    false
            );
        }
        if (canOpenTalentsFromLevelIndicator) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    xpTooltipSelector,
                    EventData.of(config.eventCommandId(), config.openTalentsCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
        if (showTalentPointAction) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    talentPointButtonSelector,
                    EventData.of(config.eventCommandId(), config.openTalentsCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
    }

    private static Anchor buildCardAnchor(boolean managedRoster) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(3));
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(0));
        anchor.setHeight(Value.of(managedRoster
                ? ROSTER_CARD_HEIGHT : NORMAL_CARD_HEIGHT));
        return anchor;
    }

    record CardBindingConfig(String linkedPanelCardUiPath,
                              String eventCommandId,
                              String linkCommandPrefix,
                              String unlinkCommandPrefix,
                              String removalMenuCommandPrefix,
                              String openGroupPickerCommandPrefix,
                             String toggleActiveCommandPrefix,
                             String toggleBreedingCommandPrefix,
                             String releaseCommandPrefix,
                             String cullCommandPrefix,
                             String respawnCommandPrefix,
                             String summonCommandPrefix,
                             String dismissCommandPrefix,
                             String locateCommandPrefix,
                             String recallCommandPrefix,
                             String setHomeCommandPrefix,
                             String returnHomeCommandPrefix,
                             String openTalentsCommandPrefix,
                             String bondedFlightToggleCommandPrefix,
                             boolean recallActionEnabled,
                             boolean ownerCommandFamilyRoster) {
        CardBindingConfig(String linkedPanelCardUiPath,
                           String eventCommandId,
                           String linkCommandPrefix,
                           String unlinkCommandPrefix,
                          String openGroupPickerCommandPrefix,
                          String toggleActiveCommandPrefix,
                          String toggleBreedingCommandPrefix,
                          String releaseCommandPrefix,
                          String cullCommandPrefix,
                          String respawnCommandPrefix,
                          String summonCommandPrefix,
                          String dismissCommandPrefix,
                          String locateCommandPrefix,
                          String recallCommandPrefix,
                          String setHomeCommandPrefix,
                          String returnHomeCommandPrefix,
                          String openTalentsCommandPrefix,
                          boolean recallActionEnabled,
                          boolean ownerCommandFamilyRoster) {
            this(linkedPanelCardUiPath, eventCommandId, linkCommandPrefix,
                    unlinkCommandPrefix,
                    CommandSelectionPageEventBinder.OPEN_REMOVAL_MENU_COMMAND_PREFIX,
                    openGroupPickerCommandPrefix,
                    toggleActiveCommandPrefix, toggleBreedingCommandPrefix,
                    releaseCommandPrefix, cullCommandPrefix, respawnCommandPrefix,
                    summonCommandPrefix, dismissCommandPrefix, locateCommandPrefix,
                    recallCommandPrefix, setHomeCommandPrefix,
                    returnHomeCommandPrefix, openTalentsCommandPrefix,
                    CommandSelectionPageEventBinder.BONDED_FLIGHT_TOGGLE_COMMAND_PREFIX,
                    recallActionEnabled, ownerCommandFamilyRoster);
        }
    }
}
