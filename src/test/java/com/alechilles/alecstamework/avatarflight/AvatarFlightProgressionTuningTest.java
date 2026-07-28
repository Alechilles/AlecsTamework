package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightProgressionTuningTest {
    private static final double EPSILON = 0.00001;

    @Test
    void neutralFallbackUsesOneForEveryMultiplier() {
        AvatarFlightProgressionTuning tuning = AvatarFlightProgressionTuning.resolve(null, null);

        assertEquals(1.0, tuning.vigourCapacityMultiplier(), EPSILON);
        assertEquals(1.0, tuning.vigourRechargeRateMultiplier(), EPSILON);
        assertEquals(1.0, tuning.forwardBoostCostMultiplier(), EPSILON);
        assertEquals(1.0, tuning.forwardBoostImpulseMultiplier(), EPSILON);
        assertEquals(1.0, tuning.glideSinkMultiplier(), EPSILON);
        assertEquals(1.0, tuning.climbLiftMultiplier(), EPSILON);
    }

    @Test
    void constructorClampsEveryMultiplierToItsSafeRange() {
        AvatarFlightProgressionTuning upperBounds = new AvatarFlightProgressionTuning(
                9.0, -2.0, -2.0, 9.0, -2.0, 9.0);
        AvatarFlightProgressionTuning lowerBounds = new AvatarFlightProgressionTuning(
                -2.0, 9.0, 9.0, -2.0, 9.0, -2.0);

        assertEquals(1.35, upperBounds.vigourCapacityMultiplier(), EPSILON);
        assertEquals(1.0, upperBounds.vigourRechargeRateMultiplier(), EPSILON);
        assertEquals(0.70, upperBounds.forwardBoostCostMultiplier(), EPSILON);
        assertEquals(1.25, upperBounds.forwardBoostImpulseMultiplier(), EPSILON);
        assertEquals(0.70, upperBounds.glideSinkMultiplier(), EPSILON);
        assertEquals(1.25, upperBounds.climbLiftMultiplier(), EPSILON);
        assertEquals(1.0, lowerBounds.vigourCapacityMultiplier(), EPSILON);
        assertEquals(1.35, lowerBounds.vigourRechargeRateMultiplier(), EPSILON);
        assertEquals(1.0, lowerBounds.forwardBoostCostMultiplier(), EPSILON);
        assertEquals(1.0, lowerBounds.forwardBoostImpulseMultiplier(), EPSILON);
        assertEquals(1.0, lowerBounds.glideSinkMultiplier(), EPSILON);
        assertEquals(1.0, lowerBounds.climbLiftMultiplier(), EPSILON);
    }
}
