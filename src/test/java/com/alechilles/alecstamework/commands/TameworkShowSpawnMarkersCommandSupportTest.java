package com.alechilles.alecstamework.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TameworkShowSpawnMarkersCommandSupportTest {

    @Test
    void parseModeUsesDefaultRadiusWhenNoArgumentProvided() {
        for (String commandToken : new String[]{"spawn-markers", "spawn-beacons"}) {
            TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                    TameworkShowSpawnMarkersCommandSupport.parse("/tw debug view " + commandToken, commandToken);

            assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.SHOW, result.mode());
            assertEquals(64.0, result.radius());
        }
    }

    @Test
    void parseModeCanDisableTracking() {
        for (String commandToken : new String[]{"spawn-markers", "spawn-beacons"}) {
            TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                    TameworkShowSpawnMarkersCommandSupport.parse("/tw debug view " + commandToken + " off", commandToken);

            assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.OFF, result.mode());
        }
    }

    @Test
    void parseModeClampsRadiusToSupportedRange() {
        for (String commandToken : new String[]{"spawn-markers", "spawn-beacons"}) {
            TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                    TameworkShowSpawnMarkersCommandSupport.parse("/tw debug view " + commandToken + " 999", commandToken);

            assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.SHOW, result.mode());
            assertEquals(256.0, result.radius());
        }
    }

    @Test
    void parseModeReportsInvalidRadius() {
        for (String commandToken : new String[]{"spawn-markers", "spawn-beacons"}) {
            TameworkShowSpawnMarkersCommandSupport.ParseResult result =
                    TameworkShowSpawnMarkersCommandSupport.parse("/tw debug view " + commandToken + " nearby", commandToken);

            assertEquals(TameworkShowSpawnMarkersCommandSupport.Mode.INVALID, result.mode());
        }
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
