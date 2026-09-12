package com.alechilles.alecstamework.npc.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KettleFlightStateTest {
    @Test
    void longThermalsKeepGlidingWithBriefStaggeredFlaps() {
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
        assertTrue(glideFrames < 480 * 0.95, "Flaps must keep recurring through a long thermal");
        assertTrue(staggeredFrames > 0, "Flock members should not all flap together");
        state.reset();
        assertTrue(state.shouldGlide(0));
    }

    @Test
    void thermalMembersKeepSeparatedAltitudeLanesThroughLongEpisodes() {
        KettleFlightState state = new KettleFlightState();
        double[] initialAltitudes = new double[8];
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (int memberIndex = 0; memberIndex < initialAltitudes.length; memberIndex++) {
            double altitude = state.altitude(memberIndex, 12, 28);
            assertTrue(altitude >= 12 && altitude <= 28);
            initialAltitudes[memberIndex] = altitude;
            lowest = Math.min(lowest, altitude);
            highest = Math.max(highest, altitude);
        }

        state.advance(300);
        for (int memberIndex = 0; memberIndex < initialAltitudes.length; memberIndex++) {
            assertEquals(initialAltitudes[memberIndex], state.altitude(memberIndex, 12, 28));
        }
        assertTrue(highest - lowest > 16 * 0.65,
                "Members should retain broad altitude separation instead of converging at the ceiling");
    }

    @Test
    void thermalMembersUseWideStableOrbitAndSpeedVariation() {
        double baseRadius = 18.0;
        double baseSpeed = 0.8;
        double narrowestRadius = Double.POSITIVE_INFINITY;
        double widestRadius = Double.NEGATIVE_INFINITY;
        double slowestSpeed = Double.POSITIVE_INFINITY;
        double fastestSpeed = Double.NEGATIVE_INFINITY;
        for (int memberIndex = 0; memberIndex < 8; memberIndex++) {
            double radius = KettleFlightState.radius(memberIndex, baseRadius);
            double speed = KettleFlightState.relativeSpeed(memberIndex, baseSpeed);
            assertTrue(radius >= baseRadius * 0.65 && radius <= baseRadius * 1.5);
            assertTrue(speed >= baseSpeed * 0.7 && speed <= baseSpeed * 1.15);
            narrowestRadius = Math.min(narrowestRadius, radius);
            widestRadius = Math.max(widestRadius, radius);
            slowestSpeed = Math.min(slowestSpeed, speed);
            fastestSpeed = Math.max(fastestSpeed, speed);
        }
        assertTrue(widestRadius - narrowestRadius > baseRadius * 0.6,
                "Kettle members should not share nearly identical circles");
        assertTrue(fastestSpeed - slowestSpeed > baseSpeed * 0.25,
                "Kettle members should not travel at nearly identical speeds");
        assertEquals(1.0, KettleFlightState.relativeSpeed(1, 2.0));
    }
}
