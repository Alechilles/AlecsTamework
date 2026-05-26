package com.alechilles.alecstamework.items;

import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.spawning.ISpawnableWithModel;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import com.hypixel.hytale.server.spawning.SpawningContext;

import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves managed-coop resident release positions while keeping releases near the coop floor.
 */
public final class CoopResidentReleasePositionService {
    private static final double RELEASE_CONE_DEGREES = 100.0;
    private static final double RELEASE_MIN_DISTANCE = 1.0;
    private static final double RELEASE_MAX_DISTANCE = 3.0;
    private static final int RELEASE_SPAWN_ATTEMPTS = 16;
    private static final double MAX_VERTICAL_DROP_BELOW_COOP = 1.0;

    @Nonnull
    public Vector3d resolveSpawnPosition(@Nonnull World world,
                                         @Nonnull Builder<Role> roleBuilder,
                                         @Nonnull Vector3i coopBlock,
                                         int coopRotationIndex,
                                         double offsetX,
                                         double offsetY,
                                         double offsetZ) {
        Vector3d rotatedOffset = rotateHorizontalOffset(coopRotationIndex, offsetX, offsetY, offsetZ);
        Vector3d fallback = clampToVerticalLimit(
                coopBlock.y,
                new Vector3d(
                        coopBlock.x + 0.5 + rotatedOffset.x,
                        coopBlock.y + rotatedOffset.y,
                        coopBlock.z + 0.5 + rotatedOffset.z
                )
        );
        Vector3d validatedFallback = validateSpawnPosition(world, roleBuilder, fallback);
        if (isWithinVerticalLimit(coopBlock.y, validatedFallback)) {
            fallback = validatedFallback;
        }

        Vector3d forwardDirection = resolveForwardDirection(coopRotationIndex, offsetX, offsetZ);
        double forwardX = forwardDirection.x;
        double forwardZ = forwardDirection.z;
        double forwardLength = Math.sqrt((forwardX * forwardX) + (forwardZ * forwardZ));
        if (!Double.isFinite(forwardLength) || forwardLength < 0.001) {
            Vector3d defaultForward = rotateHorizontalOffset(coopRotationIndex, 0.0, 0.0, 1.0);
            forwardX = defaultForward.x;
            forwardZ = defaultForward.z;
            forwardLength = 1.0;
        }
        forwardX /= forwardLength;
        forwardZ /= forwardLength;

        double centerX = coopBlock.x + 0.5;
        double centerY = coopBlock.y + offsetY;
        double centerZ = coopBlock.z + 0.5;
        double halfConeRadians = Math.toRadians(RELEASE_CONE_DEGREES * 0.5);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < RELEASE_SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble(-halfConeRadians, halfConeRadians);
            double distance = random.nextDouble(RELEASE_MIN_DISTANCE, RELEASE_MAX_DISTANCE + 0.0001);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double rotatedX = (forwardX * cos) - (forwardZ * sin);
            double rotatedZ = (forwardX * sin) + (forwardZ * cos);
            Vector3d candidate = new Vector3d(
                    centerX + (rotatedX * distance),
                    centerY,
                    centerZ + (rotatedZ * distance)
            );
            Vector3d validated = validateSpawnPosition(world, roleBuilder, candidate);
            if (isWithinVerticalLimit(coopBlock.y, validated)) {
                return validated;
            }
        }
        return fallback;
    }

    boolean isWithinVerticalLimit(int coopBlockY, @Nullable Vector3d position) {
        return position != null && position.y >= minimumAllowedSpawnY(coopBlockY);
    }

    @Nonnull
    Vector3d clampToVerticalLimit(int coopBlockY, @Nonnull Vector3d position) {
        if (position.y >= minimumAllowedSpawnY(coopBlockY)) {
            return position;
        }
        return new Vector3d(position.x, minimumAllowedSpawnY(coopBlockY), position.z);
    }

    double minimumAllowedSpawnY(int coopBlockY) {
        return coopBlockY - MAX_VERTICAL_DROP_BELOW_COOP;
    }

    @Nonnull
    Vector3d rotateHorizontalOffset(int coopRotationIndex, double x, double y, double z) {
        Rotation yawRotation = resolveYawRotation(coopRotationIndex);
        return yawRotation.rotateY(new Vector3d(x, y, z), new Vector3d());
    }

    @Nonnull
    Vector3d resolveForwardDirection(int coopRotationIndex, double offsetX, double offsetZ) {
        if (Math.abs(offsetX) < 0.001 && Math.abs(offsetZ) < 0.001) {
            return rotateHorizontalOffset(coopRotationIndex, 0.0, 0.0, 1.0);
        }
        return rotateHorizontalOffset(coopRotationIndex, offsetX, 0.0, offsetZ);
    }

    @Nonnull
    private Rotation resolveYawRotation(int coopRotationIndex) {
        if (coopRotationIndex < 0 || coopRotationIndex >= RotationTuple.VALUES.length) {
            return Rotation.None;
        }
        return RotationTuple.get(coopRotationIndex).yaw();
    }

    @Nullable
    private Vector3d validateSpawnPosition(@Nonnull World world,
                                           @Nonnull Builder<Role> roleBuilder,
                                           @Nonnull Vector3d position) {
        if (!(roleBuilder instanceof ISpawnableWithModel spawnable)) {
            return position;
        }
        SpawningContext spawningContext = new SpawningContext();
        spawningContext.setSpawnable(spawnable);
        if (!spawningContext.set(world, position.x, position.y, position.z)) {
            return null;
        }
        if (spawningContext.canSpawn() != SpawnTestResult.TEST_OK) {
            return null;
        }
        Vector3d adjusted = spawningContext.newPosition();
        return adjusted != null ? new Vector3d(adjusted) : position;
    }
}
