package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightSpeedMetricsTest {
    private static final double EPSILON = 0.00001;
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void defaultConfigUsesBoostedCapForSpeedBalance() {
        assertEquals(15.0, AvatarFlightSpeedMetrics.glideHorizontalCap(CONFIG), EPSILON);
        assertEquals(21.0, AvatarFlightSpeedMetrics.boostedHorizontalCap(CONFIG), EPSILON);
        assertEquals(16.8, AvatarFlightSpeedMetrics.fastFlightThreshold(CONFIG), EPSILON);
        assertEquals(2.0 / 3.0, AvatarFlightSpeedMetrics.speedRatio(14.0, CONFIG), EPSILON);
        assertTrue(AvatarFlightSpeedMetrics.glideHorizontalCap(CONFIG)
                < AvatarFlightSpeedMetrics.fastFlightThreshold(CONFIG));
        assertFalse(AvatarFlightSpeedMetrics.isFastFlightSpeed(16.79, CONFIG));
        assertTrue(AvatarFlightSpeedMetrics.isFastFlightSpeed(16.8, CONFIG));
    }

    @Test
    void horizontalSpeedIgnoresVerticalVelocityAndSanitizesHorizontalComponents() {
        assertEquals(5.0, AvatarFlightSpeedMetrics.horizontalSpeed(3.0, 1000.0, 4.0), EPSILON);
        assertEquals(5.0, AvatarFlightSpeedMetrics.horizontalSpeed(3.0, Double.POSITIVE_INFINITY, 4.0), EPSILON);
        assertEquals(4.0, AvatarFlightSpeedMetrics.horizontalSpeed(Double.NaN, 1000.0, 4.0), EPSILON);
        assertEquals(3.0, AvatarFlightSpeedMetrics.horizontalSpeed(3.0, 1000.0, Double.NEGATIVE_INFINITY), EPSILON);
        assertEquals(0.0, AvatarFlightSpeedMetrics.horizontalSpeed(Double.NaN, 1000.0, Double.POSITIVE_INFINITY), EPSILON);
    }

    @Test
    void nullConfigHasNoRechargeSpeedCap() {
        assertEquals(0.0, AvatarFlightSpeedMetrics.glideHorizontalCap(null), EPSILON);
        assertEquals(0.0, AvatarFlightSpeedMetrics.boostedHorizontalCap(null), EPSILON);
        assertEquals(0.0, AvatarFlightSpeedMetrics.speedRatio(14.0, null), EPSILON);
        assertEquals(0.0, AvatarFlightSpeedMetrics.fastFlightThreshold(null), EPSILON);
        assertFalse(AvatarFlightSpeedMetrics.isFastFlightSpeed(100.0, null));
    }
}
