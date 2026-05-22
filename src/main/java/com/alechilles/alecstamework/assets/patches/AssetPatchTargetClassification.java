package com.alechilles.alecstamework.assets.patches;

import javax.annotation.Nonnull;

/**
 * Classification of a target path and the safest known runtime reload route.
 */
public record AssetPatchTargetClassification(
        @Nonnull AssetPatchTargetKind kind,
        @Nonnull AssetPatchReloadMode reloadMode
) {
}
