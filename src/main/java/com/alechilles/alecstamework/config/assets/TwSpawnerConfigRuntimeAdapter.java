package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import javax.annotation.Nullable;

/** Converts the spawner asset schema into immutable runtime and public API projections. */
final class TwSpawnerConfigRuntimeAdapter {
    private TwSpawnerConfigRuntimeAdapter() {
    }

    static ItemFeatureConfig.CaptureItemMechanics captureMechanics(
            @Nullable TwSpawnerConfig.CaptureSettings settings) {
        TwSpawnerConfig.CaptureSettings capture = settings == null
                ? new TwSpawnerConfig.CaptureSettings() : settings;
        return new ItemFeatureConfig.CaptureItemMechanics(
                capture.chanceMode,
                capture.power,
                capture.baseChance,
                capture.chancePerPower,
                capture.minimumChance,
                capture.maximumChance,
                capture.failureCooldownMs,
                capture.failureParticleSystem,
                capture.failureSoundEvent);
    }

    static ItemFeatureConfig.VesselItemMechanics vesselMechanics(
            @Nullable TwSpawnerVesselSettings settings,
            @Nullable String emptyItemId,
            @Nullable String filledItemId) {
        return (settings == null ? new TwSpawnerVesselSettings() : settings)
                .toRuntimeMechanics(emptyItemId, filledItemId);
    }

    static SpawnerVesselConfigView vesselView(TwSpawnerConfig config, long revision) {
        requireConfigId(config);
        return (config.getVessel() == null ? new TwSpawnerVesselSettings() : config.getVessel())
                .toView(config.getId(), revision, config.getEmptyItemId(), config.getFilledItemId());
    }

    static boolean matchesVesselItemId(TwSpawnerConfig config, @Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        ItemFeatureConfig.VesselItemMechanics mechanics = vesselMechanics(
                config.getVessel(), config.getEmptyItemId(), config.getFilledItemId());
        return itemId.equals(mechanics.emptyItemId())
                || itemId.equals(mechanics.storedItemId())
                || itemId.equals(mechanics.activeItemId())
                || itemId.equals(mechanics.deadItemId())
                || itemId.equals(mechanics.lostItemId())
                || itemId.equals(mechanics.unavailableItemId());
    }

    static SpawnerCaptureMechanicsView captureView(TwSpawnerConfig config, long revision) {
        ItemFeatureConfig.CaptureItemMechanics mechanics = captureMechanics(config.captureSettings());
        if (config.getId() == null || config.getId().isBlank()
                || config.getEmptyItemId() == null || config.getEmptyItemId().isBlank()) {
            throw new IllegalArgumentException(
                    "Spawner config ID and EmptyItemId are required for capture mechanics.");
        }
        return new SpawnerCaptureMechanicsView(
                config.getId(), revision, config.getEmptyItemId(), mechanics.chanceMode(),
                mechanics.power(), mechanics.baseChance(), mechanics.chancePerPower(),
                mechanics.minimumChance(), mechanics.maximumChance(), mechanics.failureCooldownMs(),
                mechanics.failureParticleSystem(), mechanics.failureSoundEvent());
    }

    private static void requireConfigId(TwSpawnerConfig config) {
        if (config.getId() == null || config.getId().isBlank()) {
            throw new IllegalArgumentException(
                    "Spawner config ID is required for vessel mechanics.");
        }
    }
}
