package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TwFoodConfigTest {

    @Test
    void roleOverrideCategoryReplacesFamilyCategory() throws Exception {
        TwFoodConfig config = config(
                foods(new String[] { "Plant_Crop_Lettuce_Item" }, new String[0], new String[] { "Tw_Feed_Herbivore" }, new String[0]),
                happiness(6.0, 10.0, 2.0, -10.0)
        );
        setField(config, "roleOverrides", Map.of(
                "Example_Stag",
                overrideFood(null, null, new String[] { "Plant_Crop_Wheat_Item" }, null)
        ));

        TwFoodConfig.ResolvedFoodProfile profile = config.resolveProfile("Example_Stag");

        assertArrayEquals(
                new String[] { "Plant_Crop_Lettuce_Item", "Plant_Crop_Wheat_Item" },
                profile.acceptedItemIds()
        );
        assertArrayEquals(
                new String[] { "Plant_Crop_Wheat_Item" },
                entriesFor(profile, TwFoodConfig.FoodCategory.Compatible)
        );
    }

    @Test
    void roleOverridePreferredKeepsFamilyCompatibleWhenCompatibleIsOmitted() throws Exception {
        TwFoodConfig config = config(
                foods(new String[] { "Plant_Crop_Lettuce_Item" }, new String[0], new String[] { "Tw_Feed_Herbivore" }, new String[0]),
                happiness(6.0, 10.0, 2.0, -10.0)
        );
        setField(config, "roleOverrides", Map.of(
                "Example_Stag",
                overrideFood(new String[] { "Plant_Crop_Wheat_Item" }, null, null, null)
        ));

        TwFoodConfig.ResolvedFoodProfile profile = config.resolveProfile("Example_Stag");

        assertArrayEquals(
                new String[] { "Plant_Crop_Wheat_Item", "Tw_Feed_Herbivore" },
                profile.acceptedItemIds()
        );
    }

    @Test
    void needsConsumeOrderIsPremiumPreferredCompatibleDisliked() throws Exception {
        TwFoodConfig config = config(
                foods(
                        new String[] { "Preferred_Item" },
                        new String[] { "Premium_Item" },
                        new String[] { "Compatible_Item" },
                        new String[] { "Disliked_Item" }
                ),
                happiness(6.0, 10.0, 2.0, -10.0)
        );

        assertArrayEquals(
                new String[] { "Premium_Item", "Preferred_Item", "Compatible_Item", "Disliked_Item" },
                config.resolveProfile("Any_Role").needsConsumeItemIds()
        );
    }

    @Test
    void happinessValuesResolveByCategory() throws Exception {
        TwFoodConfig config = config(
                foods(
                        new String[] { "Preferred_Item" },
                        new String[] { "Premium_Item" },
                        new String[] { "Compatible_Item" },
                        new String[] { "Disliked_Item" }
                ),
                happiness(6.0, 10.0, 2.0, -10.0)
        );
        TwFoodConfig.ResolvedFoodProfile profile = config.resolveProfile("Any_Role");

        assertEquals(6.0, profile.resolveHappinessDelta("preferred_item"), 0.00001);
        assertEquals(10.0, profile.resolveHappinessDelta("Premium_Item"), 0.00001);
        assertEquals(2.0, profile.resolveHappinessDelta("Compatible_Item"), 0.00001);
        assertEquals(-10.0, profile.resolveHappinessDelta("Disliked_Item"), 0.00001);
    }

    @Test
    void foodInheritanceMergesNestedObjectsAndReplacesArrays() throws Exception {
        TwFoodConfig parent = config(
                foods(
                        new String[] { "Parent_Preferred" },
                        new String[] { "Parent_Premium" },
                        new String[] { "Parent_Compatible" },
                        new String[] { "Parent_Disliked" }
                ),
                happiness(1.0, 2.0, 3.0, 4.0)
        );
        TwFoodConfig child = config(
                foods(new String[] { "Child_Preferred" }, new String[0], new String[0], new String[0]),
                happiness(6.0, 10.0, 20.0, -10.0)
        );

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Foods", "Happiness"),
                Map.of(
                        "Foods", Set.of("Preferred"),
                        "Happiness", Set.of("Compatible")
                )
        );
        TwFoodConfig.ResolvedFoodProfile profile = child.resolveProfile("Any_Role");

        assertArrayEquals(
                new String[] { "Child_Preferred", "Parent_Premium", "Parent_Compatible", "Parent_Disliked" },
                profile.acceptedItemIds()
        );
        assertEquals(1.0, profile.resolveHappinessDelta("Child_Preferred"), 0.00001);
        assertEquals(2.0, profile.resolveHappinessDelta("Parent_Premium"), 0.00001);
        assertEquals(20.0, profile.resolveHappinessDelta("Parent_Compatible"), 0.00001);
        assertEquals(4.0, profile.resolveHappinessDelta("Parent_Disliked"), 0.00001);
    }

    private static String[] entriesFor(TwFoodConfig.ResolvedFoodProfile profile,
                                       TwFoodConfig.FoodCategory category) {
        return java.util.Arrays.stream(profile.displayEntries(true))
                .filter(entry -> entry.category() == category)
                .map(TwFoodConfig.FoodEntry::itemId)
                .toArray(String[]::new);
    }

    private static TwFoodConfig config(TwFoodConfig.FoodSettings foods,
                                       TwFoodConfig.HappinessSettings happiness) throws Exception {
        TwFoodConfig config = new TwFoodConfig();
        setField(config, "enabled", true);
        setField(config, "foods", foods);
        setField(config, "happiness", happiness);
        return config;
    }

    private static TwFoodConfig.FoodSettings foods(String[] preferred,
                                                   String[] premium,
                                                   String[] compatible,
                                                   String[] disliked) throws Exception {
        TwFoodConfig.FoodSettings settings = new TwFoodConfig.FoodSettings();
        if (preferred != null) setField(settings, "preferred", preferred);
        if (premium != null) setField(settings, "premium", premium);
        if (compatible != null) setField(settings, "compatible", compatible);
        if (disliked != null) setField(settings, "disliked", disliked);
        return settings;
    }

    private static TwFoodConfig.HappinessSettings happiness(double preferred,
                                                            double premium,
                                                            double compatible,
                                                            double disliked) throws Exception {
        TwFoodConfig.HappinessSettings settings = new TwFoodConfig.HappinessSettings();
        setField(settings, "preferred", preferred);
        setField(settings, "premium", premium);
        setField(settings, "compatible", compatible);
        setField(settings, "disliked", disliked);
        return settings;
    }

    private static TwFoodConfig.RoleOverrideSettings overrideFood(String[] preferred,
                                                                  String[] premium,
                                                                  String[] compatible,
                                                                  String[] disliked) throws Exception {
        TwFoodConfig.RoleOverrideSettings settings = new TwFoodConfig.RoleOverrideSettings();
        TwFoodConfig.FoodOverrideSettings foodOverride = new TwFoodConfig.FoodOverrideSettings();
        setField(foodOverride, "preferred", preferred);
        setField(foodOverride, "premium", premium);
        setField(foodOverride, "compatible", compatible);
        setField(foodOverride, "disliked", disliked);
        setField(settings, "foods", foodOverride);
        return settings;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
