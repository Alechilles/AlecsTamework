package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.vector.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests max-distance clamping behavior for spawner spawn positions. */
class SpawnerSpawnPositionServiceTest {

    @Test
    void clampToMaxDistanceKeepsTargetWhenAlreadyWithinRange() {
        Vector3d origin = new Vector3d(0.0, 0.0, 0.0);
        Vector3d target = new Vector3d(3.0, 4.0, 0.0); // distance = 5

        Vector3d result = SpawnerSpawnPositionService.clampToMaxDistance(origin, target, 5.0);

        assertSame(target, result);
    }

    @Test
    void clampToMaxDistanceClampsTargetWhenOutOfRange() {
        Vector3d origin = new Vector3d(0.0, 0.0, 0.0);
        Vector3d target = new Vector3d(3.0, 4.0, 12.0); // distance = 13

        Vector3d result = SpawnerSpawnPositionService.clampToMaxDistance(origin, target, 5.0);

        assertNotNull(result);
        assertEquals(15.0 / 13.0, result.x, 0.000001);
        assertEquals(20.0 / 13.0, result.y, 0.000001);
        assertEquals(60.0 / 13.0, result.z, 0.000001);
    }

    @Test
    void clampToMaxDistanceSkipsClampingWhenDistanceLimitDisabled() {
        Vector3d origin = new Vector3d(0.0, 0.0, 0.0);
        Vector3d target = new Vector3d(100.0, 0.0, 0.0);

        Vector3d result = SpawnerSpawnPositionService.clampToMaxDistance(origin, target, 0.0);

        assertSame(target, result);
    }
}
