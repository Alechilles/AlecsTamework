package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Resolves spawner role ids and evaluates role allow/deny policy.
 */
final class SpawnerRolePolicyService {
    private final HytaleLogger logger;

    SpawnerRolePolicyService(HytaleLogger logger) {
        this.logger = logger;
    }

    @Nullable
    String resolveSpawnRoleId(@Nullable ItemStack itemStack) {
        if (itemStack != null) {
            String capturedRoleId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING);
            if (capturedRoleId != null && !capturedRoleId.isBlank()) {
                return capturedRoleId;
            }
        }
        String roleId = resolveRoleIdFromMetadata(itemStack);
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return null;
    }

    @Nullable
    String resolveRoleIdFromNpc(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            String nameKey = NPCPlugin.get().getName(roleIndex);
            if (nameKey != null && !nameKey.isBlank()) {
                return nameKey;
            }
        }
        return null;
    }

    boolean isRoleAllowed(@Nullable String roleId, @Nullable ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        ItemFeatureConfig.RoleListMode mode = config.getSpawnerRoleListMode();
        if (mode == null || mode == ItemFeatureConfig.RoleListMode.ANY) {
            return true;
        }
        if (mode == ItemFeatureConfig.RoleListMode.ALLOW) {
            if (roleId == null || roleId.isBlank()) {
                return false;
            }
            List<String> allow = config.getSpawnerRoleAllowlist();
            return allow != null && allow.contains(roleId);
        }
        if (mode == ItemFeatureConfig.RoleListMode.DENY) {
            List<String> deny = config.getSpawnerRoleDenylist();
            if (deny == null || deny.isEmpty()) {
                return true;
            }
            if (roleId == null || roleId.isBlank()) {
                return true;
            }
            return !deny.contains(roleId);
        }
        return true;
    }

    @Nullable
    private String resolveRoleIdFromMetadata(@Nullable ItemStack itemStack) {
        CapturedNPCMetadata meta = CapturedNpcMetadataCompat.readMetadata(itemStack, logger);
        return CapturedNpcMetadataCompat.resolveRoleId(meta);
    }
}
