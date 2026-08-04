package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Objects;
import java.util.UUID;

/**
 * Patches changing normal-card values without touching stable button events.
 */
final class LinkedNpcPanelCardDynamicPresenter {
    private LinkedNpcPanelCardDynamicPresenter() {
    }

    static void refresh(
            UICommandBuilder commands,
            UIEventBuilder events,
            String selector,
            UUID npcUuid,
            LinkedNpcEntry previous,
            LinkedNpcEntry current,
            CommandPanelFeaturePresentation previousFeature,
            CommandPanelFeaturePresentation currentFeature,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig bindingConfig,
            String language
    ) {
        if (currentFeature != null && currentFeature.bonded() != null) {
            refreshBonded(commands, events, selector, npcUuid, previousFeature,
                    currentFeature, pendingUnlink, bindingConfig, language);
            return;
        }
        if (vitalsChanged(previous, current)) {
            LinkedNpcPanelVitalsBinder.bind(commands, selector, current, language);
        }
        if (previous.recallLostRemainingMs() != current.recallLostRemainingMs()) {
            commands.set(selector + " #RecallCountdown.Text", LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.card.recallCountdown",
                    (current.recallLostRemainingMs() + 999L) / 1_000L
            ));
        }
        refreshProgression(commands, selector, previous, current, pendingUnlink);
        if (previous.flightToggleAirborne() != current.flightToggleAirborne()) {
            refreshFlightMode(commands, selector, current, language);
        }
    }

    private static void refreshBonded(
            UICommandBuilder commands,
            UIEventBuilder events,
            String selector,
            UUID npcUuid,
            CommandPanelFeaturePresentation previous,
            CommandPanelFeaturePresentation current,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig bindingConfig,
            String language
    ) {
        BondedCompanionCardPresenter.refreshDynamicState(
                commands, selector, current.bonded(), language);
        BondedCompanionCardPresenter.refreshProgressionState(
                commands, selector, current.bonded(), pendingUnlink, language);
        String attribute = com.alechilles.alecstamework.api
                .BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE;
        if (!Objects.equals(previous.bonded().attributes().get(attribute),
                current.bonded().attributes().get(attribute))) {
            BondedCompanionCardPresenter.bindFlightToggleEvents(
                    events, selector, npcUuid, current.bonded(), bindingConfig);
        }
    }

    private static boolean vitalsChanged(LinkedNpcEntry previous, LinkedNpcEntry current) {
        return previous.currentHealth() != current.currentHealth()
                || previous.maxHealth() != current.maxHealth()
                || previous.currentHappiness() != current.currentHappiness()
                || previous.maxHappiness() != current.maxHappiness()
                || previous.targetHappinessPercent() != current.targetHappinessPercent()
                || !Objects.equals(previous.happinessModifierBreakdown(),
                        current.happinessModifierBreakdown())
                || previous.currentHunger() != current.currentHunger()
                || previous.maxHunger() != current.maxHunger()
                || previous.currentThirst() != current.currentThirst()
                || previous.maxThirst() != current.maxThirst()
                || previous.deadRespawnRemainingMs() != current.deadRespawnRemainingMs()
                || previous.breedingCooldownRemainingMs()
                        != current.breedingCooldownRemainingMs()
                || Double.compare(previous.breedingCooldownRatio(),
                        current.breedingCooldownRatio()) != 0
                || previous.harvestCooldownRemainingMs()
                        != current.harvestCooldownRemainingMs()
                || Double.compare(previous.harvestCooldownRatio(),
                        current.harvestCooldownRatio()) != 0;
    }

    private static void refreshProgression(
            UICommandBuilder commands,
            String selector,
            LinkedNpcEntry previous,
            LinkedNpcEntry current,
            boolean pendingUnlink
    ) {
        if (!Objects.equals(previous.futureStatA(), current.futureStatA())) {
            String ring = selector + " #XpProgressRing";
            LinkedNpcPanelProgressionBinder.bindXpProgressRing(
                    commands, ring, ring + " #XpLevelText", ring + " #XpTooltip",
                    current.futureStatA()
            );
        }
        if (!Objects.equals(previous.futureStatB(), current.futureStatB())) {
            String action = selector + " #TalentPointAction";
            LinkedNpcPanelProgressionBinder.bindTalentPointIndicator(
                    commands, action, action + " #TalentPointCount",
                    action + " #TalentPointCountShadow", current.futureStatB(),
                    current.isTalentsActionVisible()
                            && current.isTalentsActionEnabled()
                            && LinkedNpcPanelProgressionBinder.availableTalentPoints(
                                    current.futureStatB()) > 0
                            && !pendingUnlink
            );
        }
    }

    private static void refreshFlightMode(
            UICommandBuilder commands,
            String selector,
            LinkedNpcEntry current,
            String language
    ) {
        commands.set(selector + " #FlightModeGroundedIcon.Visible",
                !current.flightToggleAirborne());
        commands.set(selector + " #FlightModeAirborneIcon.Visible",
                current.flightToggleAirborne());
        commands.set(selector + " #FlightToggleButton.TooltipText",
                LocalizedText.resolve(language, current.flightToggleAirborne()
                        ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                        : "tamework.ui.linkedPanel.bonded.flight.switchToFlight"));
    }
}
