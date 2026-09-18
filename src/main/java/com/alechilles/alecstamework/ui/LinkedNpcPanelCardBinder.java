package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * Binds one linked-panel NPC card including visual state and per-row interaction handlers.
 */
final class LinkedNpcPanelCardBinder {
    private static final int NORMAL_CARD_HEIGHT = 176;
    private static final int ROSTER_CARD_HEIGHT = 212;

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
        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", config.linkedPanelCardUiPath());
        }
        bind(commandBuilder, eventBuilder, entrySelector, entry, pendingUnlink, config, language, feature);
    }

    /** Binds an already-appended generic card under an explicit selector. */
    static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     String entrySelector,
                     LinkedNpcEntry entry,
                     boolean pendingUnlink,
                     CardBindingConfig config,
                     String language) {
        bind(commandBuilder, eventBuilder, entrySelector, entry, pendingUnlink,
                config, language, null);
    }

    private static void bind(UICommandBuilder commandBuilder,
                     UIEventBuilder eventBuilder,
                     String entrySelector,
                     LinkedNpcEntry entry,
                     boolean pendingUnlink,
                     CardBindingConfig config,
                     String language,
                     CommandPanelFeaturePresentation feature) {
        String nameSelector = entrySelector + " #Name";
        String maleIconSelector = entrySelector + " #GenderMaleIcon";
        String femaleIconSelector = entrySelector + " #GenderFemaleIcon";
        String statusUnloadedSelector = entrySelector + " #StatusUnloaded";
        String recallCountdownSelector = entrySelector + " #RecallCountdown";
        String statusConfirmSelector = entrySelector + " #StatusConfirm";
        String inlineLocationSelector = entrySelector + " #InlineLocation";
        String inlineLocationStatusSelector = inlineLocationSelector + " #Status";
        String inlineLocationWorldSelector = inlineLocationSelector + " #World";
        String inlineLocationCoordinatesSelector = inlineLocationSelector + " #Coordinates";
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
        LifecycleDisplay lifecycleDisplay = resolveLifecycleDisplay(entry, language);
        LinkedNpcEntry.AnimalLifecycle lifecycle = entry.animalLifecycle();
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        commandBuilder.set(nameSelector + ".TooltipText", entry.displayName());
        commandBuilder.set(entrySelector + " #RoleSubtitle.Text", entry.roleSubtitle());
        commandBuilder.set(entrySelector + " #RoleSubtitle.Visible", !entry.roleSubtitle().isBlank());
        bindLifecycleProgress(commandBuilder, entrySelector, lifecycle, lifecycleDisplay, language, entry.captured() || entry.dead());
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
        LinkedNpcEntry.Location location = entry.location();
        boolean showInlineLocation = !managedRoster
                && !pendingUnlink
                && !entry.loaded()
                && !entry.dead()
                && !entry.lost()
                && (!location.status().isBlank()
                        || !location.world().isBlank()
                        || !location.coordinates().isBlank());
        boolean showReviveAction = !paidRevivalManaged && genericLinkedOrOwned
                && (entry.dead() || entry.lost())
                && entry.deadRespawnRemainingMs() == 0L
                && !pendingUnlink;
        boolean showLocate = !showInlineLocation && genericLinkedOrOwned
                && !entry.dead() && !entry.lost() && !pendingUnlink;
        boolean showRecall = genericLinkedOrOwned
                && config.recallActionEnabled()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        boolean showSetHome = genericLinkedOrOwned
                && entry.loaded()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        boolean showReturnHome =
                genericLinkedOrOwned
                        && !entry.dead()
                        && !entry.captured()
                        && !entry.inCoop()
                        && !entry.lost()
                        && entry.hasHome()
                        && !pendingUnlink;
        boolean removalMenuAvailable = !managedRoster;
        boolean canRelease = removalMenuAvailable && !entry.captured() && !entry.inCoop();
        boolean canCull = canRelease && entry.loaded() && !entry.dead() && !entry.lost();
        boolean showLink = !entry.ownedActions() && !legacyLinked && !managedRoster && entry.loaded()
                && !entry.dead() && !entry.captured() && !entry.inCoop() && !entry.lost() && !pendingUnlink;
        boolean showRemovalMenu = removalMenuAvailable && pendingUnlink;
        boolean showUnlink = showRemovalMenu && legacyLinked && !entry.ownedActions();
        boolean showUnlinkDisabled = showRemovalMenu && !legacyLinked && !entry.ownedActions();
        boolean showRelease = showRemovalMenu && canRelease;
        boolean showReleaseDisabled = showRemovalMenu && !canRelease;
        boolean showCull = showRemovalMenu && canCull;
        boolean showActiveToggleActive = genericLinkedOrOwned && entry.active();
        boolean showActiveToggleInactive = genericLinkedOrOwned && !entry.active();
        boolean showBreedingToggleEnabled =
                genericLinkedOrOwned && entry.loaded() && entry.breedingAvailable() && entry.breedingEnabled() && !pendingUnlink;
        boolean showBreedingToggleDisabled =
                genericLinkedOrOwned && entry.loaded() && entry.breedingAvailable() && !entry.breedingEnabled() && !pendingUnlink;
        boolean showFlightToggle = genericLinkedOrOwned && entry.loaded()
                && entry.flightToggleAvailable() && !pendingUnlink;
        boolean showShoulderRide = genericLinkedOrOwned && entry.loaded()
                && entry.shoulderRideAvailable() && !pendingUnlink;
        boolean showRespawn = paidRevivalManaged
                ? LinkedNpcPanelFeatureBinder.paidReviveVisible(feature)
                : showReviveAction;
        boolean showInactiveBadge = !entry.ownedActions() && legacyLinked && !entry.active()
                && !showRespawn && !pendingUnlink;
        boolean showRecallCountdown = genericLinkedOrOwned
                && entry.recallPending()
                && !entry.loaded()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        commandBuilder.set(
                statusUnloadedSelector + ".Visible",
                !showInlineLocation
                        && (!entry.loaded() || entry.dead() || entry.lost() || entry.captured() || entry.inCoop())
        );
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry, language));
        commandBuilder.set(recallCountdownSelector + ".Visible", showRecallCountdown);
        commandBuilder.set(recallCountdownSelector + ".Style",
                Value.ref("TameworkLinkedNpcPanelCard.ui", "RecallCountdown"));
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
        if (entry.ownedActions()) {
            commandBuilder.set(activeToggleInactiveSelector + ".Disabled", !entry.selectionSupported());
            commandBuilder.set(activeToggleActiveSelector + ".TooltipText", LocalizedText.resolve(language, "tamework.ui.companions.deselect"));
            commandBuilder.set(activeToggleInactiveSelector + ".TooltipText", LocalizedText.resolve(language,
                    entry.selectionSupported() ? "tamework.ui.companions.select" : "tamework.ui.companions.unsupported"));
        }
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
                showActiveToggleActive || showActiveToggleInactive, showInlineLocation);
        commandBuilder.set(entrySelector + " #CooldownRow.Visible",
                lifecycleDisplay.visible() || entry.hasKnownCooldowns());
        commandBuilder.set(entrySelector + " #LifecycleProgress #Paused.Visible", entry.captured() || entry.dead() || lifecycle.frozen());
        commandBuilder.set(entrySelector + " #BreedingCooldown #Paused.Visible", entry.captured() || entry.dead());
        commandBuilder.set(entrySelector + " #HarvestCooldown #Paused.Visible", entry.captured() || entry.dead());
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
        int actionLeft = showInlineLocation ? 736 : 432;
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
                if (showRemovalMenu) {
                    LinkedNpcPanelIconStyles.anchor(commandBuilder, actionSelectors[actionIndex],
                            fixedAnchor(52, actionLeft, 42, 42));
                    commandBuilder.setObject(actionSelectors[actionIndex] + "Caption.Anchor",
                            fixedAnchor(96, actionLeft - 6, 54, 12));
                }
                actionLeft += 60;
            }
        }
        String emblem = LinkedNpcPanelStatusTextService.resolveAvailabilityEmblem(entry);
        commandBuilder.set(entrySelector + " #StatusEmblem.Visible", emblem != null && !showInlineLocation);
        if (emblem != null && !showInlineLocation) {
            boolean compact = !managedRoster && !entry.hasKnownCardDetails();
            // Center in the entire action section, independently of visible actions.
            int statusLeft = 432;
            int statusWidth = 414;
            boolean lost = entry.lost() && !entry.dead() && !entry.inCoop();
            int emblemSize = lost ? (compact ? 48 : 72) : compact ? 36 : 44;
            int emblemTop = lost ? (compact ? 37 : managedRoster ? 64 : 46) : 36;
            int labelTop = lost ? emblemTop + emblemSize + 4 : compact ? 74 : 82;
            commandBuilder.setObject(entrySelector + " #StatusEmblem.Background", UiIconStyle.forTexture(emblem));
            commandBuilder.setObject(entrySelector + " #StatusEmblem.Anchor",
                    fixedAnchor(emblemTop, statusLeft + (statusWidth - emblemSize) / 2, emblemSize, emblemSize));
            commandBuilder.setObject(statusUnloadedSelector + ".Anchor",
                    fixedAnchor(labelTop, statusLeft, statusWidth, 16));
            commandBuilder.setObject(recallCountdownSelector + ".Anchor",
                    fixedAnchor(lost ? labelTop + 18 : 98, statusLeft, statusWidth, 12));
        }
        boolean showInlineStatus = showInlineLocation && !location.status().isBlank();
        boolean showInlineWorld = showInlineLocation && !location.world().isBlank();
        boolean showInlineCoordinates = showInlineLocation && !location.coordinates().isBlank();
        commandBuilder.set(inlineLocationSelector + ".Visible", showInlineLocation);
        commandBuilder.set(inlineLocationStatusSelector + ".Visible", showInlineStatus);
        commandBuilder.set(inlineLocationWorldSelector + ".Visible", showInlineWorld);
        commandBuilder.set(inlineLocationCoordinatesSelector + ".Visible", false);
        commandBuilder.set(inlineLocationSelector + " #CoordinateLabel.Visible", showInlineCoordinates);
        commandBuilder.set(inlineLocationSelector + " #CopyButton.Visible", showInlineCoordinates);
        commandBuilder.set(inlineLocationSelector + " #CopyGlyph.Visible", showInlineCoordinates);
        commandBuilder.set(inlineLocationSelector + " #CopyHint.Visible", false);
        commandBuilder.set(inlineLocationSelector + " #RelativeDistance.Visible",
                showInlineCoordinates && !location.relativeDistance().isBlank());
        commandBuilder.set(inlineLocationSelector + " #RelativeDistance.Text", location.relativeDistance());
        commandBuilder.set(inlineLocationSelector + " #Heading.Text", entry.captured()
                ? LocalizedText.resolve(language, "tamework.ui.linkedLocation.captured")
                : LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry, language));
        commandBuilder.set(inlineLocationStatusSelector + ".Text", location.status());
        commandBuilder.set(inlineLocationWorldSelector + ".Text",
                LocalizedText.resolve(language, "tamework.ui.linkedLocation.worldLabel") + " " + location.world());
        commandBuilder.set(inlineLocationSelector + " #CoordinateLabel.Text",
                LinkedNpcLocationCopyControl.labeledCoordinates(location.coordinates()));
        commandBuilder.set(inlineLocationSelector + " #CopyButton.TooltipText",
                LocalizedText.resolve(language, "tamework.ui.linkedLocation.copy"));
        commandBuilder.set(inlineLocationSelector + " #CopyHint.Text",
                LocalizedText.resolve(language, "tamework.ui.linkedLocation.copyHint"));
        commandBuilder.set(inlineLocationCoordinatesSelector + ".Value", location.coordinates());
        commandBuilder.set(inlineLocationCoordinatesSelector + ".MaxLength",
                Math.max(64, location.coordinates().length() + 16));
        int locationRow = showInlineStatus ? 28 : 14;
        int inlineLocationWidth = showRecall || showReturnHome || showSetHome ? 296 : 414;
        commandBuilder.setObject(inlineLocationStatusSelector + ".Anchor",
                fixedAnchor(14, 0, inlineLocationWidth, 14));
        commandBuilder.setObject(inlineLocationWorldSelector + ".Anchor",
                fixedAnchor(locationRow, 0, inlineLocationWidth, 14));
        locationRow += showInlineWorld ? 14 : 0;
        commandBuilder.setObject(inlineLocationCoordinatesSelector + ".Anchor",
                fixedAnchor(locationRow, 24, inlineLocationWidth - 24, 18));
        commandBuilder.setObject(inlineLocationSelector + " #CoordinateLabel.Anchor",
                fixedAnchor(locationRow, 24, inlineLocationWidth - 24, 18));
        commandBuilder.setObject(inlineLocationSelector + " #CopyButton.Anchor",
                fixedAnchor(locationRow, 0, 20, 18));
        commandBuilder.setObject(inlineLocationSelector + " #CopyGlyph.Anchor",
                fixedAnchor(locationRow + 2, 3, 14, 14));
        commandBuilder.setObject(inlineLocationSelector + " #CopyHint.Anchor",
                fixedAnchor(locationRow + 18, 24, inlineLocationWidth - 24, 14));
        commandBuilder.setObject(inlineLocationSelector + " #RelativeDistance.Anchor",
                fixedAnchor(locationRow + 18, 0, inlineLocationWidth, 14));
        if (showInlineCoordinates) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating,
                    inlineLocationSelector + " #CopyButton",
                    EventData.of(config.eventCommandId(), LinkedNpcLocationCopyControl.PREFIX + entry.npcUuid()), false);
        }
        if (showInlineLocation) {
            Anchor inlineAnchor = fixedAnchor(32, 432, inlineLocationWidth, 74);
            commandBuilder.setObject(inlineLocationSelector + ".Anchor", inlineAnchor);
            commandBuilder.setObject(recallCountdownSelector + ".Anchor",
                    fixedAnchor(88, 730, 116, 20));
            commandBuilder.set(recallCountdownSelector + ".Style",
                    Value.ref("TameworkLinkedNpcPanelCard.ui", "InlineRecallCountdown"));
        }
        commandBuilder.set(flightToggleSelector + "Caption.Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.action." + (entry.flightToggleAirborne() ? "flightAirborne" : "flightGrounded")));
        commandBuilder.set(shoulderRideSelector + "Caption.Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.action." + (entry.shoulderRideMounted() ? "shoulderOff" : "shoulderOn")));
        commandBuilder.set(respawnSelector + "Caption.Text", LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.action." + (entry.lost() ? "recover" : "revive")));
        LinkedNpcPanelIconStyles.visible(commandBuilder, locateSelector, showLocate);
        LinkedNpcPanelIconStyles.visible(commandBuilder, recallSelector, showRecall);
        if (showInlineLocation && showRecallCountdown) {
            commandBuilder.set(recallSelector + "Caption.Visible", false);
        }
        LinkedNpcPanelIconStyles.visible(commandBuilder, setHomeSelector, showSetHome);
        LinkedNpcPanelIconStyles.visible(commandBuilder, returnHomeSelector, showReturnHome);
        if (showInlineLocation && showRecallCountdown) {
            commandBuilder.set(returnHomeSelector + "Caption.Visible", false);
        }
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
            LinkedNpcPanelIconStyles.style(commandBuilder, removeSelector,
                    showRemovalMenu ? "Back" : "Remove");
            commandBuilder.set(removeSelector + ".Text", "");
            commandBuilder.set(removeSelector + "Glyph.Visible", true);
            commandBuilder.set(removeSelector + ".TooltipText", LocalizedText.resolve(language,
                    showRemovalMenu ? "tamework.ui.shared.button.back"
                            : "tamework.ui.linkedPanel.card.tooltip.removalMenu"));
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
                               boolean managedRoster, boolean showActiveToggle,
                               boolean showInlineLocation) {
        boolean compact = !managedRoster && !entry.hasKnownCardDetails() && !showInlineLocation
                && (entry.dead() || entry.lost());
        Anchor cardAnchor = buildCardAnchor(managedRoster, compact);
        commands.setObject(card + ".Anchor", cardAnchor);
        Anchor cooldownAnchor = fixedAnchor(111, 432, 414, 34);
        commands.setObject(card + " #CooldownRow.Anchor", cooldownAnchor);
        bindPortrait(commands, card, entry, compact);
        boolean showDetails = !compact && entry.hasKnownCardDetails();
        commands.set(card + " #NeedRingRow.Visible", showDetails);
        commands.set(card + " #TraitStrip.Visible", showDetails);
        commands.set(card + " #HealthTextShadow.Visible", entry.hasHealth() || entry.dead());
        commands.set(card + " #StatusDivider.Visible", true);
        commands.setObject(card + " #StatusUnloaded.Anchor",
                fixedAnchor(compact ? 48 : 74, compact ? 568 : 432, compact ? 278 : 270, 20));
        commands.setObject(card + " #GroupSelector.Anchor", fixedAnchor(compact ? 80 : 120, 0, 144, 26));
        commands.setObject(card + " #GroupSelectorLabel.Anchor", fixedAnchor(compact ? 80 : 120, 26, 96, 26));
        commands.setObject(card + " #GroupSelectorMarker.Anchor", fixedAnchor(compact ? 87 : 127, 7, 12, 12));
        commands.setObject(card + " #HealthFrame.Anchor", fixedAnchor(68, 172, 234, 22));
        // Runtime string patches accept opaque hex colors; alpha syntax is parsed as a texture path.
        commands.set(card + " #HealthFrame.Background",
                entry.dead() ? "#151916"
                        : !entry.loaded() && entry.hasKnownCardDetails() ? "#202423"
                        : compact ? "#202423" : "#151916");
        commands.setObject(card + " #HealthText.Anchor", fixedAnchor(0, 0, 232, 20));
        commands.setObject(card + " #HealthTextShadow.Anchor", fixedAnchor(1, 1, 232, 20));
        commands.setObject(card + " #HealthTooltip.Anchor", fixedAnchor(0, 0, 234, 22));
        // Keep the talent-point control first, then right-align the level control
        // so its width can shrink and grow with the displayed level digits.
        commands.setObject(card + " #XpProgressRing.Anchor", fixedAnchor(38, 358, 48, 24));
        commands.setObject(card + " #TalentPointAction.Anchor", fixedAnchor(38, 304, 34, 24));
        int activeTop = compact ? 56 : 96;
        commands.setObject(card + " #ActiveToggleActiveButton.Anchor", fixedAnchor(activeTop, 0, 40, 20));
        commands.setObject(card + " #ActiveToggleInactiveButton.Anchor", fixedAnchor(activeTop, 0, 40, 20));
        int nameLeft = entry.isMale() || entry.isFemale() ? 28 : 0;
        commands.setObject(card + " #GenderMaleIcon.Anchor", fixedAnchor(1, 0, 22, 22));
        commands.setObject(card + " #GenderFemaleIcon.Anchor", fixedAnchor(1, 0, 22, 22));
        Anchor nameAnchor = fixedAnchor(0, nameLeft, 0, 24);
        nameAnchor.setWidth(null);
        nameAnchor.setRight(Value.of(36));
        commands.setObject(card + " #Name.Anchor", nameAnchor);
    }

    static LifecycleDisplay resolveLifecycleDisplay(LinkedNpcEntry entry, String language) {
        LinkedNpcEntry.AnimalLifecycle lifecycle = entry == null
                ? LinkedNpcEntry.AnimalLifecycle.NONE : entry.animalLifecycle();
        if (!lifecycle.active()) return LifecycleDisplay.NONE;
        String stage = LocalizedText.resolve(language,
                "tamework.commandmenu.lifecycle.stage." + lifecycle.stage().toLowerCase(java.util.Locale.ROOT));
        String yieldTooltip = LocalizedText.resolve(language, lifecycle.yieldMultiplier() >= 1.0
                ? "tamework.commandmenu.lifecycle.yield.maximumTooltip"
                : "tamework.commandmenu.lifecycle.yield.reducedTooltip");
        String countdown = "";
        if (!entry.captured() && lifecycle.frozen() && !lifecycle.prime()) {
            countdown = LocalizedText.resolve(language, "tamework.commandmenu.lifecycle.status.paused");
        } else if (lifecycle.frozen() && (!entry.captured() || lifecycle.remainingMs() == Long.MAX_VALUE)) {
            countdown = LocalizedText.resolve(language, "tamework.commandmenu.lifecycle.status.frozen");
        } else if (lifecycle.remainingMs() >= 0L && lifecycle.remainingMs() != Long.MAX_VALUE) {
            String remaining = LinkedNpcPanelStatusTextService.formatRemainingTime(lifecycle.remainingMs(), language);
            if (lifecycle.nextDeath()) {
                countdown = LocalizedText.format(language,
                        "tamework.commandmenu.lifecycle.oldAgeDeath", remaining);
            } else {
                String nextStage = lifecycle.nextStage().isBlank() ? resolveNextLifecycleStage(lifecycle.stage(), language)
                        : LocalizedText.resolve(language, "tamework.commandmenu.lifecycle.stage."
                                + lifecycle.nextStage().toLowerCase(java.util.Locale.ROOT));
                countdown = nextStage.isBlank()
                        ? LocalizedText.format(language, "tamework.commandmenu.lifecycle.remaining", remaining)
                        : LocalizedText.format(language, "tamework.commandmenu.lifecycle.nextStage", nextStage, remaining);
            }
        }
        return new LifecycleDisplay(true, stage, countdown, yieldTooltip);
    }

    private static String resolveNextLifecycleStage(String stage, String language) {
        String nextStage = switch (stage.toLowerCase(java.util.Locale.ROOT)) {
            case "adolescent" -> "adult";
            case "adult" -> "prime";
            case "prime" -> "senior";
            default -> "";
        };
        return nextStage.isEmpty() ? "" : LocalizedText.resolve(language,
                "tamework.commandmenu.lifecycle.stage." + nextStage);
    }

    private static void bindLifecycleProgress(UICommandBuilder commands, String card,
                                              LinkedNpcEntry.AnimalLifecycle lifecycle,
                                              LifecycleDisplay display, String language, boolean muted) {
        String selector = card + " #LifecycleProgress";
        commands.set(selector + ".Visible", display.visible());
        String color = muted ? "#727772" : lifecycleColor(lifecycle);
        commands.setObject(selector + " #AgeIcon.Background", lifecycleIcon(lifecycle).setColor(Value.of(color)));
        commands.set(selector + " #CooldownLabel.Text", display.visible()
                ? LocalizedText.format(language, "tamework.commandmenu.lifecycle.ageStage", display.stageText())
                : "");
        commands.set(selector + " #CooldownText.Text", display.countdownText());
        commands.set(selector + " #CooldownLabel.Style", Value.ref("TameworkLinkedNpcPanelCard.ui",
                muted ? "CooldownTextMuted" : lifecycle.prime() ? "LifecyclePrimeLabel"
                        : "Senior".equalsIgnoreCase(lifecycle.stage())
                        ? "LifecycleSeniorLabel" : "LifecycleAdultLabel"));
        commands.set(selector + " #LifecycleProgressTooltip.TooltipText", display.yieldTooltip());
        commands.set(selector + " #CooldownText.Style", Value.ref("TameworkLinkedNpcPanelCard.ui",
                muted ? "CooldownTextMuted" : "CooldownTextNormal"));
        commands.set(selector + " #MeterFill.Background", color);
        commands.setObject(selector + " #MeterFill.Anchor",
                LinkedNpcPanelStatusMeter.buildFillAnchor(lifecycle.stageProgress()));
    }

    static String lifecycleColor(LinkedNpcEntry.AnimalLifecycle lifecycle) {
        return lifecycle.prime() ? "#d9c878"
                : "Senior".equalsIgnoreCase(lifecycle.stage()) ? "#d9a86f" : "#78bfc1";
    }

    static PatchStyle lifecycleIcon(LinkedNpcEntry.AnimalLifecycle lifecycle) {
        String stage = switch (lifecycle.stage().toLowerCase(java.util.Locale.ROOT)) {
            case "baby" -> "Baby";
            case "adolescent" -> "Adolescent";
            case "prime" -> "Prime";
            case "senior" -> "Senior";
            default -> "Adult";
        };
        return new PatchStyle(
                Value.of("Tamework/LinkedPanelIcons/LifeStage_" + stage + ".png"))
                .setColor(Value.of(lifecycleColor(lifecycle)));
    }

    record LifecycleDisplay(boolean visible, String stageText, String countdownText, String yieldTooltip) {
        static final LifecycleDisplay NONE = new LifecycleDisplay(false, "", "", "");
    }

    static void bindPortrait(UICommandBuilder commands, String card, LinkedNpcEntry entry, boolean compact) {
        LinkedNpcPanelPortraitBinder.bind(commands, card, entry);
        commands.set(card + " #Portrait.Style", Value.ref("TameworkLinkedNpcPanelCard.ui", compact ? "PortraitCompactStyle" : "PortraitStyle"));
        commands.setObject(card + " #Portrait.Anchor", fixedAnchor(compact ? 30 : 28, compact ? 96 : 28, compact ? 48 : 92, compact ? 48 : 92));
        commands.setObject(card + " #RoleSubtitle.Anchor", fixedAnchor(37, 0, compact && !entry.portraitIcon().isBlank() ? 92 : 148, 18));
        int activeTop = compact ? 56 : 96;
        boolean portrait = !entry.portraitIcon().isBlank();
        commands.setObject(card + " #StatusInactive.Anchor", fixedAnchor(
                activeTop, 48, portrait && compact ? 56 : 96, 20));
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
                ? ROSTER_CARD_HEIGHT : compact ? 124 : NORMAL_CARD_HEIGHT));
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
