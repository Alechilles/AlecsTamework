package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightLaunchVfxMathTest {
    private static final double EPSILON = 0.00001;

    @Test
    void chargeProgressAndPulseTuningClampAtBoundaries() {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        assertEquals(0.0, AvatarFlightLaunchVfxMath.chargeProgress(-10L, 1000L), EPSILON);
        assertEquals(0.5, AvatarFlightLaunchVfxMath.chargeProgress(500L, 1000L), EPSILON);
        assertEquals(1.0, AvatarFlightLaunchVfxMath.chargeProgress(2000L, 1000L), EPSILON);
        assertEquals(600L, AvatarFlightLaunchVfxMath.pulseIntervalMs(config.getVfx(), 0.0));
        assertEquals(375L, AvatarFlightLaunchVfxMath.pulseIntervalMs(config.getVfx(), 0.5));
        assertEquals(150L, AvatarFlightLaunchVfxMath.pulseIntervalMs(config.getVfx(), 1.0));
        assertEquals(0.85, AvatarFlightLaunchVfxMath.pulseScale(config.getVfx(), 0.0), EPSILON);
        assertEquals(1.50, AvatarFlightLaunchVfxMath.pulseScale(config.getVfx(), 1.0), EPSILON);
    }

    @Test
    void releaseTierUsesLaunchCurveAndExactConfiguredBoundaries() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getLaunch(), "minChargeMs", 0.0);
        setField(config.getLaunch(), "maxChargeMs", 1000.0);
        setField(config.getLaunch(), "chargeExponent", 1.0);

        assertEquals(AvatarFlightLaunchVfxMath.ReleaseTier.PARTIAL,
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), config.getVfx(), 449L));
        assertEquals(AvatarFlightLaunchVfxMath.ReleaseTier.MID,
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), config.getVfx(), 450L));
        assertEquals(AvatarFlightLaunchVfxMath.ReleaseTier.FULL,
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), config.getVfx(), 800L));
    }

    @Test
    void releaseTierRespectsConfiguredLaunchExponent() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getLaunch(), "minChargeMs", 0.0);
        setField(config.getLaunch(), "maxChargeMs", 1000.0);
        setField(config.getLaunch(), "chargeExponent", 2.0);

        assertEquals(AvatarFlightLaunchVfxMath.ReleaseTier.PARTIAL,
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), config.getVfx(), 600L));
        assertEquals(AvatarFlightLaunchVfxMath.ReleaseTier.MID,
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), config.getVfx(), 700L));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
