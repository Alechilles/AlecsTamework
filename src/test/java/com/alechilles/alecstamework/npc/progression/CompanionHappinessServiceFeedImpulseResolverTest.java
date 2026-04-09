package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionHappinessServiceFeedImpulseResolverTest {

    @Test
    void resolveFeedImpulseTotalUsesMappedItemOverride() throws Exception {
        TwHappinessConfig config = configWithFeedImpulses(5.0, Map.of("Tw_Feed_Herbivore", -10.0));

        double total = CompanionHappinessService.resolveFeedImpulseTotal(
                config,
                5.0,
                Map.of("Tw_Feed_Herbivore", 1)
        );

        assertEquals(-10.0, total, 0.00001);
    }

    @Test
    void resolveFeedImpulseTotalDoesNotFallbackToGainOnFeedForUnmappedItems() throws Exception {
        TwHappinessConfig config = configWithFeedImpulses(5.0, Map.of("Tw_Feed_Herbivore", -10.0));

        double total = CompanionHappinessService.resolveFeedImpulseTotal(
                config,
                5.0,
                Map.of("Plant_Crop_Wheat_Item", 2)
        );

        assertEquals(0.0, total, 0.00001);
    }

    @Test
    void resolveFeedImpulseTotalDoesNotStackWhenSameItemIsConsumedMultipleTimes() throws Exception {
        TwHappinessConfig config = configWithFeedImpulses(5.0, Map.of("Tw_Feed_Herbivore", -10.0));

        double total = CompanionHappinessService.resolveFeedImpulseTotal(
                config,
                5.0,
                Map.of(
                        "Tw_Feed_Herbivore", 2,
                        "Plant_Crop_Wheat_Item", 3
                )
        );

        assertEquals(-10.0, total, 0.00001);
    }

    private static TwHappinessConfig configWithFeedImpulses(double gainOnFeed,
                                                            Map<String, Double> feedItemImpulses) throws Exception {
        var ctor = TwHappinessConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwHappinessConfig config = ctor.newInstance();
        TwHappinessConfig.ImpulseSettings impulses = new TwHappinessConfig.ImpulseSettings();
        setField(config, "enabled", true);
        setField(config, "impulses", impulses);
        setField(impulses, "gainOnFeed", gainOnFeed);
        setField(impulses, "feedItemImpulses", feedItemImpulses);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
