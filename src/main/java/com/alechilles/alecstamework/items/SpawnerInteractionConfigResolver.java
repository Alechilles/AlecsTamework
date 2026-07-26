package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import javax.annotation.Nullable;

/** Applies live ownership settings to one immutable spawner interaction config. */
final class SpawnerInteractionConfigResolver {
    private SpawnerInteractionConfigResolver() {
    }

    @Nullable
    static ItemFeatureConfig resolve(@Nullable ItemFeatureConfig baseConfig,
                                     @Nullable Boolean spawnAssignsOwnerOverride) {
        if (baseConfig == null) {
            return null;
        }
        TameworkRuntimeSettings settings = TameworkRuntimeSettings.current();
        boolean spawnAssignsOwner = spawnAssignsOwnerOverride != null
                ? spawnAssignsOwnerOverride
                : settings.spawnSetsOwner();
        return ItemFeatureConfig.builder()
                .spawnerEnabled(baseConfig.isSpawnerEnabled())
                .whistleEnabled(baseConfig.isWhistleEnabled())
                .captureClearsOwner(settings.captureClearsOwner())
                .captureRequireTamed(baseConfig.isCaptureRequireTamed())
                .captureTamesTarget(baseConfig.isCaptureTamesTarget())
                .captureOwnerRestricted(baseConfig.isCaptureOwnerRestricted())
                .spawnAssignsOwner(spawnAssignsOwner)
                .spawnOwnerRestricted(baseConfig.isSpawnOwnerRestricted())
                .whistleRadius(baseConfig.getWhistleRadius())
                .spawnerRoleAllowlist(baseConfig.getSpawnerRoleAllowlist())
                .spawnerRoleDenylist(baseConfig.getSpawnerRoleDenylist())
                .spawnerRoleListMode(baseConfig.getSpawnerRoleListMode())
                .captureRequireOwnerOverride(baseConfig.getCaptureRequireOwnerOverride())
                .spawnRequireOwnerOverride(baseConfig.getSpawnRequireOwnerOverride())
                .captureParticleSystem(baseConfig.getCaptureParticleSystem())
                .spawnParticleSystem(baseConfig.getSpawnParticleSystem())
                .captureSoundEvent(baseConfig.getCaptureSoundEvent())
                .captureRequiredEffectId(baseConfig.getCaptureRequiredEffectId())
                .captureChannelAuraEffectId(baseConfig.getCaptureChannelAuraEffectId())
                .captureChannelSoundEvent(baseConfig.getCaptureChannelSoundEvent())
                .captureMaxHealthPercent(baseConfig.getCaptureMaxHealthPercent())
                .captureTamedRoleOverrides(baseConfig.getCaptureTamedRoleOverrides())
                .spawnSoundEvent(baseConfig.getSpawnSoundEvent())
                .captureCooldownMs(baseConfig.getCaptureCooldownMs())
                .spawnCooldownMs(baseConfig.getSpawnCooldownMs())
                .captureMaxDistance(baseConfig.getCaptureMaxDistance())
                .spawnMaxDistance(baseConfig.getSpawnMaxDistance())
                .spawnerFilledItemId(baseConfig.getSpawnerFilledItemId())
                .spawnerIconDefault(baseConfig.getSpawnerIconDefault())
                .spawnerIconOverrides(baseConfig.getSpawnerIconOverrides())
                .spawnerIconOverridesByRole(baseConfig.getSpawnerIconOverridesByRole())
                .spawnerIconOverrideGroups(baseConfig.getSpawnerIconOverrideGroups())
                .spawnerTooltipMode(baseConfig.getSpawnerTooltipMode())
                .captureMechanics(baseConfig.getCaptureMechanics())
                .build();
    }
}
