package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Resolves safe spawn positions/rotation for spawner item usage.
 */
final class SpawnerSpawnPositionService {
    private static final double SPAWN_OFFSET_Y = 0.5;
    private static final double SPAWN_SURFACE_OFFSET_Y = 0.01;
    private static final double SPAWN_FORWARD_DISTANCE = 1.5;
    private static final double RAYCAST_DISTANCE_EPSILON = 0.1;

    private final HytaleLogger logger;

    SpawnerSpawnPositionService(HytaleLogger logger) {
        this.logger = logger;
    }

    boolean isWithinSpawnDistance(@Nullable Player player, @Nullable Vector3d spawnPosition, @Nullable ItemFeatureConfig config) {
        if (player == null || spawnPosition == null || config == null) {
            return false;
        }
        double maxDistance = config.getSpawnMaxDistance();
        if (maxDistance <= 0) {
            return true;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return false;
        }
        Vector3d p = new Vector3d(playerTransform.getPosition());
        double dx = p.x - spawnPosition.x;
        double dy = p.y - spawnPosition.y;
        double dz = p.z - spawnPosition.z;
        double maxDistSq = maxDistance * maxDistance;
        return (dx * dx + dy * dy + dz * dz) <= maxDistSq;
    }

    @Nullable
    Vector3d resolveSpawnPosition(@Nullable Player player, @Nullable ItemFeatureConfig config) {
        if (player == null) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        double maxDistance = config != null ? config.getSpawnMaxDistance() : 0;
        double spawnDistance = maxDistance > 0 ? maxDistance : SPAWN_FORWARD_DISTANCE;
        double rayDistance = spawnDistance + RAYCAST_DISTANCE_EPSILON;

        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3d playerPosition = new Vector3d(transform.getPosition());
        Vector3f rotation = new Vector3f(transform.getRotation());
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = new Vector3f(headRotation.getRotation());
        }

        Vector3f forward = new Vector3f(Vector3f.FORWARD);
        forward.rotateY(rotation.getYaw());
        forward.rotateX(rotation.getPitch());
        forward.normalize();

        Vector3d targetLocation = TargetUtil.getTargetLocation(
                playerRef,
                this::isBlockingSpawnBlock,
                rayDistance,
                store
        );
        if (targetLocation != null) {
            double nudge = 0.01;
            Vector3d adjusted = new Vector3d(
                    targetLocation.x - forward.x * nudge,
                    targetLocation.y - forward.y * nudge,
                    targetLocation.z - forward.z * nudge
            );
            int blockX = (int) Math.floor(adjusted.x);
            int blockY = (int) Math.floor(adjusted.y);
            int blockZ = (int) Math.floor(adjusted.z);
            double clampedX = Math.min(Math.max(targetLocation.x, blockX + 0.001), blockX + 0.999);
            double clampedZ = Math.min(Math.max(targetLocation.z, blockZ + 0.001), blockZ + 0.999);
            double targetY = targetLocation.y;
            double snapThreshold = 0.02;
            double spawnY;
            if (Math.abs(targetY - blockY) <= snapThreshold || Math.abs(targetY - (blockY + 1.0)) <= snapThreshold) {
                spawnY = targetY + SPAWN_SURFACE_OFFSET_Y;
            } else {
                spawnY = blockY + 1.0 + SPAWN_SURFACE_OFFSET_Y;
            }
            Vector3d spawnPos = new Vector3d(clampedX, spawnY, clampedZ);
            Vector3d clampedSpawnPos = clampToMaxDistance(playerPosition, spawnPos, maxDistance);
            if (clampedSpawnPos != spawnPos) {
                logSpawnDebug(
                        "hit-distance-clamped",
                        targetLocation,
                        adjusted,
                        clampedSpawnPos,
                        forward,
                        rayDistance,
                        maxDistance,
                        blockX,
                        blockY,
                        blockZ
                );
                return clampedSpawnPos;
            }
            logSpawnDebug("hit", targetLocation, adjusted, spawnPos, forward, rayDistance, maxDistance, blockX, blockY, blockZ);
            return spawnPos;
        }

        Vector3d spawnPos = new Vector3d(playerPosition);
        spawnPos.x += forward.x * spawnDistance;
        spawnPos.y += forward.y * spawnDistance + SPAWN_OFFSET_Y;
        spawnPos.z += forward.z * spawnDistance;
        double minY = playerPosition.y + SPAWN_SURFACE_OFFSET_Y;
        if (spawnPos.y < minY) {
            spawnPos.y = minY;
            Vector3d clampedSpawnPos = clampToMaxDistance(playerPosition, spawnPos, maxDistance);
            logSpawnDebug("fallback-clamped", null, null, clampedSpawnPos, forward, spawnDistance, maxDistance, -1, -1, -1);
            return clampedSpawnPos;
        }
        Vector3d clampedSpawnPos = clampToMaxDistance(playerPosition, spawnPos, maxDistance);
        logSpawnDebug("fallback", null, null, clampedSpawnPos, forward, spawnDistance, maxDistance, -1, -1, -1);
        return clampedSpawnPos;
    }

    Vector3f resolveSpawnRotation(@Nullable Store<EntityStore> store,
                                  @Nullable Ref<EntityStore> playerRef,
                                  @Nullable Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Vector3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Vector3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                    playerPos.x - spawnPosition.x,
                    0.0,
                    playerPos.z - spawnPosition.z
            );
            if (relative.squaredLength() > 0.0001) {
                return Vector3f.lookAt(relative);
            }
        }
        Vector3f rotation = new Vector3f(transform.getRotation());
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = new Vector3f(headRotation.getRotation());
        }
        return rotation;
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

    private void logSpawnDebug(String stage,
                               @Nullable Vector3d targetLocation,
                               @Nullable Vector3d adjusted,
                               @Nullable Vector3d spawnPos,
                               @Nullable Vector3f forward,
                               double rayDistance,
                               double maxDistance,
                               int blockX,
                               int blockY,
                               int blockZ) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugSpawnerLocationEnabled()) {
            return;
        }
        StringBuilder message = new StringBuilder(200);
        message.append("Spawner spawn debug [").append(stage).append("] ");
        message.append("ray=").append(rayDistance).append(" max=").append(maxDistance).append(" ");
        if (forward != null) {
            message.append("forward=").append(formatVector(forward)).append(" ");
        }
        if (targetLocation != null) {
            message.append("target=").append(formatVector(targetLocation)).append(" ");
        }
        if (adjusted != null) {
            message.append("adjusted=").append(formatVector(adjusted)).append(" ");
        }
        if (blockX != -1 || blockY != -1 || blockZ != -1) {
            message.append("block=(").append(blockX).append(",").append(blockY).append(",").append(blockZ).append(") ");
        }
        if (spawnPos != null) {
            message.append("spawn=").append(formatVector(spawnPos));
        }
        logger.at(Level.INFO).log(message.toString());
    }

    private static String formatVector(Vector3d vector) {
        if (vector == null) {
            return "(null)";
        }
        return String.format(Locale.US, "(%.3f, %.3f, %.3f)", vector.x, vector.y, vector.z);
    }

    private static String formatVector(Vector3f vector) {
        if (vector == null) {
            return "(null)";
        }
        return String.format(Locale.US, "(%.3f, %.3f, %.3f)", vector.x, vector.y, vector.z);
    }

    @Nullable
    static Vector3d clampToMaxDistance(@Nullable Vector3d origin, @Nullable Vector3d target, double maxDistance) {
        if (origin == null || target == null || maxDistance <= 0.0) {
            return target;
        }
        double dx = target.x - origin.x;
        double dy = target.y - origin.y;
        double dz = target.z - origin.z;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        double maxDistanceSquared = maxDistance * maxDistance;
        if (distanceSquared <= maxDistanceSquared) {
            return target;
        }
        double distance = Math.sqrt(distanceSquared);
        if (distance <= 0.000001) {
            return target;
        }
        double scale = maxDistance / distance;
        return new Vector3d(
                origin.x + (dx * scale),
                origin.y + (dy * scale),
                origin.z + (dz * scale)
        );
    }
}
