package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAbilityAnimationSettings;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AvatarFlightAbilityAnimationSelectorTest {
    @Test
    void mapsAcceptedControllerOutputsToConfiguredCues() throws Exception {
        AvatarFlightAbilityAnimationSettings settings = settings();

        AvatarFlightAbilityAnimationSelector.Cue flap =
                AvatarFlightAbilityAnimationSelector.select(output(true, false, false), settings);
        AvatarFlightAbilityAnimationSelector.Cue boost =
                AvatarFlightAbilityAnimationSelector.select(output(false, true, false), settings);
        AvatarFlightAbilityAnimationSelector.Cue airbrake =
                AvatarFlightAbilityAnimationSelector.select(output(false, false, true), settings);

        assertEquals(AvatarFlightAbilityAnimationSelector.Ability.UPWARD_BOOST, flap.ability());
        assertEquals("Dragon_Flap", flap.animationId());
        assertEquals(650L, flap.durationMs());
        assertEquals(AvatarFlightAbilityAnimationSelector.Ability.FORWARD_BOOST, boost.ability());
        assertEquals("Dragon_Surge", boost.animationId());
        assertEquals(700L, boost.durationMs());
        assertEquals(AvatarFlightAbilityAnimationSelector.Ability.AIRBRAKE, airbrake.ability());
        assertEquals("Dragon_Brake", airbrake.animationId());
        assertEquals(500L, airbrake.durationMs());
    }

    @Test
    void airbrakeWinsWhenMoreThanOneAbilityIsAccepted() throws Exception {
        AvatarFlightAbilityAnimationSelector.Cue cue =
                AvatarFlightAbilityAnimationSelector.select(output(true, true, true), settings());

        assertEquals(AvatarFlightAbilityAnimationSelector.Ability.AIRBRAKE, cue.ability());
    }

    @Test
    void returnsNoCueWhenControllerAcceptedNoAbility() throws Exception {
        assertNull(AvatarFlightAbilityAnimationSelector.select(output(false, false, false), settings()));
    }

    private static AvatarFlightAbilityAnimationSettings settings() throws Exception {
        AvatarFlightAbilityAnimationSettings settings = new AvatarFlightAbilityAnimationSettings();
        setField(settings, "upwardBoostAnimation", "Dragon_Flap");
        setField(settings, "forwardBoostAnimation", "Dragon_Surge");
        setField(settings, "airbrakeAnimation", "Dragon_Brake");
        return settings;
    }

    private static AvatarFlightController.Output output(boolean jump, boolean boost, boolean airbrake) {
        return new AvatarFlightController.Output(
                AvatarFlightMode.FORWARD_FLIGHT,
                0.0, 0.0, 0.0,
                0L, 0L, 0L,
                0.0, 0.0,
                true, jump, boost, false, 0.0,
                false, false,
                0.0, 0.0, 0.0,
                airbrake
        );
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
