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
    void presentsCurrentBaseTargetThenActiveAndInactiveEffectsWithoutDuplicates() {
        var presentation = new CompanionHappinessPresentationService.PresentationSnapshot(
                62.0, 0.0, 100.0, 50.0, 67.0,
                List.of(
                        new CompanionHappinessPresentationService.EffectEntry(
                                "owner_nearby", "Owner Nearby", 5.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "food:apple", "Apple", 7.0,
                                CompanionHappinessPresentationService.EffectKind.FOOD)
                ),
                List.of(
                        new CompanionHappinessPresentationService.EffectEntry(
                                "hunger_hungry", "Hunger: Hungry", -8.0,
                                CompanionHappinessPresentationService.EffectKind.EQUILIBRIUM),
                        new CompanionHappinessPresentationService.EffectEntry(
                                "food:berry", "Berry", 2.0,
                                CompanionHappinessPresentationService.EffectKind.FOOD)
                ),
                true
        );

        String tooltip = newService().buildHappinessPresentation(presentation, "en-US");

        assertTrue(tooltip.startsWith("Happiness: 62% (base 50%, target 67%)"));
        assertTrue(tooltip.indexOf("Active effects") < tooltip.indexOf("All effects"));
        assertTrue(tooltip.contains("Owner nearby: +5.00"));
        assertTrue(tooltip.contains("Hungry: -8.00"));
        assertTrue(tooltip.contains("Food effects are exclusive"));
        assertFalse(tooltip.substring(tooltip.indexOf("All effects")).contains("Owner nearby"));
    }

    @Test
    void explainsExclusiveFoodWhenThereAreNoInactiveEffects() {
        var presentation = new CompanionHappinessPresentationService.PresentationSnapshot(
                62, 0, 100, 50, 55,
                List.of(new CompanionHappinessPresentationService.EffectEntry(
                        "food:apple", "Apple", 5,
                        CompanionHappinessPresentationService.EffectKind.FOOD)),
                List.of(), true);
        String tooltip = newService().buildHappinessPresentation(presentation, "en-US");
        assertTrue(tooltip.contains("Food effects are exclusive"));
        assertFalse(tooltip.contains("All effects"));
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
