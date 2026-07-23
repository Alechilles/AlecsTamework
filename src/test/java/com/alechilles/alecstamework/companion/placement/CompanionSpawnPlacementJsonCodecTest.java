package com.alechilles.alecstamework.companion.placement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact primitive and validation contracts for persisted spawn placements. */
class CompanionSpawnPlacementJsonCodecTest {
    @Test
    void negativeCoordinatesAndAnglesRoundTripExactly() {
        CompanionSpawnPlacement placement = new CompanionSpawnPlacement(
                "  world-negative  ",
                -12.5,
                -63.05,
                -4.5,
                -0.25f,
                -1.5707964f,
                -0.5f
        );

        assertEquals(
                new CompanionSpawnPlacement(
                        "world-negative",
                        -12.5,
                        -63.05,
                        -4.5,
                        -0.25f,
                        -1.5707964f,
                        -0.5f
                ),
                CompanionSpawnPlacementJsonCodec.decode(
                        CompanionSpawnPlacementJsonCodec.encode(placement)
                )
        );
    }

    @Test
    void rejectsBlankWorldAndEveryNonFiniteCoordinateFamily() {
        assertThrows(
                IllegalArgumentException.class,
                () -> placement(" ", 0, 0, 0, 0, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> placement("world", Double.NaN, 0, 0, 0, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> placement(
                        "world", 0, Double.POSITIVE_INFINITY, 0, 0, 0, 0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> placement(
                        "world", 0, 0, Double.NEGATIVE_INFINITY, 0, 0, 0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> placement("world", 0, 0, 0, Float.NaN, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> placement(
                        "world", 0, 0, 0, 0, Float.POSITIVE_INFINITY, 0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> placement(
                        "world", 0, 0, 0, 0, 0, Float.NEGATIVE_INFINITY
                )
        );
    }

    private CompanionSpawnPlacement placement(
            String world,
            double x,
            double y,
            double z,
            float pitch,
            float yaw,
            float roll
    ) {
        return new CompanionSpawnPlacement(
                world, x, y, z, pitch, yaw, roll
        );
    }
}
