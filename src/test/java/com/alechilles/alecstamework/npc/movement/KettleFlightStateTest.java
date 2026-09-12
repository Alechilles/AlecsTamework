package com.alechilles.alecstamework.npc.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KettleFlightStateTest {
    @Test
    void longThermalsKeepGlidingWithBriefStaggeredFlapsAfterClimbCaps() {
        KettleFlightState state = new KettleFlightState();
        state.advance(300);
        int glideFrames = 0;
        int staggeredFrames = 0;
        for (int frame = 0; frame < 480; frame++) {
            state.advance(0.25);
            if (state.shouldGlide(0)) glideFrames++;
            if (state.shouldGlide(0) != state.shouldGlide(1)) staggeredFrames++;
        }
        assertTrue(glideFrames > 480 * 0.8, "Most of a long thermal should glide");
        assertTrue(glideFrames < 480 * 0.95, "Flaps must keep recurring after the climb caps");
        assertTrue(staggeredFrames > 0, "Flock members should not all flap together");
        state.reset();
        assertTrue(state.shouldGlide(0));
    }

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
