package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
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
        boolean ownerRestricted = config.isCaptureOwnerRestricted();
        boolean requireOwner = resolveCaptureRequireOwner(config);
        if (ownerUuid != null) {
            if (ownerRestricted && (playerUuid == null || !ownerUuid.equals(playerUuid))) {
                return false;
            }
            return true;
        }
        return !requireOwner;
    }

    boolean isSpawnAllowed(@Nullable UUID playerUuid, @Nullable UUID ownerUuid, @Nullable ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        boolean requireOwner = resolveSpawnRequireOwner(config);
        if (ownerUuid != null) {
            if (config.isSpawnOwnerRestricted() && (playerUuid == null || !ownerUuid.equals(playerUuid))) {
                return false;
            }
            return true;
        }
        return !requireOwner;
    }

    private boolean resolveCaptureRequireOwner(ItemFeatureConfig config) {
        Boolean override = config.getCaptureRequireOwnerOverride();
        return override != null && override;
    }

    private boolean resolveSpawnRequireOwner(ItemFeatureConfig config) {
        Boolean override = config.getSpawnRequireOwnerOverride();
        return override != null && override;
    }
}
