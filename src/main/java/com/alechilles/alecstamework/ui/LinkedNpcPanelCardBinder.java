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
    private static final int CARD_HEIGHT = 126;

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
        String entrySelector = "#TameworkLinkedPanelList[" + index + "]";
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
        String activeToggleActiveSelector = entrySelector + " #ActiveToggleActiveButton";
        String activeToggleInactiveSelector = entrySelector + " #ActiveToggleInactiveButton";
        String breedingToggleEnabledSelector = entrySelector + " #BreedingToggleEnabledButton";
        String breedingToggleDisabledSelector = entrySelector + " #BreedingToggleDisabledButton";
        String inactiveBadgeSelector = entrySelector + " #StatusInactive";
        String groupTabSelector = entrySelector + " #GroupTab";
        String groupTabButtonSelector = entrySelector + " #GroupTabButton";
        String respawnSelector = entrySelector + " #RespawnButton";
        String summonSelector = entrySelector + " #RosterSummonButton";
        String dismissSelector = entrySelector + " #RosterDismissButton";
        String rosterStateSelector = entrySelector + " #RosterState";
        String rosterTimerSelector = entrySelector + " #RosterTimer";
        String rosterCapacitySelector = entrySelector + " #RosterCapacity";
        String locateSelector = entrySelector + " #LocateButton";
        String recallSelector = entrySelector + " #RecallButton";
        String setHomeSelector = entrySelector + " #SetHomeButton";
        String returnHomeSelector = entrySelector + " #ReturnHomeButton";
        String releaseSelector = entrySelector + " #ReleaseButton";
        String cullSelector = entrySelector + " #CullButton";

        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", config.linkedPanelCardUiPath());
        }
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        commandBuilder.set(maleIconSelector + ".Visible", entry.isMale());
        commandBuilder.set(femaleIconSelector + ".Visible", entry.isFemale());
        boolean isLinked = entry.linked();
        boolean showRespawn = isLinked
                && (entry.dead() || entry.lost())
                && entry.deadRespawnRemainingMs() == 0L
                && !pendingUnlink;
        boolean showLocate = isLinked && !pendingUnlink;
        boolean showRecall = isLinked
                && config.recallActionEnabled()
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
                isLinked && entry.loaded() && entry.breedingAvailable() && entry.breedingEnabled() && !pendingUnlink;
        boolean showBreedingToggleDisabled =
                isLinked && entry.loaded() && entry.breedingAvailable() && !entry.breedingEnabled() && !pendingUnlink;
        boolean showInactiveBadge = isLinked && !entry.active() && !pendingUnlink;
        boolean showRecallCountdown = isLinked
                && entry.recallPending()
                && !entry.loaded()
                && !entry.dead()
                && !entry.captured()
                && !entry.inCoop()
                && !entry.lost()
                && !pendingUnlink;
        CommandRosterStatusPresentation roster = entry.rosterStatusPresentation();
        if (roster != null && roster.reviveCapBlocked()) showRespawn = false;

        commandBuilder.set(statusUnloadedSelector + ".Visible", !entry.loaded() && !pendingUnlink && !showRespawn);
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
                isLinked
                        ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.card.confirmRemove")
                        : LocalizedText.resolve(language, "tamework.ui.linkedPanel.card.releaseOrCull")
        );
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
        commandBuilder.set(respawnSelector + ".Visible", showRespawn);
        bindRosterStatus(commandBuilder, rosterStateSelector, rosterTimerSelector,
                rosterCapacitySelector, summonSelector, dismissSelector, roster, language);
        commandBuilder.set(locateSelector + ".Visible", showLocate);
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
        if (roster != null && roster.summonVisible()) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, summonSelector,
                    EventData.of(config.eventCommandId(), config.summonCommandPrefix() + entry.npcUuid()), false);
        }
        if (roster != null && roster.dismissVisible()) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, dismissSelector,
                    EventData.of(config.eventCommandId(), config.dismissCommandPrefix() + entry.npcUuid()), false);
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

    private static Anchor buildCardAnchor() {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(3));
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(0));
        anchor.setHeight(Value.of(CARD_HEIGHT));
        return anchor;
    }

    private static void bindRosterStatus(UICommandBuilder builder, String stateSelector,
                                         String timerSelector, String capacitySelector,
                                         String summonSelector, String dismissSelector,
                                         CommandRosterStatusPresentation roster, String language) {
        boolean visible = roster != null;
        builder.set(stateSelector + ".Visible", visible);
        builder.set(timerSelector + ".Visible", visible);
        builder.set(capacitySelector + ".Visible", visible);
        builder.set(summonSelector + ".Visible", visible && roster.summonVisible());
        builder.set(dismissSelector + ".Visible", visible && roster.dismissVisible());
        if (!visible) return;
        String stateKey = switch (roster.state()) {
            case ACTIVE -> "active";
            case UNLOADED -> "unloaded";
            case RESTORING -> "restoring";
            case STORING -> "storing";
            case ROSTER_STORED -> "stored";
            case DEAD_REVIVABLE -> "dead";
            case LOST -> "lost";
        };
        builder.set(stateSelector + ".Text", LocalizedText.format(language,
                "tamework.ui.linkedPanel.roster.state",
                LocalizedText.resolve(language, "tamework.ui.linkedPanel.roster.state." + stateKey)));
        String timer = roster.remainingMs() != null
                ? LocalizedText.format(language, "tamework.ui.linkedPanel.roster.remaining",
                LinkedNpcPanelStatusTextService.formatRemainingTime(roster.remainingMs(), language))
                : roster.cooldownRemainingMs() > 0L
                ? LocalizedText.format(language, "tamework.ui.linkedPanel.roster.cooldown",
                LinkedNpcPanelStatusTextService.formatRemainingTime(roster.cooldownRemainingMs(), language))
                : LocalizedText.format(language, "tamework.ui.linkedPanel.roster.duration",
                roster.unlimitedDuration()
                        ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.roster.unlimited")
                        : LinkedNpcPanelStatusTextService.formatRemainingTime(
                        roster.configuredDurationMs(), language));
        builder.set(timerSelector + ".Text", timer);
        builder.set(capacitySelector + ".Text", roster.capUnlimited()
                ? LocalizedText.format(language, "tamework.ui.linkedPanel.roster.capacityUnlimited",
                roster.activeCount())
                : LocalizedText.format(language, "tamework.ui.linkedPanel.roster.capacity",
                roster.activeCount(), roster.activeLimit()));
        builder.set(summonSelector + ".Enabled", roster.summonEnabled());
        builder.set(dismissSelector + ".Enabled", roster.dismissEnabled());
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
                             String summonCommandPrefix,
                             String dismissCommandPrefix,
                             String locateCommandPrefix,
                             String recallCommandPrefix,
                             String setHomeCommandPrefix,
                             String returnHomeCommandPrefix,
                             String openTalentsCommandPrefix,
                             boolean recallActionEnabled) {
    }
}
