package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import javax.annotation.Nullable;

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

    private final BreedingOffspringRoleResolver roleResolver;

    BreedingOffspringSpawnService(BreedingOffspringRoleResolver roleResolver) {
        this.roleResolver = roleResolver;
    }

    @Nullable
    ResolvedSpawnRole resolveSpawnRole(@Nullable String baseRoleId,
                                       @Nullable TwBreedingConfig breedingConfig,
                                       int parentARoleIndex,
                                       int parentBRoleIndex,
                                       @Nullable NPCPlugin npcPlugin) {
        ResolvedSpawnRole fromBaseRole = resolveSpawnRoleFromBaseRoleId(baseRoleId, breedingConfig, npcPlugin);
        if (fromBaseRole != null) {
            return fromBaseRole;
        }
        String parentARoleId = resolveRoleIdFromIndex(parentARoleIndex, npcPlugin);
        ResolvedSpawnRole fromParentAIndex = resolveSpawnRoleFromBaseRoleId(parentARoleId, breedingConfig, npcPlugin);
        if (fromParentAIndex != null) {
            return fromParentAIndex;
        }
        String parentBRoleId = resolveRoleIdFromIndex(parentBRoleIndex, npcPlugin);
        return resolveSpawnRoleFromBaseRoleId(parentBRoleId, breedingConfig, npcPlugin);
    }

    @Nullable
    Pair<Ref<EntityStore>, NPCEntity> spawnWithFallback(@Nullable NPCPlugin npcPlugin,
                                                         @Nullable Store<EntityStore> store,
                                                         int roleIndex,
                                                         @Nullable Vector3d spawnPosition,
                                                         @Nullable Vector3f spawnRotation) {
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
                Pair<Ref<EntityStore>, NPCEntity> spawned = trySpawnAtPosition(
                        npcPlugin,
                        store,
                        roleIndex,
                        candidate,
                        spawnRotation
                );
                if (spawned != null) {
                    return spawned;
                }
            }
        }
        return null;
    }

    @Nullable
    private ResolvedSpawnRole resolveSpawnRoleFromBaseRoleId(@Nullable String baseRoleId,
                                                             @Nullable TwBreedingConfig breedingConfig,
                                                             @Nullable NPCPlugin npcPlugin) {
        if (baseRoleId == null || baseRoleId.isBlank() || npcPlugin == null) {
            return null;
        }
        BreedingOffspringRoleResolver.OffspringRoleSelection selection =
                roleResolver.selectOffspringRole(baseRoleId, breedingConfig, npcPlugin);
        if (selection == null || selection.roleId() == null || selection.roleId().isBlank()) {
            return null;
        }
        int roleIndex = npcPlugin.getIndex(selection.roleId());
        if (roleIndex < 0) {
            return null;
        }
        return new ResolvedSpawnRole(selection.roleId(), roleIndex, selection.lifecycleFamily());
    }

    @Nullable
    private Pair<Ref<EntityStore>, NPCEntity> trySpawnAtPosition(@Nullable NPCPlugin npcPlugin,
                                                                  @Nullable Store<EntityStore> store,
                                                                  int roleIndex,
                                                                  @Nullable Vector3d candidate,
                                                                  @Nullable Vector3f spawnRotation) {
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

    record ResolvedSpawnRole(String roleId,
                             int roleIndex,
                             @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
    }
}
