package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedItemPreferenceResolverTest {

    @Test
    void scoreUsesConfiguredFeedItemImpulse() throws Exception {
        TwHappinessConfig config = configWithFeedImpulses(Map.of(
                "tw_feed_herbivore", -8.0,
                "plant_crop_tomato_item", 5.0
        ));
        FeedItemPreferenceResolver resolver = FeedItemPreferenceResolver.create(config);

        assertEquals(5.0, resolver.score("Plant_Crop_Tomato_Item"), 0.00001);
        assertEquals(-8.0, resolver.score("Tw_Feed_Herbivore"), 0.00001);
    }

    @Test
    void scoreFallsBackToZeroWhenItemHasNoImpulseMapping() throws Exception {
        TwHappinessConfig config = configWithFeedImpulses(Map.of("tw_feed_herbivore", -8.0));
        FeedItemPreferenceResolver resolver = FeedItemPreferenceResolver.create(config);

        assertEquals(0.0, resolver.score("Plant_Crop_Wheat_Item"), 0.00001);
    }

    @Test
    void scoreUsesFoodProfileBeforeHappinessConfigFallback() throws Exception {
        FeedItemPreferenceResolver resolver = FeedItemPreferenceResolver.create(profile(
                new String[] { "Plant_Crop_Lettuce_Item" },
                new String[] { "Tw_Feed_Premium_Herbivore" },
                new String[] { "Tw_Feed_Herbivore" },
                new String[] { "Tw_Feed_Generic" }
        ));

        assertEquals(10.0, resolver.score("Tw_Feed_Premium_Herbivore"), 0.00001);
        assertEquals(6.0, resolver.score("Plant_Crop_Lettuce_Item"), 0.00001);
        assertEquals(2.0, resolver.score("Tw_Feed_Herbivore"), 0.00001);
        assertEquals(-10.0, resolver.score("Tw_Feed_Generic"), 0.00001);
        assertEquals(0.0, resolver.score("Unknown_Item"), 0.00001);
    }

    private static TwHappinessConfig configWithFeedImpulses(Map<String, Double> feedItemImpulses) throws Exception {
        var ctor = TwHappinessConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwHappinessConfig config = ctor.newInstance();
        TwHappinessConfig.ImpulseSettings impulses = new TwHappinessConfig.ImpulseSettings();
        setField(config, "enabled", true);
        setField(config, "impulses", impulses);
        setField(impulses, "feedItemImpulses", feedItemImpulses);
        return config;
    }

    private static TwFoodConfig.ResolvedFoodProfile profile(String[] preferred,
                                                            String[] premium,
                                                            String[] compatible,
                                                            String[] disliked) throws Exception {
        var ctor = TwFoodConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwFoodConfig config = ctor.newInstance();
        TwFoodConfig.FoodSettings foods = new TwFoodConfig.FoodSettings();
        setField(foods, "preferred", preferred);
        setField(foods, "premium", premium);
        setField(foods, "compatible", compatible);
        setField(foods, "disliked", disliked);
        setField(config, "foods", foods);
        return config.resolveProfile("Any_Role");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
