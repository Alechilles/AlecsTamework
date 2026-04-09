package com.alechilles.alecstamework.npc.progression;

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

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
