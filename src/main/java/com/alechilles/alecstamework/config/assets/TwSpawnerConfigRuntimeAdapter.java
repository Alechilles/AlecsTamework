package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
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

}
