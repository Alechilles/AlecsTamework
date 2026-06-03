package com.alechilles.alecstamework.assets.patches;

import java.util.Collection;
import java.util.LinkedHashSet;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.AssetPack;

/**
 * Classifies generated optional asset patch outputs that still require explicit restart reporting.
 */
public final class AssetPatchReloadCoordinator {
    public AssetPatchReloadCoordinator() {
    }

    public void reloadPublishedTargets(@Nonnull AssetPack generatedPack,
                                       @Nonnull Collection<String> affectedTargets,
                                       @Nonnull AssetPatchStatus status) {
        LinkedHashSet<String> normalizedTargets = new LinkedHashSet<>();
        for (String target : affectedTargets) {
            normalizedTargets.add(AssetPatchDefinition.normalizeAssetPath(target));
        }

        for (String target : normalizedTargets) {
            AssetPatchTargetClassification classification = AssetPatchTargetClassifier.classify(target);
            switch (classification.reloadMode()) {
                case NPC_BUILDERS -> {
                    // Hytale's NPC asset watcher handles generated role files asynchronously.
                }
                case HYTALE_ASSET_STORE, TAMEWORK_CONFIG, RESTART_REQUIRED ->
                        status.addRestartRequiredTarget(target);
            }
        }
    }
}
