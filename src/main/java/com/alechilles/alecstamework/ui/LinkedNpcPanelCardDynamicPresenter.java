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
        if (!previous.portraitIcon().equals(current.portraitIcon())) {
            LinkedNpcPanelCardBinder.bindPortrait(commands, selector, current, !current.hasKnownCardDetails());
        }
        if (vitalsChanged(previous, current)) {
            LinkedNpcPanelVitalsBinder.bind(commands, selector, current, language);
            LinkedNpcPanelCardBinder.bindBreedingTooltips(commands, selector, current, language);
        }
        if (previous.recallLostRemainingMs() != current.recallLostRemainingMs()) {
            commands.set(selector + " #RecallCountdown.Text", LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.card.recallCountdown",
                    (current.recallLostRemainingMs() + 999L) / 1_000L
            ));
        }
        refreshProgression(commands, selector, previous, current);
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
        BondedCompanionCardPresenter.bindFlightToggleEvents(
                events, selector, npcUuid, current.bonded(), bindingConfig);
    }

    private static boolean vitalsChanged(LinkedNpcEntry previous, LinkedNpcEntry current) {
        return previous.currentHealth() != current.currentHealth()
                || previous.maxHealth() != current.maxHealth()
                || previous.currentHappiness() != current.currentHappiness()
                || previous.maxHappiness() != current.maxHappiness()
                || previous.targetHappinessPercent() != current.targetHappinessPercent()
                || Double.compare(previous.breedingHappinessRatio(), current.breedingHappinessRatio()) != 0
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
            LinkedNpcEntry current
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
                            && LinkedNpcPanelProgressionBinder.availableTalentPoints(
                                    current.futureStatB()) > 0
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
                false);
        commands.set(selector + " #FlightModeAirborneIcon.Visible",
                false);
        LinkedNpcPanelIconStyles.style(commands, selector + " #FlightToggleButton",
                current.flightToggleAirborne() ? "FlightAirborne" : "FlightGrounded");
        commands.set(selector + " #FlightToggleButton.TooltipText",
                LocalizedText.resolve(language, current.flightToggleAirborne()
                        ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                        : "tamework.ui.linkedPanel.bonded.flight.switchToFlight"));
    }
}
