package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.math.TameworkRotationUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
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
    private static final int SAFE_VERTICAL_SCAN_STEPS = 4;
    private static final double SAFE_STAND_HEIGHT_OFFSET = 0.05;

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
            Vector3d safeDesired = projectToSurface(world, desired.x, desired.y + 8.0, desired.z, 48.0);
            if (safeDesired != null && isWithinPlacementVerticalBand(safeDesired.y, playerPos.y, minRelativeY, maxRelativeY)) {
                return safeDesired;
            }
        }
        return null;
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
        Rotation3f rotation = null;
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = TameworkRotationUtil.copyOrDefault(headRotation.getRotation());
        }
        if (rotation == null) {
            TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }
            rotation = TameworkRotationUtil.copyOrDefault(transform.getRotation());
        }
        Vector3d forward = TameworkRotationUtil.directionFrom(rotation).normalize();
        Vector3d out = new Vector3d(forward.x, 0.0, forward.z);
        if (out.lengthSquared() <= 0.0001) {
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
        return resolveSafeStandPosition(world, x, target.y, z);
    }

    @Nullable
    private Vector3d resolveSafeStandPosition(@Nullable World world,
                                              double x,
                                              double y,
                                              double z) {
        if (world == null || world.getChunkStore() == null) {
            return null;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore != null ? chunkStore.getStore() : null;
        if (chunkStoreStore == null) {
            return null;
        }
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int step = 0; step <= SAFE_VERTICAL_SCAN_STEPS; step++) {
            int upY = blockY + step;
            if (canStandAt(chunkStore, chunkStoreStore, blockX, upY, blockZ, chunkCache)) {
                return toStandPosition(blockX, upY, blockZ);
            }
            if (step == 0) {
                continue;
            }
            int downY = blockY - step;
            if (canStandAt(chunkStore, chunkStoreStore, blockX, downY, blockZ, chunkCache)) {
                return toStandPosition(blockX, downY, blockZ);
            }
        }
        return null;
    }

    private boolean canStandAt(@Nonnull ChunkStore chunkStore,
                               @Nonnull Store<ChunkStore> chunkStoreStore,
                               int blockX,
                               int blockY,
                               int blockZ,
                               @Nonnull Map<Long, WorldChunk> chunkCache) {
        WorldChunk feetChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        WorldChunk headChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        WorldChunk groundChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        if (feetChunk == null || headChunk == null || groundChunk == null) {
            return false;
        }
        int feetFluid = feetChunk.getFluidId(blockX, blockY, blockZ);
        int headFluid = headChunk.getFluidId(blockX, blockY + 1, blockZ);
        int groundFluid = groundChunk.getFluidId(blockX, blockY - 1, blockZ);
        if (feetFluid != 0 || headFluid != 0) {
            return false;
        }
        int feetBlockId = feetChunk.getBlock(blockX, blockY, blockZ);
        int headBlockId = headChunk.getBlock(blockX, blockY + 1, blockZ);
        int groundBlockId = groundChunk.getBlock(blockX, blockY - 1, blockZ);
        if (isSolidBlock(feetBlockId, feetFluid) || isSolidBlock(headBlockId, headFluid)) {
            return false;
        }
        return isSolidBlock(groundBlockId, groundFluid);
    }

    @Nullable
    private WorldChunk resolveWorldChunk(@Nonnull ChunkStore chunkStore,
                                         @Nonnull Store<ChunkStore> chunkStoreStore,
                                         int blockX,
                                         int blockZ,
                                         @Nonnull Map<Long, WorldChunk> chunkCache) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        if (chunkCache.containsKey(chunkIndex)) {
            return chunkCache.get(chunkIndex);
        }
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
        if (chunkRef == null || !chunkRef.isValid()) {
            chunkCache.put(chunkIndex, null);
            return null;
        }
        WorldChunk worldChunk = chunkStoreStore.getComponent(chunkRef, WorldChunk.getComponentType());
        chunkCache.put(chunkIndex, worldChunk);
        return worldChunk;
    }

    @Nonnull
    private Vector3d toStandPosition(int blockX, int blockY, int blockZ) {
        return new Vector3d(blockX + 0.5, blockY + SAFE_STAND_HEIGHT_OFFSET, blockZ + 0.5);
    }

    private boolean isSolidBlock(int blockId, int fluidId) {
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null || blockType == BlockType.UNKNOWN) {
            return false;
        }
        return WorldUtil.isSolidOnlyBlock(blockType, fluidId);
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
