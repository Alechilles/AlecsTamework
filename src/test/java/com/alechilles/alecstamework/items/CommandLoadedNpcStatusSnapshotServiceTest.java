package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandLoadedNpcStatusSnapshotServiceTest {
    @Test
    void happinessTooltipCombinesOnlyActiveCareContributions() {
        var service = new CommandLoadedNpcStatusSnapshotService(null, null, null, null);
        var modifiers = java.util.List.of(
                new com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService.ModifierEntry(
                        "hunger_care", "Husbandry Care: Hunger", 5.6),
                new com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService.ModifierEntry(
                        "population_care", "Husbandry Care: Population", 3.7));
        var snapshot = new com.alechilles.alecstamework.npc.progression.CompanionHappinessService.HappinessSnapshot(
                50, 0, 100, 34, 43.3, modifiers);

        Assertions.assertEquals("Caretaking: +9.30", service.buildHappinessModifierBreakdown(snapshot, "en-US"));
    }

    @Test
    void computePercentClampsToZeroAndHundred() {
        Assertions.assertEquals(0, CommandLoadedNpcStatusSnapshotService.computePercentForTests(-10.0, 0.0, 100.0));
        Assertions.assertEquals(50, CommandLoadedNpcStatusSnapshotService.computePercentForTests(50.0, 0.0, 100.0));
        Assertions.assertEquals(100, CommandLoadedNpcStatusSnapshotService.computePercentForTests(150.0, 0.0, 100.0));
    }

    @Test
    void formatSignedUsesExplicitPositiveSign() {
        Assertions.assertEquals("+1.25", CommandLoadedNpcStatusSnapshotService.formatSignedForTests(1.25));
        Assertions.assertEquals("-0.50", CommandLoadedNpcStatusSnapshotService.formatSignedForTests(-0.5));
    }
}
