package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.math.vector.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TameworkDeleteSpawnMarkerCommandSupportTest {

    @Test
    void parseUsesDefaultRangeWhenNoArgumentProvided() {
        TameworkDeleteSpawnMarkerCommandSupport.ParseResult result =
                TameworkDeleteSpawnMarkerCommandSupport.parse("tw deletespawnmarker");

        assertEquals(TameworkDeleteSpawnMarkerCommandSupport.Mode.DELETE, result.mode());
        assertEquals(10.0, result.range());
    }

    @Test
    void parseClampsRangeToSupportedLimit() {
        TameworkDeleteSpawnMarkerCommandSupport.ParseResult result =
                TameworkDeleteSpawnMarkerCommandSupport.parse("tw deletespawnmarker 999");

        assertEquals(TameworkDeleteSpawnMarkerCommandSupport.Mode.DELETE, result.mode());
        assertEquals(64.0, result.range());
    }

    @Test
    void parseRejectsInvalidRange() {
        TameworkDeleteSpawnMarkerCommandSupport.ParseResult result =
                TameworkDeleteSpawnMarkerCommandSupport.parse("tw deletespawnmarker near");

        assertEquals(TameworkDeleteSpawnMarkerCommandSupport.Mode.INVALID, result.mode());
    }

    @Test
    void scoringAcceptsMarkerCenteredInFrontOfPlayer() {
        TameworkDeleteSpawnMarkerCommandSupport.TargetScore score =
                TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                        new Vector3d(0.0, 0.0, 0.0),
                        new Vector3d(0.0, 0.0, 1.0),
                        new Vector3d(0.1, 0.0, 6.0),
                        10.0
                );

        assertTrue(score.matches());
        assertEquals(6.0, score.forwardDistance(), 0.001);
        assertEquals(0.1, score.perpendicularDistance(), 0.001);
    }

    @Test
    void scoringRejectsMarkerBehindPlayer() {
        TameworkDeleteSpawnMarkerCommandSupport.TargetScore score =
                TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                        new Vector3d(0.0, 0.0, 0.0),
                        new Vector3d(0.0, 0.0, 1.0),
                        new Vector3d(0.0, 0.0, -2.0),
                        10.0
                );

        assertFalse(score.matches());
    }

    @Test
    void scoringRejectsMarkerTooFarFromViewRay() {
        TameworkDeleteSpawnMarkerCommandSupport.TargetScore score =
                TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                        new Vector3d(0.0, 0.0, 0.0),
                        new Vector3d(0.0, 0.0, 1.0),
                        new Vector3d(4.0, 0.0, 6.0),
                        10.0
                );

        assertFalse(score.matches());
    }

    @Test
    void betterScorePrefersCenteredMarkerOverCloserOffAxisMarker() {
        TameworkDeleteSpawnMarkerCommandSupport.TargetScore centered =
                TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                        new Vector3d(0.0, 0.0, 0.0),
                        new Vector3d(0.0, 0.0, 1.0),
                        new Vector3d(0.05, 0.0, 7.0),
                        10.0
                );
        TameworkDeleteSpawnMarkerCommandSupport.TargetScore offAxis =
                TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                        new Vector3d(0.0, 0.0, 0.0),
                        new Vector3d(0.0, 0.0, 1.0),
                        new Vector3d(1.0, 0.0, 4.0),
                        10.0
                );

        assertTrue(centered.isBetterThan(offAxis));
    }

    @Test
    void scoringRejectsInvalidForwardVector() {
        assertNull(TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 4.0),
                10.0
        ));
    }
}
