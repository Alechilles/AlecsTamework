package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightLaunchSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightLaunchCurveTest {
    private static final double EPSILON = 0.00001;
    private static final AvatarFlightLaunchSettings SETTINGS = TwAvatarFlightConfig.defaultConfig().getLaunch();

    @Test
    void chargeIsZeroBelowThresholdAndOneAtMax() {
        assertEquals(0.0, AvatarFlightLaunchCurve.charge(SETTINGS, 499L), EPSILON);
        assertEquals(0.0, AvatarFlightLaunchCurve.charge(SETTINGS, 500L), EPSILON);
        assertEquals(1.0, AvatarFlightLaunchCurve.charge(SETTINGS, 3000L), EPSILON);
        assertEquals(1.0, AvatarFlightLaunchCurve.charge(SETTINGS, 4000L), EPSILON);
    }

    @Test
    void launchImpulseMatchesStoryboardSamples() {
        assertImpulse(500L, 6.0, 6.0, 0.05);
        assertImpulse(1_000L, 10.2, 7.8, 0.05);
        assertImpulse(2_000L, 14.6, 9.6, 0.05);
        assertImpulse(3_000L, 18.0, 11.0, 0.05);

        AvatarFlightLaunchCurve.Impulse tap = AvatarFlightLaunchCurve.impulse(SETTINGS, 499L);
        assertEquals(0.0, tap.up(), EPSILON);
        assertEquals(0.0, tap.forward(), EPSILON);
        assertEquals(0.0, tap.charge(), EPSILON);
    }

    @Test
    void launchCostUsesFullCostThreshold() {
        assertEquals(0.0, AvatarFlightLaunchCurve.cost(SETTINGS, 499L), EPSILON);
        assertEquals(1.0, AvatarFlightLaunchCurve.cost(SETTINGS, 500L), EPSILON);
        assertEquals(1.0, AvatarFlightLaunchCurve.cost(SETTINGS, 1_000L), EPSILON);
        assertEquals(2.0, AvatarFlightLaunchCurve.cost(SETTINGS, 2_000L), EPSILON);
        assertEquals(2.0, AvatarFlightLaunchCurve.cost(SETTINGS, 3_000L), EPSILON);
    }

    private static void assertImpulse(long holdMs, double expectedUp, double expectedForward, double epsilon) {
        AvatarFlightLaunchCurve.Impulse impulse = AvatarFlightLaunchCurve.impulse(SETTINGS, holdMs);

        assertEquals(expectedUp, impulse.up(), epsilon);
        assertEquals(expectedForward, impulse.forward(), epsilon);
    }
}
