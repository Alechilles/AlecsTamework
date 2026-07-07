package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
