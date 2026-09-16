package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionHappinessPresentationService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the player-facing explanation against re-listing an active effect in
 * the inactive section after a happiness-band or food transition.
 */
class CommandLoadedNpcStatusSnapshotServiceHappinessPresentationTest {
    @Test
    void condensesNeedAndFoodEffectsWithoutHidingOtherHappinessEffects() {
        var presentation = new CompanionHappinessPresentationService.PresentationSnapshot(
                62.0, 0.0, 100.0, 50.0, 67.0,
                List.of(
                        new CompanionHappinessPresentationService.EffectEntry(
                                "hunger_hungry", "Hunger: Hungry", -8.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "population_crowded", "Population: Crowded", -3.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "food:apple", "Apple", 7.0,
                                CompanionHappinessPresentationService.EffectKind.FOOD),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "impulse_pet", "Petted", 2.0,
                                CompanionHappinessPresentationService.EffectKind.IMPULSE)
                ),
                List.of(
                        new CompanionHappinessPresentationService.EffectEntry(
                                "hunger_well_fed", "Hunger: Well-fed", 8.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "thirst_quenched", "Thirst: Quenched", 4.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "population_lonely", "Population: Lonely", -4.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "food:berry", "Berry", 2.0,
                                CompanionHappinessPresentationService.EffectKind.FOOD),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "impulse_damage", "Attacked", -3.0,
                                CompanionHappinessPresentationService.EffectKind.IMPULSE)
                ),
                true
        );

        String tooltip = newService().buildHappinessPresentation(presentation, "en-US");

        assertTrue(tooltip.startsWith("Happiness: 62%"));
        assertTrue(tooltip.indexOf("Active effects") < tooltip.indexOf("Other effects"));
        assertTrue(tooltip.contains("Hunger: -8.00\n  Hungry"));
        assertTrue(tooltip.contains("Social needs: -3.00\n  Crowded"));
        assertTrue(tooltip.contains("Last food eaten: +7.00\n  Apple"));
        assertTrue(tooltip.contains("Petted: +2.00"));
        assertTrue(tooltip.contains("Thirst: -"));
        assertTrue(tooltip.contains("Attacked: -"));
        assertFalse(tooltip.contains("Well-fed"));
        assertFalse(tooltip.contains("Lonely"));
        assertFalse(tooltip.contains("Berry"));
        assertFalse(tooltip.contains("target 67%"));
        assertFalse(tooltip.contains("Food effects are exclusive"));
    }

    @Test
    void showsTheLastFoodCategoryOnceWhenFoodEffectsAreExclusive() {
        var presentation = new CompanionHappinessPresentationService.PresentationSnapshot(
                62, 0, 100, 50, 55,
                List.of(new CompanionHappinessPresentationService.EffectEntry(
                        "food:apple", "Apple", 5,
                        CompanionHappinessPresentationService.EffectKind.FOOD)),
                List.of(), true);
        String tooltip = newService().buildHappinessPresentation(presentation, "en-US");
        assertTrue(tooltip.contains("Last food eaten: +5.00\n  Apple"));
        assertFalse(tooltip.contains("Other effects"));
    }

    @Test
    void combinesActiveFoodOnlyWhenFoodEffectsAreNotExclusive() {
        var presentation = new CompanionHappinessPresentationService.PresentationSnapshot(
                62, 0, 100, 50, 55,
                List.of(
                        new CompanionHappinessPresentationService.EffectEntry(
                                "food:apple", "Apple", 5,
                                CompanionHappinessPresentationService.EffectKind.FOOD),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "food:berry", "Berry", 2,
                                CompanionHappinessPresentationService.EffectKind.FOOD)
                ),
                List.of(), false);

        String tooltip = newService().buildHappinessPresentation(presentation, "en-US");

        assertTrue(tooltip.contains("Food: +7.00\n  Apple\n  Berry"));
        assertFalse(tooltip.contains("Last food eaten"));
    }

    private static CommandLoadedNpcStatusSnapshotService newService() {
        return new CommandLoadedNpcStatusSnapshotService(
                new CommandNpcNameResolver(),
                new CommandLinkPolicyService(),
                new CommandLinkedPanelProgressionPresentationService(),
                new CommandLinkedPanelCooldownSnapshotService()
        );
    }
}
