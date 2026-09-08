package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelEntryServiceHappinessTooltipTest {

    @Test
    void savedFoodEffectsUseProfileCategoriesWithoutItemNames() throws Exception {
        var constructor = TwFoodConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwFoodConfig config = constructor.newInstance();
        TwFoodConfig.FoodSettings foods = new TwFoodConfig.FoodSettings();
        for (String category : List.of("premium", "preferred", "compatible", "disliked")) {
            var field = TwFoodConfig.FoodSettings.class.getDeclaredField(category);
            field.setAccessible(true);
            field.set(foods, new String[] { "Example_" + category });
        }
        var foodsField = TwFoodConfig.class.getDeclaredField("foods");
        foodsField.setAccessible(true);
        foodsField.set(config, foods);
        var impulses = new java.util.ArrayList<CompanionHappinessService.ActiveImpulseSnapshot>();
        for (String category : List.of("premium", "preferred", "compatible", "disliked")) {
            // Equal values ensure labels come from the diet, not assumed tuning numbers.
            impulses.add(new CompanionHappinessService.ActiveImpulseSnapshot(
                    "feed:food:example_" + category, "Ate", 5.0, Long.MAX_VALUE, "Example_" + category));
        }
        impulses.add(new CompanionHappinessService.ActiveImpulseSnapshot(
                "feed:hand", "Hand-fed", 3.0, Long.MAX_VALUE, "Example_premium"));
        var snapshot = new CompanionHappinessService.HappinessSnapshot(
                60, 0, 100, 50, 55, List.of(), impulses);

        String breakdown = newService().buildHappinessModifierBreakdown(
                snapshot, null, config.resolveProfile("Example_Animal"));

        for (String label : List.of("Premium Feed", "Favorite Food", "Generic Feed", "Disliked Food")) {
            assertTrue(breakdown.contains(label + ": +5.00"), breakdown);
        }
        assertTrue(breakdown.contains("Hand-fed: +3.00"));
        assertFalse(breakdown.contains("Ate"));
        assertFalse(breakdown.contains("Example_"));
    }

    @Test
    void breakdownIncludesOnlyActiveFeedImpulses() {
        CommandLoadedNpcStatusSnapshotService service = newService();
        CompanionHappinessService.HappinessSnapshot snapshot = new CompanionHappinessService.HappinessSnapshot(
                60.0,
                0.0,
                100.0,
                50.0,
                55.0,
                List.of(new CompanionHappinessModifierService.ModifierEntry("owner_nearby", "Owner Nearby", 5.0)),
                List.of(
                        new CompanionHappinessService.ActiveImpulseSnapshot(
                                "feed:param:foodgeneric",
                                "Ate",
                                -10.0,
                                System.currentTimeMillis() + 60_000L,
                                "Tw_Feed_Herbivore"
                        )
                )
        );

        String breakdown = service.buildHappinessModifierBreakdown(snapshot);

        assertTrue(breakdown.contains("Owner nearby: +5.00"));
        assertTrue(breakdown.contains("Generic Feed: -10.00"));
        assertFalse(breakdown.contains("Feed (default):"));
        assertFalse(breakdown.contains("Base:"));
        assertFalse(breakdown.contains("Target:"));
    }

    @Test
    void breakdownOmitsFeedLinesWhenNoActiveImpulsesExist() {
        CommandLoadedNpcStatusSnapshotService service = newService();
        CompanionHappinessService.HappinessSnapshot snapshot = new CompanionHappinessService.HappinessSnapshot(
                50.0,
                0.0,
                100.0,
                50.0,
                50.0,
                List.of()
        );

        String breakdown = service.buildHappinessModifierBreakdown(snapshot);

        assertFalse(breakdown != null && breakdown.contains("Ate "));
        assertTrue(breakdown == null || breakdown.isBlank());
    }

    private static CommandLoadedNpcStatusSnapshotService newService() {
        return new CommandLoadedNpcStatusSnapshotService(null, null, null, null);
    }
}
