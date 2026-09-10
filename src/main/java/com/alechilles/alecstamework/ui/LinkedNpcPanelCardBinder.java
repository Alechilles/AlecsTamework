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
    private static final int NORMAL_CARD_HEIGHT = 158;
    private static final int ROSTER_CARD_HEIGHT = 194;

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
        commandBuilder.set(entrySelector + " #RoleSubtitle.Text", entry.roleSubtitle());
        commandBuilder.set(entrySelector + " #RoleSubtitle.Visible", !entry.roleSubtitle().isBlank());
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
        boolean showActiveToggleActive = legacyLinked && entry.active();
        boolean showActiveToggleInactive = legacyLinked && !entry.active();
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
                !entry.loaded() && !pendingUnlink
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
        bindCardLayout(commandBuilder, entrySelector, entry, managedRoster,
                showActiveToggleActive || showActiveToggleInactive);
        commandBuilder.set(entrySelector + " #CooldownRow.Visible",
                entry.hasKnownCooldowns());
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
                        && entry.futureStatA() != null;
        commandBuilder.set(xpTooltipSelector + ".Disabled", !canOpenTalentsFromLevelIndicator);
        commandBuilder.set(xpProgressRingSelector + " #TalentsArrow.Visible", canOpenTalentsFromLevelIndicator);
        boolean showTalentPointAction =
                entry.isTalentsActionVisible()
                        && LinkedNpcPanelProgressionBinder.availableTalentPoints(entry.futureStatB()) > 0;
        boolean canOpenTalentPoints = showTalentPointAction && entry.isTalentsActionEnabled();
        commandBuilder.set(talentPointButtonSelector + ".Disabled", !canOpenTalentPoints);
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
        int actionLeft = 432;
        String[] actionSelectors = {shoulderRideSelector, flightToggleSelector,
                showBreedingToggleEnabled ? breedingToggleEnabledSelector : breedingToggleDisabledSelector,
                respawnSelector, locateSelector, recallSelector, setHomeSelector,
                returnHomeSelector, releaseSelector, releaseDisabledSelector,
                unlinkSelector, unlinkDisabledSelector, cullSelector};
        boolean[] actionVisible = {showShoulderRide, showFlightToggle,
                showBreedingToggleEnabled || showBreedingToggleDisabled,
                showRespawn, showLocate, showRecall, showSetHome, showReturnHome,
                showRelease, showReleaseDisabled, showUnlink, showUnlinkDisabled, showCull};
        for (int actionIndex = 0; actionIndex < actionSelectors.length; actionIndex++) {
            if (actionVisible[actionIndex]) {
                LinkedNpcPanelIconStyles.placeAction(commandBuilder, actionSelectors[actionIndex], actionLeft);
                actionLeft += 60;
            }
        }
        if (!entry.hasHealth() && !entry.dead() && !managedRoster) {
            int statusLeft = Math.max(568, actionLeft + 6);
            int statusWidth = 846 - statusLeft;
            commandBuilder.setObject(entrySelector + " #HealthFrame.Anchor", fixedAnchor(50, statusLeft, statusWidth, 22));
            commandBuilder.setObject(entrySelector + " #HealthText.Anchor", fixedAnchor(0, 0, statusWidth - 2, 20));
            commandBuilder.setObject(entrySelector + " #HealthTextShadow.Anchor", fixedAnchor(1, 1, statusWidth - 2, 20));
            commandBuilder.setObject(entrySelector + " #HealthTooltip.Anchor", fixedAnchor(0, 0, statusWidth, 22));
            commandBuilder.setObject(entrySelector + " #RecallCountdown.Anchor", fixedAnchor(72, statusLeft, statusWidth, 20));
        }
        commandBuilder.set(flightToggleSelector + "Caption.Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.action." + (entry.flightToggleAirborne() ? "flightAirborne" : "flightGrounded")));
        commandBuilder.set(shoulderRideSelector + "Caption.Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.action." + (entry.shoulderRideMounted() ? "shoulderOff" : "shoulderOn")));
        commandBuilder.set(respawnSelector + "Caption.Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.action." + (entry.lost() ? "recover" : "revive")));
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
        if (canOpenTalentPoints) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    talentPointButtonSelector,
                    EventData.of(config.eventCommandId(), config.openTalentsCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
    }

    /** Keep unavailable cards concise and reset geometry when a reused row becomes live again. */
    static void bindCardLayout(UICommandBuilder commands, String card, LinkedNpcEntry entry,
                               boolean managedRoster, boolean showActiveToggle) {
        boolean compact = !managedRoster && !entry.hasKnownCardDetails();
        commands.setObject(card + ".Anchor", buildCardAnchor(managedRoster, compact));
        commands.set(card + " #NeedRingRow.Visible", !compact);
        commands.set(card + " #TraitStrip.Visible", !compact);
        commands.set(card + " #HealthTextShadow.Visible", entry.hasHealth() || entry.dead());
        commands.set(card + " #StatusDivider.Visible", true);
        commands.setObject(card + " #StatusUnloaded.Anchor",
                fixedAnchor(compact ? 48 : 74, compact ? 568 : 432, compact ? 278 : 270, 20));
        commands.setObject(card + " #GroupSelector.Anchor", fixedAnchor(compact ? 72 : 102, 0, 144, 26));
        commands.setObject(card + " #HealthFrame.Anchor", fixedAnchor(50, compact && !entry.dead() ? 568 : 172, compact ? 278 : 234, 22));
        // Runtime string patches accept opaque hex colors; alpha syntax is parsed as a texture path.
        commands.set(card + " #HealthFrame.Background",
                entry.dead() ? "#151916"
                        : !entry.loaded() && entry.hasKnownCardDetails() ? "#202423"
                        : compact ? "#202423" : "#151916");
        commands.setObject(card + " #HealthText.Anchor", fixedAnchor(0, 0, compact ? 276 : 232, 20));
        commands.setObject(card + " #HealthTextShadow.Anchor", fixedAnchor(1, 1, compact ? 276 : 232, 20));
        commands.setObject(card + " #HealthTooltip.Anchor", fixedAnchor(0, 0, compact ? 278 : 234, 22));
        // Keep the talent-point control first, then right-align the level control
        // so its width can shrink and grow with the displayed level digits.
        commands.setObject(card + " #XpProgressRing.Anchor", fixedAnchor(18, 358, 48, 24));
        commands.setObject(card + " #TalentPointAction.Anchor", fixedAnchor(18, 304, 34, 24));
        int activeTop = compact ? 48 : 78;
        commands.setObject(card + " #ActiveToggleActiveButton.Anchor", fixedAnchor(activeTop, 0, 40, 20));
        commands.setObject(card + " #ActiveToggleInactiveButton.Anchor", fixedAnchor(activeTop, 0, 40, 20));
        commands.setObject(card + " #StatusInactive.Anchor", fixedAnchor(activeTop, 48, 96, 20));
        int nameLeft = entry.isMale() || entry.isFemale() ? 28 : 0;
        commands.setObject(card + " #GenderMaleIcon.Anchor", fixedAnchor(1, 0, 22, 22));
        commands.setObject(card + " #GenderFemaleIcon.Anchor", fixedAnchor(1, 0, 22, 22));
        commands.setObject(card + " #Name.Anchor", fixedAnchor(0, nameLeft, 150 - nameLeft, 24));
    }

    private static Anchor fixedAnchor(int top, int left, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setLeft(Value.of(left));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private static Anchor buildCardAnchor(boolean managedRoster, boolean compact) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(3));
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(0));
        anchor.setHeight(Value.of(managedRoster
                ? ROSTER_CARD_HEIGHT : compact ? 112 : NORMAL_CARD_HEIGHT));
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
