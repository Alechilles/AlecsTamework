package com.alechilles.alecstamework.assets.patches;

import javax.annotation.Nonnull;

/**
 * Classifies optional patch targets conservatively so hot reload is only attempted through known safe routes.
 */
public final class AssetPatchTargetClassifier {
    private AssetPatchTargetClassifier() {
    }

    @Nonnull
    public static AssetPatchTargetClassification classify(@Nonnull String target) {
        String normalized = AssetPatchDefinition.normalizeAssetPath(target);
        if (normalized.startsWith("Server/NPC/Roles/")) {
            return new AssetPatchTargetClassification(
                    AssetPatchTargetKind.NPC_BUILDER,
                    AssetPatchReloadMode.NPC_BUILDERS
            );
        }
        if (normalized.startsWith("Server/Tamework/") && !normalized.startsWith("Server/Tamework/Patches/")) {
            return new AssetPatchTargetClassification(
                    AssetPatchTargetKind.TAMEWORK_CONFIG,
                    AssetPatchReloadMode.RESTART_REQUIRED
            );
        }
        if (normalized.startsWith("Server/") && isServerJsonLikeTarget(normalized)) {
            return new AssetPatchTargetClassification(
                    AssetPatchTargetKind.HYTALE_ASSET_STORE,
                    AssetPatchReloadMode.RESTART_REQUIRED
            );
        }
        if (normalized.startsWith("Common/")) {
            return new AssetPatchTargetClassification(
                    AssetPatchTargetKind.COMMON_ASSET,
                    AssetPatchReloadMode.RESTART_REQUIRED
            );
        }
        if (normalized.endsWith(".json")) {
            return new AssetPatchTargetClassification(
                    AssetPatchTargetKind.UNKNOWN_JSON,
                    AssetPatchReloadMode.RESTART_REQUIRED
            );
        }
        return new AssetPatchTargetClassification(
                AssetPatchTargetKind.UNKNOWN_JSON,
                AssetPatchReloadMode.RESTART_REQUIRED
        );
    }

    static boolean isServerJsonLikeTarget(@Nonnull String target) {
        return target.endsWith(".json")
                || target.endsWith(".particlesystem")
                || target.endsWith(".particlespawner");
    }
}
