package com.alechilles.alecstamework.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TameworkShowSpawnMarkersCommandSupportTest {

    @Test
    void parseModeUsesDefaultRadiusWhenNoArgumentProvided() {
        TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                TameworkShowSpawnMarkersCommandSupport.parse("tw showspawnmarkers");

        assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.SHOW, result.mode());
        assertEquals(64.0, result.radius());
    }

    @Test
    void parseModeCanDisableTracking() {
        TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                TameworkShowSpawnMarkersCommandSupport.parse("tw showspawnmarkers off");

        assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.OFF, result.mode());
    }

    @Test
    void parseModeClampsRadiusToSupportedRange() {
        TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                TameworkShowSpawnMarkersCommandSupport.parse("tw showspawnmarkers 999");

        assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.SHOW, result.mode());
        assertEquals(256.0, result.radius());
    }

    @Test
    void parseModeReportsInvalidRadius() {
        TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                TameworkShowSpawnMarkersCommandSupport.parse("tw showspawnmarkers nearby");

        assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.INVALID, result.mode());
    }

    @Test
    void debugStyleUsesBrightPinkAndChunkierMarkerGeometry() {
        TameworkShowSpawnMarkersCommandSupport.DebugStyle style =
                TameworkShowSpawnMarkersCommandSupport.debugStyle();

        assertEquals(1.0F, style.red());
        assertEquals(0.05F, style.green());
        assertEquals(0.85F, style.blue());
        assertEquals(0.75, style.markerWidth());
        assertEquals(1.2, style.capWidth());
        assertEquals(0.95F, style.markerAlpha());
    }

    @Test
    void formatNpcSummaryShowsSeveralWeightedNpcOptions() {
        assertEquals(
                "cow, chicken, fox",
                TameworkShowSpawnMarkersCommandSupport.formatNpcSummary(
                        java.util.List.of("cow", "chicken", "fox"),
                        4
                )
        );
    }

    @Test
    void formatNpcSummaryCompactsLongWeightedNpcOptions() {
        assertEquals(
                "cow, chicken, fox, wolf, +1 more",
                TameworkShowSpawnMarkersCommandSupport.formatNpcSummary(
                        java.util.List.of("cow", "chicken", "fox", "wolf", "bear"),
                        4
                )
        );
    }
}
