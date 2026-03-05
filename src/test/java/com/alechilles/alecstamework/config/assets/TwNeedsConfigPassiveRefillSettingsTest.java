package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests passive-refill vertical scan radius defaults and sanitization. */
class TwNeedsConfigPassiveRefillSettingsTest {

    @Test
    void defaultsExposeExpectedVerticalScanRadii() {
        TwNeedsConfig.PassiveRefillSettings settings = new TwNeedsConfig.PassiveRefillSettings();

        assertEquals(2, settings.getContainerVerticalScanRadius());
        assertEquals(1, settings.getWaterVerticalScanRadius());
    }

    @Test
    void negativeVerticalScanRadiiClampToZero() throws Exception {
        TwNeedsConfig.PassiveRefillSettings settings = new TwNeedsConfig.PassiveRefillSettings();
        setField(settings, "containerVerticalScanRadius", -4);
        setField(settings, "waterVerticalScanRadius", -9);

        assertEquals(0, settings.getContainerVerticalScanRadius());
        assertEquals(0, settings.getWaterVerticalScanRadius());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
