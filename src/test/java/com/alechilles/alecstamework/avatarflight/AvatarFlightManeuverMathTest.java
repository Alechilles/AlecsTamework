package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightManeuverMathTest {
    private static final double EPSILON = 0.00001;
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void diveLoadRampsAndDecays() {
        double load = 0.0;
        load = AvatarFlightManeuverMath.updateLoad(
                load,
                true,
                0.4,
                CONFIG.getCurve().getDiveLoadRampSeconds(),
                CONFIG.getCurve().getDiveLoadDecaySeconds()
        );

        assertEquals(0.25, load, EPSILON);

        load = AvatarFlightManeuverMath.updateLoad(
                load,
                false,
                0.3,
                CONFIG.getCurve().getDiveLoadRampSeconds(),
                CONFIG.getCurve().getDiveLoadDecaySeconds()
        );

        assertEquals(0.0, load, EPSILON);
    }

    @Test
    void pitchPowerMakesShortShallowDiveWeak() {
        double shallowDivePower = AvatarFlightManeuverMath.pitchPower(
                Math.toRadians(-45.0),
                true,
                CONFIG.getCurve().getDivePitchExponent()
        );
        double steepDivePower = AvatarFlightManeuverMath.pitchPower(
                Math.toRadians(-70.0),
                true,
                CONFIG.getCurve().getDivePitchExponent()
        );

        assertEquals(Math.pow(45.0 / 70.0, 1.55), shallowDivePower, EPSILON);
        assertTrue(shallowDivePower < steepDivePower);
        assertTrue(shallowDivePower < 0.55);
        assertEquals(1.0, steepDivePower, EPSILON);
        assertEquals(0.0, AvatarFlightManeuverMath.pitchPower(
                Math.toRadians(45.0),
                true,
                CONFIG.getCurve().getDivePitchExponent()
        ), EPSILON);
    }

    @Test
    void climbEligibilityUsesSpeedAboveNeutral() {
        assertEquals(0.0, AvatarFlightManeuverMath.climbEligibility(5.99, CONFIG), EPSILON);
        assertEquals(0.0, AvatarFlightManeuverMath.climbEligibility(
                CONFIG.getMovement().getNeutralGlideSpeed(),
                CONFIG
        ), EPSILON);
        assertEquals(Math.sqrt(0.5), AvatarFlightManeuverMath.climbEligibility(10.5, CONFIG), EPSILON);
        assertEquals(1.0, AvatarFlightManeuverMath.climbEligibility(
                CONFIG.getMovement().getMaxGlideSpeed(),
                CONFIG
        ), EPSILON);
    }

    @Test
    void boostedExcessDecaysTowardNaturalCap() {
        assertEquals(18.0, AvatarFlightManeuverMath.decayBoostedExcess(21.0, CONFIG, 1.5), EPSILON);
        assertEquals(15.0, AvatarFlightManeuverMath.decayBoostedExcess(16.0, CONFIG, 1.0), EPSILON);
        assertEquals(14.0, AvatarFlightManeuverMath.decayBoostedExcess(14.0, CONFIG, 1.0), EPSILON);
        assertEquals(0.0, AvatarFlightManeuverMath.decayBoostedExcess(-1.0, CONFIG, 1.0), EPSILON);
    }
}
