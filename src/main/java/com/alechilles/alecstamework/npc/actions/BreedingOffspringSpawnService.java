package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.BreedingChildProjectionMarker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.Nonnull;

/**
 * Resolves offspring role IDs and performs resilient spawn placement attempts.
 */
final class BreedingOffspringSpawnService {
    private static final double[][] SPAWN_POSITION_OFFSETS = new double[][] {
            { 1.35, 0.00 },
            { -1.35, 0.00 },
            { 0.00, 1.35 },
            { 0.00, -1.35 },
            { 0.95, 0.95 },
            { -0.95, 0.95 },
            { 0.95, -0.95 },
            { -0.95, -0.95 },
            { 0.60, 0.00 },
            { -0.60, 0.00 },
            { 0.00, 0.60 },
            { 0.00, -0.60 },
            { 0.00, 0.00 }
    };
    private static final double[] SPAWN_VERTICAL_OFFSETS = new double[] { 0.35, 0.75, 1.15, 0.00 };
    private static final int SAFE_VERTICAL_SCAN_STEPS = 4;
    private static final double SAFE_STAND_HEIGHT_OFFSET = 0.05;

    private final BreedingOffspringRoleResolver roleResolver;

    BreedingOffspringSpawnService(BreedingOffspringRoleResolver roleResolver) {
        this.roleResolver = roleResolver;
    }

    @Nullable
    BreedingResolvedSpawnRole resolveSpawnRole(@Nullable String baseRoleId,
                                       @Nullable TwBreedingConfig breedingConfig,
                                       int parentARoleIndex,
                                       int parentBRoleIndex,
                                       @Nullable NPCPlugin npcPlugin) {
        return resolveSpawnRole(
                baseRoleId,
                null,
                breedingConfig,
                parentARoleIndex,
                parentBRoleIndex,
                npcPlugin,
                Math.random(),
                Math.random()
        );
    }

    @Nullable
    BreedingResolvedSpawnRole resolveSpawnRole(@Nullable String baseRoleId,
                                       @Nullable TwBreedingConfig breedingConfig,
                                       int parentARoleIndex,
                                       int parentBRoleIndex,
                                       @Nullable NPCPlugin npcPlugin,
                                       double adultRoleRoll) {
        return resolveSpawnRole(
                baseRoleId,
                null,
                breedingConfig,
                parentARoleIndex,
                parentBRoleIndex,
                npcPlugin,
                adultRoleRoll,
                Math.random()
        );
    }

    @Nullable
    BreedingResolvedSpawnRole resolveSpawnRole(@Nullable String baseRoleId,
                                       @Nullable TwBreedingConfig breedingConfig,
                                       int parentARoleIndex,
                                       int parentBRoleIndex,
                                       @Nullable NPCPlugin npcPlugin,
                                       double adultRoleRoll,
                                       double genderRoll) {
        return resolveSpawnRole(
                baseRoleId,
                null,
                breedingConfig,
                parentARoleIndex,
                parentBRoleIndex,
                npcPlugin,
                adultRoleRoll,
                genderRoll
        );
    }

    @Nullable
    BreedingResolvedSpawnRole resolveSpawnRole(@Nullable String parentARoleId,
                                       @Nullable String parentBRoleId,
                                       @Nullable TwBreedingConfig breedingConfig,
                                       int parentARoleIndex,
                                       int parentBRoleIndex,
                                       @Nullable NPCPlugin npcPlugin,
                                       double adultRoleRoll,
                                       double genderRoll) {
        if (npcPlugin == null) {
            return null;
        }
        String resolvedParentARoleId = firstNonBlank(parentARoleId, resolveRoleIdFromIndex(parentARoleIndex, npcPlugin));
        String resolvedParentBRoleId = firstNonBlank(parentBRoleId, resolveRoleIdFromIndex(parentBRoleIndex, npcPlugin));
        BreedingResolvedSpawnRole fromParentRoles = resolveSpawnRoleFromParentRoleIds(
                resolvedParentARoleId,
                resolvedParentBRoleId,
                breedingConfig,
                npcPlugin,
                adultRoleRoll,
                genderRoll
        );
        if (fromParentRoles != null) {
            return fromParentRoles;
        }
        return resolveSpawnRoleFromParentRoleIds(
                resolvedParentBRoleId,
                resolvedParentARoleId,
                breedingConfig,
                npcPlugin,
                adultRoleRoll,
                genderRoll
        );
    }

    @Nullable
    Pair<Ref<EntityStore>, NPCEntity> spawnWithFallback(@Nullable NPCPlugin npcPlugin,
                                                         @Nullable Store<EntityStore> store,
                                                         int roleIndex,
                                                         @Nullable Vector3d spawnPosition,
                                                         @Nullable Rotation3f spawnRotation) {
        if (npcPlugin == null || store == null || roleIndex < 0 || spawnPosition == null || spawnRotation == null) {
            return null;
        }
        for (double yOffset : SPAWN_VERTICAL_OFFSETS) {
            for (double[] offset : SPAWN_POSITION_OFFSETS) {
                Vector3d candidate = new Vector3d(
                        spawnPosition.x + offset[0],
                        spawnPosition.y + yOffset,
                        spawnPosition.z + offset[1]
                );
                Vector3d safeCandidate = resolveSafeSpawnCandidate(candidate, store);
                if (safeCandidate == null) {
                    continue;
                }
                Pair<Ref<EntityStore>, NPCEntity> spawned = trySpawnAtPosition(
                        npcPlugin,
                        store,
                        roleIndex,
                        safeCandidate,
                        spawnRotation
                );
                if (spawned != null) {
                    return spawned;
                }
            }
        }
        return null;
    }

    /** Spawns only after a prepared population unit initializes the pre-add holder. */
    @Nonnull
    BreedingPreparedSpawnResult spawnPreparedWithFallback(
            @Nullable NPCPlugin npcPlugin,
            @Nullable Store<EntityStore> store,
            int roleIndex,
            @Nullable Vector3d spawnPosition,
            @Nullable Rotation3f spawnRotation,
            @Nullable ClaimChunkCoordinate requiredDestination,
            @Nonnull UUID requiredNpcUuid,
            @Nonnull TameworkProjectionIdentityComponent expectedProjectionMarker,
            @Nonnull BreedingSpawnHolderPreparation holderPreparation
    ) {
        if (npcPlugin == null || store == null || roleIndex < 0
                || spawnPosition == null || spawnRotation == null) {
            return BreedingPreparedSpawnResult.failed("breeding-spawn-context-invalid");
        }
        BreedingPreparedSpawnResult preflight = recoverSpawn(
                store, requiredNpcUuid, expectedProjectionMarker,
                "breeding-spawn-preflight-absent"
        );
        if (preflight.spawned() != null || preflight.outcomeAmbiguous()) {
            return preflight;
        }
        for (double yOffset : SPAWN_VERTICAL_OFFSETS) {
            for (double[] offset : SPAWN_POSITION_OFFSETS) {
                Vector3d candidate = new Vector3d(
                        spawnPosition.x + offset[0],
                        spawnPosition.y + yOffset,
                        spawnPosition.z + offset[1]
                );
                Vector3d safeCandidate = resolveSafeSpawnCandidate(candidate, store);
                if (safeCandidate == null || !matchesDestination(safeCandidate, requiredDestination)) {
                    continue;
                }
                BreedingPreparedSpawnResult attempt = tryPreparedSpawn(
                        npcPlugin,
                        store,
                        roleIndex,
                        safeCandidate,
                        spawnRotation,
                        requiredNpcUuid,
                        expectedProjectionMarker,
                        holderPreparation
                );
                if (terminalPreparedAttempt(attempt)) {
                    return attempt;
                }
            }
        }
        return BreedingPreparedSpawnResult.failed("breeding-spawn-placement-failed");
    }

    /** An unknown add outcome must never fall through to another placement candidate. */
    static boolean terminalPreparedAttempt(@Nonnull BreedingPreparedSpawnResult attempt) {
        return attempt.spawned() != null
                || attempt.preparationFailed()
                || attempt.outcomeAmbiguous();
    }

    private static boolean matchesDestination(@Nonnull Vector3d candidate,
                                              @Nullable ClaimChunkCoordinate requiredDestination) {
        if (requiredDestination == null) {
            return true;
        }
        int blockX = (int) Math.floor(candidate.x);
        int blockZ = (int) Math.floor(candidate.z);
        return ChunkUtil.chunkCoordinate(blockX) == requiredDestination.chunkX()
                && ChunkUtil.chunkCoordinate(blockZ) == requiredDestination.chunkZ();
    }

    @Nonnull
    private BreedingPreparedSpawnResult tryPreparedSpawn(
            @Nonnull NPCPlugin npcPlugin,
            @Nonnull Store<EntityStore> store,
            int roleIndex,
            @Nonnull Vector3d candidate,
            @Nonnull Rotation3f spawnRotation,
            @Nonnull UUID requiredNpcUuid,
            @Nonnull TameworkProjectionIdentityComponent expectedProjectionMarker,
            @Nonnull BreedingSpawnHolderPreparation holderPreparation
    ) {
        PreparationFailure failure = new PreparationFailure();
        Pair<Ref<EntityStore>, NPCEntity> spawned = null;
        try {
            spawned = npcPlugin.spawnEntity(
                    store,
                    roleIndex,
                    candidate,
                    spawnRotation,
                    null,
                    (npc, holder, spawnStore) -> {
                        String reason = holderPreparation.prepare(npc, holder, spawnStore);
                        if (reason != null) {
                            failure.reason = reason;
                            throw new SpawnHolderPreparationException(reason);
                        }
                    },
                    null
            );
            if (spawned == null || spawned.first() == null || spawned.second() == null) {
                despawnQuietly(spawned);
                return recoverSpawn(
                        store, requiredNpcUuid, expectedProjectionMarker,
                        "breeding-spawn-store-rejected"
                );
            }
            UUIDComponent uuid = store.getComponent(
                    spawned.first(), UUIDComponent.getComponentType()
            );
            TameworkProjectionIdentityComponent marker = store.getComponent(
                    spawned.first(), TameworkProjectionIdentityComponent.getComponentType()
            );
            if (!matchesPreparedIdentity(
                    requiredNpcUuid,
                    expectedProjectionMarker,
                    uuid,
                    spawned.second(),
                    marker
            )) {
                despawnQuietly(spawned);
                return BreedingPreparedSpawnResult.ambiguous(
                        "breeding-spawn-identity-mismatch"
                );
            }
            return new BreedingPreparedSpawnResult(spawned, null, false, false);
        } catch (SpawnHolderPreparationException exception) {
            return new BreedingPreparedSpawnResult(null, failure.reason, true, false);
        } catch (RuntimeException | LinkageError exception) {
            return recoverSpawn(
                    store, requiredNpcUuid, expectedProjectionMarker,
                    "breeding-spawn-exception"
            );
        }
    }

    @Nonnull
    private static BreedingPreparedSpawnResult recoverSpawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID requiredNpcUuid,
            @Nonnull TameworkProjectionIdentityComponent expectedProjectionMarker,
            @Nonnull String absentReason
    ) {
        BreedingChildProjectionProbe.Result probe = BreedingChildProjectionProbe.probe(
                store, requiredNpcUuid, expectedProjectionMarker
        );
        if (probe.present()) {
            try {
                UUIDComponent uuid = store.getComponent(
                        probe.ref(), UUIDComponent.getComponentType()
                );
                TameworkProjectionIdentityComponent marker = store.getComponent(
                        probe.ref(), TameworkProjectionIdentityComponent.getComponentType()
                );
                if (matchesPreparedIdentity(
                        requiredNpcUuid,
                        expectedProjectionMarker,
                        uuid,
                        probe.npc(),
                        marker
                )) {
                    return new BreedingPreparedSpawnResult(
                            Pair.of(probe.ref(), probe.npc()), null, false, false
                    );
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Identity must remain fully observable before an applying unit can commit.
            }
            return BreedingPreparedSpawnResult.ambiguous(
                    absentReason + "-identity-mismatch"
            );
        }
        return probe.absenceProven()
                ? BreedingPreparedSpawnResult.failed(absentReason)
                : BreedingPreparedSpawnResult.ambiguous(absentReason + "-outcome-ambiguous");
    }

    /** Requires every live identity representation to agree with the reserved child. */
    static boolean matchesPreparedIdentity(
            @Nonnull UUID requiredNpcUuid,
            @Nonnull TameworkProjectionIdentityComponent expectedProjectionMarker,
            @Nullable UUIDComponent uuid,
            @Nullable NPCEntity npc,
            @Nullable TameworkProjectionIdentityComponent marker) {
        return uuid != null
                && requiredNpcUuid.equals(uuid.getUuid())
                && npc != null
                && requiredNpcUuid.equals(npc.getUuid())
                && BreedingChildProjectionMarker.matches(marker, expectedProjectionMarker);
    }

    private static void despawnQuietly(@Nullable Pair<Ref<EntityStore>, NPCEntity> spawned) {
        try {
            if (spawned != null && spawned.second() != null) {
                spawned.second().setToDespawn();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Caller cancels the applying population unit; reconciliation covers despawn ambiguity.
        }
    }

    @Nullable
    private BreedingResolvedSpawnRole resolveSpawnRoleFromParentRoleIds(@Nullable String parentARoleId,
                                                                @Nullable String parentBRoleId,
                                                                @Nullable TwBreedingConfig breedingConfig,
                                                                @Nullable NPCPlugin npcPlugin,
                                                                double adultRoleRoll,
                                                                double genderRoll) {
        boolean hasParentA = parentARoleId != null && !parentARoleId.isBlank();
        boolean hasParentB = parentBRoleId != null && !parentBRoleId.isBlank();
        if ((!hasParentA && !hasParentB) || npcPlugin == null) {
            return null;
        }
        BreedingOffspringRoleResolver.OffspringRoleSelection selection =
                roleResolver.selectOffspringRole(
                        parentARoleId,
                        parentBRoleId,
                        breedingConfig,
                        npcPlugin,
                        Math.random(),
                        Math.random(),
                        adultRoleRoll,
                        genderRoll
                );
        if (selection == null || selection.roleId() == null || selection.roleId().isBlank()) {
            return null;
        }
        int roleIndex = npcPlugin.getIndex(selection.roleId());
        if (roleIndex < 0) {
            return null;
        }
        return new BreedingResolvedSpawnRole(
                selection.roleId(),
                roleIndex,
                selection.adultRoleId(),
                selection.gender(),
                selection.lifecycleFamily()
        );
    }

    @Nullable
    private Pair<Ref<EntityStore>, NPCEntity> trySpawnAtPosition(@Nullable NPCPlugin npcPlugin,
                                                                  @Nullable Store<EntityStore> store,
                                                                  int roleIndex,
                                                                  @Nullable Vector3d candidate,
                                                                  @Nullable Rotation3f spawnRotation) {
        if (npcPlugin == null || store == null || roleIndex < 0 || candidate == null || spawnRotation == null) {
            return null;
        }
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                store,
                roleIndex,
                candidate,
                spawnRotation,
                null,
                null
        );
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return null;
        }
        return spawned;
    }

    @Nullable
    private Vector3d resolveSafeSpawnCandidate(@Nullable Vector3d candidate,
                                               @Nullable Store<EntityStore> store) {
        if (candidate == null || store == null) {
            return candidate;
        }
        World world = resolveWorld(store);
        if (world == null || world.getChunkStore() == null) {
            return candidate;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore != null ? chunkStore.getStore() : null;
        if (chunkStoreStore == null) {
            return candidate;
        }

        int blockX = (int) Math.floor(candidate.x);
        int blockY = (int) Math.floor(candidate.y);
        int blockZ = (int) Math.floor(candidate.z);
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int step = 0; step <= SAFE_VERTICAL_SCAN_STEPS; step++) {
            int upY = blockY + step;
            if (canStandAt(chunkStore, chunkStoreStore, blockX, upY, blockZ, chunkCache)) {
                return new Vector3d(candidate.x, upY + SAFE_STAND_HEIGHT_OFFSET, candidate.z);
            }
            if (step == 0) {
                continue;
            }
            int downY = blockY - step;
            if (canStandAt(chunkStore, chunkStoreStore, blockX, downY, blockZ, chunkCache)) {
                return new Vector3d(candidate.x, downY + SAFE_STAND_HEIGHT_OFFSET, candidate.z);
            }
        }
        return null;
    }

    @Nullable
    private World resolveWorld(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        return store.getExternalData().getWorld();
    }

    private boolean canStandAt(ChunkStore chunkStore,
                               Store<ChunkStore> chunkStoreStore,
                               int blockX,
                               int blockY,
                               int blockZ,
                               Map<Long, WorldChunk> chunkCache) {
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
    private WorldChunk resolveWorldChunk(ChunkStore chunkStore,
                                         Store<ChunkStore> chunkStoreStore,
                                         int blockX,
                                         int blockZ,
                                         Map<Long, WorldChunk> chunkCache) {
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

    @Nullable
    private String resolveRoleIdFromIndex(int roleIndex, @Nullable NPCPlugin npcPlugin) {
        if (npcPlugin == null || roleIndex < 0) {
            return null;
        }
        String roleId = npcPlugin.getName(roleIndex);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return roleId;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static final class PreparationFailure {
        private String reason;
    }

    private static final class SpawnHolderPreparationException extends RuntimeException {
        private SpawnHolderPreparationException(String message) {
            super(message);
        }
    }
}
