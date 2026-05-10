package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates owner-based capture/spawn access rules for spawner items.
 */
final class SpawnerOwnershipPolicyService {
    boolean isCaptureAllowed(@Nullable UUID playerUuid, @Nullable UUID ownerUuid, @Nullable ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        boolean requireOwner = resolveCaptureRequireOwner(config);
        return isOwnershipAllowed(requireOwner, config.isCaptureOwnerRestricted(), playerUuid, ownerUuid);
    }

    boolean isSpawnAllowed(@Nullable UUID playerUuid, @Nullable UUID ownerUuid, @Nullable ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        boolean requireOwner = resolveSpawnRequireOwner(config);
        return isOwnershipAllowed(requireOwner, config.isSpawnOwnerRestricted(), playerUuid, ownerUuid);
    }

    private boolean resolveCaptureRequireOwner(@Nonnull ItemFeatureConfig config) {
        return config.getCaptureRequireOwnerOverride() != null
                ? config.getCaptureRequireOwnerOverride()
                : resolveCaptureRequireOwnerDefault(TwGlobalConfig.resolveActive());
    }

    private boolean resolveSpawnRequireOwner(@Nonnull ItemFeatureConfig config) {
        return config.getSpawnRequireOwnerOverride() != null
                ? config.getSpawnRequireOwnerOverride()
                : resolveSpawnRequireOwnerDefault(TwGlobalConfig.resolveActive());
    }

    static boolean resolveCaptureRequireOwnerDefault(@Nullable TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.captureRequiresOwner(resolved.isOwnershipCaptureRequiresOwner());
    }

    static boolean resolveSpawnRequireOwnerDefault(@Nullable TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.spawnRequiresOwner(resolved.isOwnershipSpawnRequiresOwner());
    }

    static boolean isOwnerRequirementSatisfied(boolean requireOwner,
                                               @Nullable UUID playerUuid,
                                               @Nullable UUID ownerUuid) {
        if (!requireOwner) {
            return true;
        }
        if (ownerUuid == null) {
            return true;
        }
        return playerUuid != null && ownerUuid.equals(playerUuid);
    }

    static boolean isOwnershipAllowed(boolean requireOwner,
                                      boolean ownerRestricted,
                                      @Nullable UUID playerUuid,
                                      @Nullable UUID ownerUuid) {
        if (!isOwnerRequirementSatisfied(requireOwner, playerUuid, ownerUuid)) {
            return false;
        }
        if (!ownerRestricted) {
            return true;
        }
        if (ownerUuid == null) {
            return true;
        }
        return playerUuid != null && ownerUuid != null && ownerUuid.equals(playerUuid);
    }
}
