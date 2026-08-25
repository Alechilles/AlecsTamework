package com.alechilles.alecstamework.api.commandhud;

import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for detached snapshots, views, and update hints. */
class CommandHudSnapshotTest {
    @Test
    void targetSnapshotCopiesMutableRowsAndTraits() {
        List<CommandTargetHudSnapshot.FoodRow> foods = new ArrayList<>();
        foods.add(new CommandTargetHudSnapshot.FoodRow(
                "food:hay", "Hay", null, null));
        List<CommandTargetHudSnapshot.Trait> traits = new ArrayList<>();
        traits.add(new CommandTargetHudSnapshot.Trait("calm", "Calm", null));

        CommandTargetHudSnapshot snapshot = new CommandTargetHudSnapshot(
                UUID.randomUUID(), "target-1", "Milo", "npc:wolf", "Wolf", "Male",
                "ACTIVE",
                new CommandTargetHudSnapshot.Vitals(10, 20, 5, 10, 8, 10, 7, 10),
                new CommandTargetHudSnapshot.Cooldowns(
                        new CommandTargetHudSnapshot.Cooldown(true, 100L, 0.5, true),
                        null),
                foods.get(0), foods, List.of(), null,
                new CommandTargetHudSnapshot.Progression(2, 30L, 100L, 1),
                traits, "Alec");
        foods.clear();
        traits.clear();

        assertEquals(1, snapshot.compatibleFoods().size());
        assertEquals(1, snapshot.traits().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.compatibleFoods().clear());
    }

    @Test
    void viewsCopyContributionMapsAndFullChangesCoverEveryRegion() {
        CommandTargetHudSnapshot target = minimalTarget();
        Map<CommandHudContributorId, CommandHudContribution> contributions =
                new LinkedHashMap<>();
        CommandHudContributorId contributorId =
                CommandHudContributorId.of("runeteria:badge");
        contributions.put(contributorId, CommandHudContribution.available(
                contributorId, Map.of("ready", CommandUiValue.of(true))));

        CommandTargetHudView view = new CommandTargetHudView(target, contributions);
        contributions.clear();
        assertTrue(view.contributions().containsKey(contributorId));
        assertThrows(UnsupportedOperationException.class,
                () -> view.contributions().clear());

        CommandTargetHudChangeSet targetChanges = CommandTargetHudChangeSet.full();
        assertTrue(targetChanges.fullRefresh());
        assertEquals(CommandTargetHudChangeSet.Section.all(),
                targetChanges.changedSections());

        CommandHotswapHudChangeSet hotswapChanges = CommandHotswapHudChangeSet.full();
        assertTrue(hotswapChanges.fullRefresh());
        assertEquals(Set.of(CommandHotswapHudChangeSet.Slot.PRIMARY,
                        CommandHotswapHudChangeSet.Slot.SECONDARY,
                        CommandHotswapHudChangeSet.Slot.Q,
                        CommandHotswapHudChangeSet.Slot.E,
                        CommandHotswapHudChangeSet.Slot.R),
                hotswapChanges.changedSlots());
        assertTrue(hotswapChanges.groupStatusChanged());
    }

    private static CommandTargetHudSnapshot minimalTarget() {
        return new CommandTargetHudSnapshot(
                UUID.randomUUID(), "target-1", "Milo", null, null, null, "ACTIVE",
                new CommandTargetHudSnapshot.Vitals(null, null, null, null,
                        null, null, null, null),
                new CommandTargetHudSnapshot.Cooldowns(null, null), null,
                List.of(), List.of(), null, null, List.of(), null);
    }
}
