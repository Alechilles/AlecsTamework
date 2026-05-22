package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import org.junit.jupiter.api.Test;

final class AssetPatchServiceTest {

    @Test
    void patchGenerationRunsAfterTameworkPackOrderingAndBeforeCommonAssetLoad() {
        assertTrue(AssetPatchService.EARLY_PATCH_GENERATION_PRIORITY > -40);
        assertTrue(AssetPatchService.EARLY_PATCH_GENERATION_PRIORITY < LoadAssetEvent.PRIORITY_LOAD_COMMON);
    }
}
