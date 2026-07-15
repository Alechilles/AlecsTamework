package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightComponentTest {
    private static final double EPSILON = 0.00001;

    @Test
    void clonePreservesVigourResourceState() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setVigourCharges(3.25);
        component.setLastVigourUpdateAtMs(-2500L);
        component.setVigourRechargeBlockedUntilMs(-1750L);
        component.setVigourRechargeMode("fast_flight");

        AvatarFlightComponent clone = component.clone();

        assertEquals(3.25, clone.getVigourCharges(), EPSILON);
        assertEquals(-2500L, clone.getLastVigourUpdateAtMs());
        assertEquals(-1750L, clone.getVigourRechargeBlockedUntilMs());
        assertEquals("FAST_FLIGHT", clone.getVigourRechargeMode());
    }

    @Test
    void maneuverLoadsAreFiniteAndClone() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setDiveLoad(0.75);
        component.setClimbLoad(Double.NaN);
        component.setNextLaunchAtMs(1500L);

        AvatarFlightComponent clone = component.clone();

        assertEquals(0.75, clone.getDiveLoad(), EPSILON);
        assertEquals(0.0, clone.getClimbLoad(), EPSILON);
        assertEquals(1500L, clone.getNextLaunchAtMs());
    }

    @Test
    void hudTelemetryIsFiniteClampedAndCloned() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setHudPitchRadians(Math.toRadians(30.0));
        component.setHudTargetSpeedRatio(1.5);

        AvatarFlightComponent clone = component.clone();

        assertEquals(Math.toRadians(30.0), clone.getHudPitchRadians(), EPSILON);
        assertEquals(1.0, clone.getHudTargetSpeedRatio(), EPSILON);

        component.setHudPitchRadians(Double.NaN);
        component.setHudTargetSpeedRatio(Double.NEGATIVE_INFINITY);

        assertEquals(0.0, component.getHudPitchRadians(), EPSILON);
        assertEquals(0.0, component.getHudTargetSpeedRatio(), EPSILON);
    }

    @Test
    void cloneAndClearPreserveLaunchAudioStateContract() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setNextLaunchChargeAudioAtMs(-250L);
        component.setLaunchFullChargeAudioPlayed(true);

        AvatarFlightComponent clone = component.clone();

        assertEquals(-250L, clone.getNextLaunchChargeAudioAtMs());
        assertTrue(clone.isLaunchFullChargeAudioPlayed());

        clone.clearLaunchAudioState();
        assertEquals(0L, clone.getNextLaunchChargeAudioAtMs());
        assertFalse(clone.isLaunchFullChargeAudioPlayed());
    }

    @Test
    void cloneAndClearPreserveAbilityAnimationStateContract() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setAbilityAnimationId("Dragon_Flap");
        component.setAbilityAnimationSlot("Movement");
        component.setAbilityAnimationKind("UPWARD_BOOST");
        component.setAbilityAnimationUntilMs(-250L);

        AvatarFlightComponent clone = component.clone();

        assertEquals("Dragon_Flap", clone.getAbilityAnimationId());
        assertEquals("Movement", clone.getAbilityAnimationSlot());
        assertEquals("UPWARD_BOOST", clone.getAbilityAnimationKind());
        assertEquals(-250L, clone.getAbilityAnimationUntilMs());

        clone.clearAbilityAnimationState();
        assertEquals("", clone.getAbilityAnimationId());
        assertEquals("Action", clone.getAbilityAnimationSlot());
        assertEquals("", clone.getAbilityAnimationKind());
        assertEquals(0L, clone.getAbilityAnimationUntilMs());
    }
}
