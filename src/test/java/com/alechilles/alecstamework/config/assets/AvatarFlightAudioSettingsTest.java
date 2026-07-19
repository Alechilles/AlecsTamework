package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightAudioSettingsTest {
    @Test
    void flapCadencesAreDisabledByDefault() {
        AvatarFlightAudioSettings settings = new AvatarFlightAudioSettings();

        assertEquals("", settings.getIdleFlightFlapSoundEvent());
        assertEquals(0L, settings.getIdleFlightFlapIntervalMs());
        assertEquals("", settings.getFlightFlapSoundEvent());
        assertEquals(0L, settings.getFlightFlapIntervalMs());
    }

    @Test
    void explicitNestedValuesOverrideWhileMissingValuesInherit() throws Exception {
        AvatarFlightAudioSettings parent = new AvatarFlightAudioSettings();
        setField(parent, "idleFlightFlapSoundEvent", "SFX_Parent_Idle");
        setField(parent, "idleFlightFlapIntervalMs", 900.0);
        setField(parent, "flightFlapSoundEvent", "SFX_Parent_Flight");
        setField(parent, "flightFlapIntervalMs", 1_300.0);

        AvatarFlightAudioSettings child = new AvatarFlightAudioSettings();
        setField(child, "idleFlightFlapIntervalMs", 750.0);
        child.inheritMissingFrom(parent, Set.of("IdleFlightFlapIntervalMs"));

        assertEquals("SFX_Parent_Idle", child.getIdleFlightFlapSoundEvent());
        assertEquals(750L, child.getIdleFlightFlapIntervalMs());
        assertEquals("SFX_Parent_Flight", child.getFlightFlapSoundEvent());
        assertEquals(1_300L, child.getFlightFlapIntervalMs());
    }

    @Test
    void explicitZeroIntervalRemainsDisabled() throws Exception {
        AvatarFlightAudioSettings parent = new AvatarFlightAudioSettings();
        setField(parent, "flightFlapIntervalMs", 1_300.0);
        AvatarFlightAudioSettings child = new AvatarFlightAudioSettings();
        setField(child, "flightFlapIntervalMs", 0.0);

        child.inheritMissingFrom(parent, Set.of("FlightFlapIntervalMs"));

        assertEquals(0L, child.getFlightFlapIntervalMs());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
