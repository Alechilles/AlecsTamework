package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves same-type population keys and nearby counts for breeding overcrowding checks.
 *
 * <p>Families are normalized by their adult role id so baby/adolescent/adult variants count as one type.
 */
final class BreedingPopulationTypeService {
    @Nullable
    String resolveTypeKey(@Nullable String roleId, @Nullable TwBreedingConfig config) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        String canonicalRole = roleId;
        if (config != null) {
            TwBreedingConfig.RoleFamily family = config.resolveLifecycleFamilyForRole(roleId);
            if (family != null && family.getAdultRoleId() != null && !family.getAdultRoleId().isBlank()) {
                canonicalRole = family.getAdultRoleId();
            }
        }
        return canonicalRole.trim().toLowerCase(Locale.ROOT);
    }

    int countNearbyOfType(@Nullable Store<EntityStore> store,
                          @Nullable Vector3d center,
                          double radius,
                          @Nullable TwBreedingConfig config,
                          @Nullable String typeKey) {
        if (store == null || center == null || typeKey == null || typeKey.isBlank()) {
            return 0;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        if (npcType == null || transformType == null) {
            return 0;
        }
        final double radiusSq = Math.max(0.0, radius) * Math.max(0.0, radius);
        final int[] count = new int[] {0};
        store.forEachChunk(
                Query.and(npcType, transformType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        countChunk(chunk, npcType, transformType, center, radiusSq, config, typeKey, count)
        );
        return count[0];
    }

    private void countChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                            @Nonnull Vector3d center,
                            double radiusSq,
                            @Nullable TwBreedingConfig config,
                            @Nonnull String typeKey,
                            @Nonnull int[] countRef) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            NPCEntity npc = chunk.getComponent(i, npcType);
            TransformComponent transform = chunk.getComponent(i, transformType);
            if (npc == null || transform == null) {
                continue;
            }
            String roleId = resolveRoleId(npc);
            if (roleId == null || roleId.isBlank()) {
                continue;
            }
            String candidateType = resolveTypeKey(roleId, config);
            if (candidateType == null || !candidateType.equals(typeKey)) {
                continue;
            }
            Vector3d position = transform.getPosition();
            double dx = position.x - center.x;
            double dy = position.y - center.y;
            double dz = position.z - center.z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distanceSq) || distanceSq > radiusSq) {
                continue;
            }
            countRef[0]++;
        }
    }

    @Nullable
    private String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin != null) {
                String resolved = plugin.getName(roleIndex);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return null;
    }
}
