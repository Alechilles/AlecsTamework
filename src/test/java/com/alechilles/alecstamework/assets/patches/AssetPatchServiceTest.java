package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AssetPatchServiceTest {

    @Test
    void patchGenerationRunsAfterTameworkPackOrderingAndBeforeCommonAssetLoad() {
        assertTrue(AssetPatchService.EARLY_PATCH_GENERATION_PRIORITY > -40);
        assertTrue(AssetPatchService.EARLY_PATCH_GENERATION_PRIORITY < LoadAssetEvent.PRIORITY_LOAD_COMMON);
    }

    @Test
    void missingOptionalPatchTargetsAreSkippedInsteadOfFailed() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchService.java"
        ));

        assertTrue(source.contains("status.addSkipped(\"No source asset found for target \" + target + \".\");"));
    }
}
