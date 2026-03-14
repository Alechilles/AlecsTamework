package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression tests for minute-based breeding config conversion paths. */
class TwBreedingConfigMinutesConversionTest {

    @Test
    void minutesToSecondsSupportsNullFallbackForMinuteOnlyOverrides() throws Exception {
        Method method = TwBreedingConfig.class.getDeclaredMethod("minutesToSeconds", int.class, Integer.class);
        method.setAccessible(true);

        int converted = (int) method.invoke(null, 90, null);

        assertEquals(5400, converted);
    }

    @Test
    void minutesToSecondsUsesFallbackWhenMinutesAreNegative() throws Exception {
        Method method = TwBreedingConfig.class.getDeclaredMethod("minutesToSeconds", int.class, Integer.class);
        method.setAccessible(true);

        int converted = (int) method.invoke(null, -1, 1200);

        assertEquals(1200, converted);
    }
}
