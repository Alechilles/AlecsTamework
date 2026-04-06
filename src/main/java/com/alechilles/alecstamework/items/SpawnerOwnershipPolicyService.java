package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Evaluates owner-based capture/spawn access rules for spawner items.
 */
final class SpawnerOwnershipPolicyService {
    boolean isCaptureAllowed(@Nullable UUID playerUuid, @Nullable UUID ownerUuid, @Nullable ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        boolean requireOwner = resolveCaptureRequireOwner();
        return isOwnerRequirementSatisfied(requireOwner, playerUuid, ownerUuid);
    }

    boolean isSpawnAllowed(@Nullable UUID playerUuid, @Nullable UUID ownerUuid, @Nullable ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        boolean requireOwner = resolveSpawnRequireOwner();
        return isOwnerRequirementSatisfied(requireOwner, playerUuid, ownerUuid);
    }

    private boolean resolveCaptureRequireOwner() {
        return resolveCaptureRequireOwnerDefault(TwGlobalConfig.resolveActive());
    }

    private boolean resolveSpawnRequireOwner() {
        return resolveSpawnRequireOwnerDefault(TwGlobalConfig.resolveActive());
    }

    static boolean resolveCaptureRequireOwnerDefault(@Nullable TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return resolved.isOwnershipCaptureRequiresOwner();
    }

    static boolean resolveSpawnRequireOwnerDefault(@Nullable TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return resolved.isOwnershipSpawnRequiresOwner();
    }

    static boolean isOwnerRequirementSatisfied(boolean requireOwner,
                                               @Nullable UUID playerUuid,
                                               @Nullable UUID ownerUuid) {
        if (!requireOwner) {
            return true;
        }
        return playerUuid != null && ownerUuid != null && ownerUuid.equals(playerUuid);
    }
}
