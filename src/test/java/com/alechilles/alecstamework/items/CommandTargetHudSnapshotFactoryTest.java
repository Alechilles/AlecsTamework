package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that target HUD data is detached without losing rendered fields. */
class CommandTargetHudSnapshotFactoryTest {
    private static final UUID TARGET = UUID.fromString(
            "b8b6a5d2-bab4-4a87-a7d3-88b9cfed2e6d");

    @Test
    void copiesIdentityVitalsCooldownsRowsProgressionTraitsAndOwner() {
        LinkedNpcEntry status = new LinkedNpcEntry(
                TARGET,
                "Moss",
                "Female",
                80,
                100,
                65,
                100,
                70,
                "modifier details",
                40,
                60,
                30,
                50,
                true,
                true,
                false,
                false,
                false,
                false,
                0L,
                null,
                new LinkedNpcEntry.FutureStat("Level 7 XP", 120, 300,
                        "Level: 7/20 - 120/300 XP", "Level modifiers"),
                new LinkedNpcEntry.FutureStat("Talent Points", 3, 10),
                new LinkedNpcTraitIndicator[]{
                        new LinkedNpcTraitIndicator(
                                "A", "Traits/A.png", "Brave", "Brave trait", 0.75, true, false)
                },
                true,
                true,
                true,
                true,
                true,
                true,
                "runeteria:stag",
                "Stag",
                "forest",
                "Forest Companions",
                "#4A8F55",
                true,
                true,
                true,
                4_000L,
                0.4,
                true,
                true,
                2_000L,
                0.2,
                true,
                false,
                0L
        );
        CommandTargetHudViewModel model = new CommandTargetHudViewModel(
                status,
                new CommandTargetHudViewModel.FoodRow(
                        "Food:Apple", "Apple", "Items/Apple.png", 4.0),
                List.of(new CommandTargetHudViewModel.FoodRow(
                        "Food:Berry", "Berry", "Items/Berry.png", -1.5)),
                List.of(new CommandTargetHudViewModel.AttachmentRow("Coat", "Mossy")),
                new CommandTargetHudViewModel.TameRequirementRow(true, 5, "2/5"),
                "Alec"
        );

        CommandTargetHudSnapshot snapshot = new CommandTargetHudSnapshotFactory()
                .create(model, "target-key");

        assertEquals(TARGET, snapshot.targetUuid());
        assertEquals("target-key", snapshot.targetKey());
        assertEquals("Moss", snapshot.displayName());
        assertEquals("runeteria:stag", snapshot.speciesId());
        assertEquals("Stag", snapshot.speciesLabel());
        assertEquals("Female", snapshot.gender());
        assertEquals("loaded", snapshot.lifecycleStatus());
        assertEquals(80, snapshot.vitals().currentHealth());
        assertEquals(100, snapshot.vitals().maxHealth());
        assertEquals(65, snapshot.vitals().currentHappiness());
        assertEquals(70, snapshot.vitals().targetHappinessPercent());
        assertEquals(40, snapshot.vitals().currentHunger());
        assertEquals(50, snapshot.vitals().maxThirst());
        assertEquals(2_000L, snapshot.cooldowns().harvest().remainingMillis());
        assertEquals(4_000L, snapshot.cooldowns().breeding().remainingMillis());
        assertEquals("Food:Apple", snapshot.favoriteFood().itemId());
        assertEquals("Food:Berry", snapshot.compatibleFoods().get(0).itemId());
        assertEquals("Coat: Mossy", snapshot.attachments().get(0).displayLine());
        assertEquals(5, snapshot.tameRequirement().requiredStacks());
        assertEquals(7, snapshot.progression().level());
        assertEquals(120L, snapshot.progression().experience());
        assertEquals(300L, snapshot.progression().experienceToNextLevel());
        assertEquals(3, snapshot.progression().availableTalentPoints());
        assertEquals(20, snapshot.progression().maxLevel());
        assertEquals(false, snapshot.progression().atMaxLevel());
        assertEquals("Level: 7/20 - 120/300 XP",
                snapshot.progression().tooltipHeaderText());
        assertEquals("Level modifiers", snapshot.progression().tooltipText());
        assertEquals("modifier details", snapshot.happinessModifierBreakdown());
        assertEquals("Brave", snapshot.traits().get(0).label());
        assertEquals("trait-0", snapshot.traits().get(0).id());
        assertEquals("Alec", snapshot.ownerDisplayName());
    }

    @Test
    void omitsNeedsWhenStatusDoesNotHaveLoadedValues() {
        LinkedNpcEntry status = new LinkedNpcEntry(
                UUID.randomUUID(), "Unloaded", 10, 100, 10, 100,
                null, 10, 100, 10, 100, false, false, false, true,
                false, false, 0L, null);

        CommandTargetHudSnapshot snapshot = new CommandTargetHudSnapshotFactory().create(
                new CommandTargetHudViewModel(status, null, List.of(), List.of(), null, null));

        assertNull(snapshot.vitals().currentHealth());
        assertNull(snapshot.vitals().currentHappiness());
        assertNull(snapshot.vitals().currentHunger());
        assertNull(snapshot.vitals().currentThirst());
        assertEquals("captured", snapshot.lifecycleStatus());
    }

    @Test
    void mapsMaxLevelAndMaxLevelTooltipState() {
        LinkedNpcEntry status = new LinkedNpcEntry(
                TARGET, "Moss", 80, 100, 65, 100, "modifier details",
                40, 60, 30, 50, true, true, false, false, false, false, 0L,
                new LinkedNpcEntry.FutureStat("Level 20 MAX", 1, 1,
                        "Level: 20/20 - MAX XP", "Max-level modifiers"),
                null, null, false, false, false, false);

        CommandTargetHudSnapshot snapshot = new CommandTargetHudSnapshotFactory().create(
                new CommandTargetHudViewModel(status, null, List.of(), List.of(), null, null));

        assertEquals(20, snapshot.progression().level());
        assertEquals(20, snapshot.progression().maxLevel());
        assertTrue(snapshot.progression().atMaxLevel());
        assertEquals("Level: 20/20 - MAX XP",
                snapshot.progression().tooltipHeaderText());
        assertEquals("Max-level modifiers", snapshot.progression().tooltipText());
    }
}
