package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

/**
 * Computes companion placement positions for recall and respawn flows.
 */
final class CommandCompanionPlacementService {
    private static final double RESPAWN_DISTANCE_CLOSE = 5.0;
    private static final double RESPAWN_DISTANCE_NEAR = 8.0;
    private static final double RESPAWN_DISTANCE_MID = 12.0;
    private static final double RESPAWN_DISTANCE_FAR = 16.0;
    private static final double OUT_OF_VIEW_MIN_ANGLE_DEGREES = 70.0;
    private static final double[] PLACEMENT_ANGLE_OFFSETS = {
            180.0, -180.0, 150.0, -150.0, 120.0, -120.0, 90.0, -90.0, 60.0, -60.0, 45.0, -45.0, 30.0, -30.0, 0.0
    };
    private static final double COMMAND_PLACEMENT_MIN_RELATIVE_Y = -2.0;
    private static final double COMMAND_PLACEMENT_MAX_RELATIVE_Y = 4.0;

    Vector3d computeSafeRecallPosition(Ref<EntityStore> playerRef,
                                       Store<EntityStore> store,
                                       double safeSpawnDistance,
                                       @Nullable String roleId,
                                       Vector3d sourcePosition) {
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
        return computeSafeCompanionPlacementPosition(
                playerRef,
                store,
                safeSpawnDistance,
                sourcePosition,
                settings
        );
    }

    Vector3d computeSafeRespawnPosition(Ref<EntityStore> playerRef,
                                        Store<EntityStore> store,
                                        double safeSpawnDistance,
                                        @Nullable String roleId,
                                        Vector3d sourcePosition) {
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
        return computeSafeCompanionPlacementPosition(
                playerRef,
                store,
                safeSpawnDistance,
                sourcePosition,
                settings
        );
    }

    private Vector3d computeSafeCompanionPlacementPosition(Ref<EntityStore> playerRef,
                                                           Store<EntityStore> store,
                                                           double safeSpawnDistance,
                                                           Vector3d sourcePosition,
                                                           @Nullable TwCompanionConfig.EffectiveSettings companionSettings) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return null;
        }
        Vector3d playerPos = new Vector3d(playerTransform.getPosition());
        Vector3d desired = computeDesiredPlacementPosition(playerPos, safeSpawnDistance, sourcePosition);
        double minRelativeY = resolvePlacementMinRelativeY(companionSettings);
        double maxRelativeY = resolvePlacementMaxRelativeY(companionSettings, minRelativeY);
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            if (desired != null) {
                return new Vector3d(desired.x, playerPos.y + 1.0, desired.z);
            }
            return new Vector3d(playerPos.x, playerPos.y + 1.0, playerPos.z);
        }

        Vector3d lookDirection = resolvePlayerLookDirection(playerRef, store);
        double dirX = lookDirection != null ? lookDirection.x : 1.0;
        double dirZ = lookDirection != null ? lookDirection.z : 0.0;
        if (lookDirection == null && desired != null) {
            double dx = desired.x - playerPos.x;
            double dz = desired.z - playerPos.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                dirX = dx / len;
                dirZ = dz / len;
            }
        }
        double baseAngle = Math.atan2(dirZ, dirX);
        double targetDistance = Math.max(2.0, safeSpawnDistance);
        double[] distanceCandidates = resolvePlacementDistanceCandidates(companionSettings, targetDistance);
        double[] angleOffsets = resolvePlacementAngleOffsets();
        for (double distance : distanceCandidates) {
            if (distance < 2.0) {
                continue;
            }
            for (double angleOffset : angleOffsets) {
                double radians = baseAngle + Math.toRadians(angleOffset);
                double x = playerPos.x + Math.cos(radians) * distance;
                double z = playerPos.z + Math.sin(radians) * distance;
                Vector3d surface = projectToSurface(world, x, playerPos.y + 2.0, z, 48.0);
                if (surface == null) {
                    surface = projectToSurface(world, x, playerPos.y + 24.0, z, 64.0);
                }
                if (surface != null && isWithinPlacementVerticalBand(surface.y, playerPos.y, minRelativeY, maxRelativeY)) {
                    return surface;
                }
            }
        }

        Vector3d nearPlayer = projectToSurface(world, playerPos.x, playerPos.y + 8.0, playerPos.z, 48.0);
        if (nearPlayer != null && isWithinPlacementVerticalBand(nearPlayer.y, playerPos.y, minRelativeY, maxRelativeY)) {
            return nearPlayer;
        }
        if (desired != null) {
            return new Vector3d(desired.x, playerPos.y + 1.0, desired.z);
        }
        return new Vector3d(playerPos.x, playerPos.y + 1.0, playerPos.z);
    }

    private Vector3d computeDesiredPlacementPosition(Vector3d playerPos,
                                                     double safeSpawnDistance,
                                                     Vector3d sourcePosition) {
        if (playerPos == null) {
            return null;
        }
        double dirX = 1.0;
        double dirZ = 0.0;
        if (sourcePosition != null) {
            double sx = sourcePosition.x - playerPos.x;
            double sz = sourcePosition.z - playerPos.z;
            double len = Math.sqrt(sx * sx + sz * sz);
            if (len > 0.001) {
                dirX = sx / len;
                dirZ = sz / len;
            }
        }
        double distance = Math.max(2.0, safeSpawnDistance);
        return new Vector3d(
                playerPos.x + dirX * distance,
                playerPos.y + 1.0,
                playerPos.z + dirZ * distance
        );
    }

    private double[] resolvePlacementDistanceCandidates(@Nullable TwCompanionConfig.EffectiveSettings companionSettings,
                                                        double fallbackDistance) {
        double close = resolvePositiveDouble(
                companionSettings != null ? companionSettings.getDeadRespawnDistanceClose() : 0.0,
                RESPAWN_DISTANCE_CLOSE
        );
        double near = resolvePositiveDouble(
                companionSettings != null ? companionSettings.getDeadRespawnDistanceNear() : 0.0,
                RESPAWN_DISTANCE_NEAR
        );
        double mid = resolvePositiveDouble(
                companionSettings != null ? companionSettings.getDeadRespawnDistanceMid() : 0.0,
                RESPAWN_DISTANCE_MID
        );
        double far = resolvePositiveDouble(
                companionSettings != null ? companionSettings.getDeadRespawnDistanceFar() : 0.0,
                RESPAWN_DISTANCE_FAR
        );
        return new double[] {
                Math.max(2.0, close),
                Math.max(2.0, near),
                Math.max(2.0, mid),
                Math.max(2.0, far),
                Math.max(2.0, fallbackDistance)
        };
    }

    private double[] resolvePlacementAngleOffsets() {
        List<Double> offCamera = new ArrayList<>();
        List<Double> fallback = new ArrayList<>();
        for (double angleOffset : PLACEMENT_ANGLE_OFFSETS) {
            if (Math.abs(angleOffset) >= OUT_OF_VIEW_MIN_ANGLE_DEGREES) {
                offCamera.add(angleOffset);
                continue;
            }
            fallback.add(angleOffset);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Collections.shuffle(offCamera, random);
        Collections.shuffle(fallback, random);
        double[] ordered = new double[offCamera.size() + fallback.size()];
        int index = 0;
        for (double value : offCamera) {
            ordered[index++] = value;
        }
        for (double value : fallback) {
            ordered[index++] = value;
        }
        return ordered;
    }

    private double resolvePlacementMinRelativeY(@Nullable TwCompanionConfig.EffectiveSettings companionSettings) {
        return resolveFiniteDouble(
                companionSettings != null ? companionSettings.getPlacementMinRelativeY() : Double.NaN,
                COMMAND_PLACEMENT_MIN_RELATIVE_Y
        );
    }

    private double resolvePlacementMaxRelativeY(@Nullable TwCompanionConfig.EffectiveSettings companionSettings,
                                                double minRelativeY) {
        double maxRelativeY = resolveFiniteDouble(
                companionSettings != null ? companionSettings.getPlacementMaxRelativeY() : Double.NaN,
                COMMAND_PLACEMENT_MAX_RELATIVE_Y
        );
        return maxRelativeY < minRelativeY ? minRelativeY : maxRelativeY;
    }

    private boolean isWithinPlacementVerticalBand(double surfaceY,
                                                  double playerY,
                                                  double minRelativeY,
                                                  double maxRelativeY) {
        double minY = playerY + minRelativeY;
        double maxY = playerY + maxRelativeY;
        return surfaceY >= minY && surfaceY <= maxY;
    }

    private Vector3d resolvePlayerLookDirection(Ref<EntityStore> playerRef, Store<EntityStore> store) {
        if (playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        Vector3f rotation = null;
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = new Vector3f(headRotation.getRotation());
        }
        if (rotation == null) {
            TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }
            rotation = new Vector3f(transform.getRotation());
        }
        Vector3f forward = new Vector3f(Vector3f.FORWARD);
        forward.rotateY(rotation.getYaw());
        forward.rotateX(rotation.getPitch());
        forward.normalize();
        Vector3d out = new Vector3d(forward.x, 0.0, forward.z);
        if (out.squaredLength() <= 0.0001) {
            return null;
        }
        out.normalize();
        return out;
    }

    private Vector3d projectToSurface(World world,
                                      double x,
                                      double y,
                                      double z,
                                      double maxDistance) {
        if (world == null || maxDistance <= 0.0) {
            return null;
        }
        Vector3d target = TargetUtil.getTargetLocation(
                world,
                this::isBlockingSpawnBlock,
                x,
                y,
                z,
                0.0,
                -1.0,
                0.0,
                maxDistance
        );
        if (target == null) {
            return null;
        }
        int blockY = (int) Math.floor(target.y);
        double surfaceY = blockY + 1.0 + 0.05;
        if (surfaceY < target.y + 0.02) {
            surfaceY = target.y + 0.02;
        }
        return new Vector3d(x, surfaceY, z);
    }

    private boolean isBlockingSpawnBlock(int blockId) {
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null || blockType == BlockType.UNKNOWN) {
            return false;
        }
        return WorldUtil.isSolidOnlyBlock(blockType, 0);
    }

    private double resolvePositiveDouble(double configured, double fallback) {
        if (!Double.isFinite(configured) || configured <= 0.0) {
            return fallback;
        }
        return configured;
    }

    private double resolveFiniteDouble(double configured, double fallback) {
        if (!Double.isFinite(configured)) {
            return fallback;
        }
        return configured;
    }
}
