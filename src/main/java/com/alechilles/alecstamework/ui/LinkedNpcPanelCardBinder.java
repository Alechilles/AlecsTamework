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
    private static final int CARD_HEIGHT = 92;
    private static final int EXPANDED_CARD_HEIGHT = 184;
    private static final int FUTURE_STAT_FILL_WIDTH = 358;
    private static final int FUTURE_STAT_FILL_HEIGHT = 8;

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
        String statusConfirmSelector = entrySelector + " #StatusConfirm";
        String secondaryStatFrameSelector = entrySelector + " #FutureStatAFrame";
        String secondaryStatFillSelector = entrySelector + " #FutureStatAFill";
        String secondaryStatTextSelector = entrySelector + " #FutureStatAText";
        String tertiaryStatFrameSelector = entrySelector + " #FutureStatBFrame";
        String tertiaryStatFillSelector = entrySelector + " #FutureStatBFill";
        String tertiaryStatTextSelector = entrySelector + " #FutureStatBText";
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
                isLinked && entry.loaded() && entry.breedingCooldownKnown() && entry.breedingEnabled() && !pendingUnlink;
        boolean showBreedingToggleDisabled =
                isLinked && entry.loaded() && entry.breedingCooldownKnown() && !entry.breedingEnabled() && !pendingUnlink;
        boolean showInactiveBadge = isLinked && !entry.active() && !pendingUnlink;

        commandBuilder.set(statusUnloadedSelector + ".Visible", !entry.loaded() && !pendingUnlink && !showRespawn);
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry, language));
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
        commandBuilder.setObject(entrySelector + ".Anchor", buildCardAnchor(entry));
        LinkedNpcPanelVitalsBinder.bind(commandBuilder, entrySelector, entry, language);
        commandBuilder.set(secondaryStatFrameSelector + ".Visible", entry.hasFutureStatA());
        commandBuilder.set(tertiaryStatFrameSelector + ".Visible", entry.hasFutureStatB());
        bindFutureStat(commandBuilder, secondaryStatFillSelector, secondaryStatTextSelector, entry.futureStatA());
        bindFutureStat(commandBuilder, tertiaryStatFillSelector, tertiaryStatTextSelector, entry.futureStatB());
        commandBuilder.set(futureActionBarSelector + ".Visible", entry.hasAnyFutureAction());
        commandBuilder.set(traitsButtonSelector + ".Visible", entry.isTraitsActionVisible());
        commandBuilder.set(talentsButtonSelector + ".Visible", entry.isTalentsActionVisible());
        commandBuilder.set(respawnSelector + ".Visible", showRespawn);
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
        if (entry.isTalentsActionVisible() && entry.isTalentsActionEnabled() && !pendingUnlink) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    talentsButtonSelector,
                    EventData.of(config.eventCommandId(), config.openTalentsCommandPrefix() + entry.npcUuid()),
                    false
            );
        }
    }

    private static void bindFutureStat(UICommandBuilder commandBuilder,
                                       String fillSelector,
                                       String textSelector,
                                       LinkedNpcEntry.FutureStat stat) {
        if (commandBuilder == null || fillSelector == null || textSelector == null || stat == null) {
            return;
        }
        commandBuilder.set(textSelector + ".Text", stat.label() + ": " + stat.current() + "/" + stat.max());
        commandBuilder.setObject(fillSelector + ".Anchor", buildFutureStatFillAnchor(stat.current(), stat.max()));
    }

    private static Anchor buildFutureStatFillAnchor(int current, int max) {
        double ratio = max <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, ((double) current) / (double) max));
        int width = Math.max(0, Math.min(FUTURE_STAT_FILL_WIDTH, (int) Math.round(FUTURE_STAT_FILL_WIDTH * ratio)));
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(1));
        anchor.setTop(Value.of(1));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(FUTURE_STAT_FILL_HEIGHT));
        return anchor;
    }

    private static Anchor buildCardAnchor(LinkedNpcEntry entry) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(3));
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(0));
        anchor.setHeight(Value.of(hasProgressionSurface(entry) ? EXPANDED_CARD_HEIGHT : CARD_HEIGHT));
        return anchor;
    }

    private static boolean hasProgressionSurface(LinkedNpcEntry entry) {
        return entry != null
                && (entry.hasFutureStatA() || entry.hasFutureStatB() || entry.hasAnyFutureAction());
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
                             String locateCommandPrefix,
                             String recallCommandPrefix,
                             String setHomeCommandPrefix,
                             String returnHomeCommandPrefix,
                             String openTalentsCommandPrefix,
                             boolean recallActionEnabled) {
    }
}
