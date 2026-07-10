package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAudioSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightLaunchAudioMathTest {
    private static final double EPSILON = 0.00001;

    @Test
    void chargePulseCadenceVolumeAndPitchInterpolateAndClamp() {
        AvatarFlightAudioSettings audio = TwAvatarFlightConfig.defaultConfig().getAudio();

        assertEquals(600L, AvatarFlightLaunchAudioMath.pulseIntervalMs(audio, -1.0));
        assertEquals(390L, AvatarFlightLaunchAudioMath.pulseIntervalMs(audio, 0.5));
        assertEquals(180L, AvatarFlightLaunchAudioMath.pulseIntervalMs(audio, 2.0));
        assertEquals(0.32f, AvatarFlightLaunchAudioMath.pulseVolume(audio, 0.0), EPSILON);
        assertEquals(0.61f, AvatarFlightLaunchAudioMath.pulseVolume(audio, 0.5), EPSILON);
        assertEquals(0.90f, AvatarFlightLaunchAudioMath.pulseVolume(audio, 1.0), EPSILON);
        assertEquals(0.85f, AvatarFlightLaunchAudioMath.pulsePitch(audio, 0.0), EPSILON);
        assertEquals(1.12f, AvatarFlightLaunchAudioMath.pulsePitch(audio, 1.0), EPSILON);
    }

    @Test
    void invalidOrderingIsNormalizedBySettingsGetters() throws Exception {
        AvatarFlightAudioSettings audio = TwAvatarFlightConfig.defaultConfig().getAudio();
        setField(audio, "launchChargeEarlyIntervalMs", 100.0);
        setField(audio, "launchChargeFullIntervalMs", 900.0);
        setField(audio, "launchChargeMinVolume", 1.5);
        setField(audio, "launchChargeMaxVolume", 0.5);
        setField(audio, "launchChargeMinPitch", 1.4);
        setField(audio, "launchChargeMaxPitch", 0.7);

        assertEquals(100L, AvatarFlightLaunchAudioMath.pulseIntervalMs(audio, 1.0));
        assertEquals(1.5f, AvatarFlightLaunchAudioMath.pulseVolume(audio, 1.0), EPSILON);
        assertEquals(1.4f, AvatarFlightLaunchAudioMath.pulsePitch(audio, 1.0), EPSILON);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
