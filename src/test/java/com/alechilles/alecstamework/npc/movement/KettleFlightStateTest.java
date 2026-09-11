package com.alechilles.alecstamework.npc.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KettleFlightStateTest {
    @Test
    void thermalClimbStaysBoundedAndResetsBetweenEpisodes() {
        KettleFlightState state = new KettleFlightState();
        double initial = state.altitude(0, 12, 28);
        state.advance(20);
        assertTrue(state.altitude(0, 12, 28) > initial);
        state.advance(300);
        assertEquals(28, state.altitude(0, 12, 28));
        state.reset();
        assertEquals(initial, state.altitude(0, 12, 28));
    }

    @Test
    void flockMembersUseDifferentOrbitLanesWithoutExceedingAltitudeCeiling() {
        KettleFlightState state = new KettleFlightState();
        assertNotEquals(KettleFlightState.radius(0, 18), KettleFlightState.radius(1, 18));
        assertNotEquals(state.altitude(0, 12, 28), state.altitude(1, 12, 28));
        assertEquals(13, state.altitude(3, 12, 13));
    }
}
