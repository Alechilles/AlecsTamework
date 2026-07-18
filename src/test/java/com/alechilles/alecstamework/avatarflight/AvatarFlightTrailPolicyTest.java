package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightTrailSettings;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers fast-glide trail boundary and hysteresis decisions. */
class AvatarFlightTrailPolicyTest {

    @Test
    void startsAtConfiguredNearMaxGlideThreshold() throws Exception {
        AvatarFlightTrailSettings settings = enabledSettings();

        assertFalse(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(false, 13.79, 15.0, settings));
        assertTrue(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(false, 13.8, 15.0, settings));
        assertTrue(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(false, 18.0, 15.0, settings));
    }

    @Test
    void runningTrailUsesLowerStopThresholdToAvoidFlicker() throws Exception {
        AvatarFlightTrailSettings settings = enabledSettings();

        assertTrue(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(true, 12.9, 15.0, settings));
        assertFalse(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(true, 12.89, 15.0, settings));
    }

    @Test
    void missingRootOrInvalidCapNeverStartsTrail() throws Exception {
        AvatarFlightTrailSettings blank = new AvatarFlightTrailSettings();
        AvatarFlightTrailSettings configured = enabledSettings();

        assertFalse(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(false, 20.0, 15.0, blank));
        assertFalse(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(false, 20.0, 0.0, configured));
        assertFalse(AvatarFlightTrailPolicy.shouldRunFastGlideTrail(false, Double.NaN, 15.0, configured));
    }

    private static AvatarFlightTrailSettings enabledSettings() throws Exception {
        AvatarFlightTrailSettings settings = new AvatarFlightTrailSettings();
        Field field = AvatarFlightTrailSettings.class.getDeclaredField("fastGlideRootInteraction");
        field.setAccessible(true);
        field.set(settings, "Root_Test_Fast_Glide");
        return settings;
    }
}
