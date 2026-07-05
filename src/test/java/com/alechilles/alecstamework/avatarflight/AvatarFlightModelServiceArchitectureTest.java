package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightModelServiceArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightModelService.java"
    );

    @Test
    void avatarFlightModelSwapInjectsPoseAnimationSetsIntoRuntimeModel() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("createAvatarFlightModel(modelAsset, scale, config.getAnimation())"),
                "transformed avatar models should be enriched before the ModelComponent is applied");
        assertTrue(source.contains("Model baseModel = Model.createScaledModel(modelAsset, scale)"),
                "enrichment should start from the normal scaled model so hitbox, camera, attachments, and texture stay intact");
        assertTrue(source.contains("new Model("),
                "runtime enrichment needs a fresh Model instance so the packet cache includes injected animation sets");
        assertTrue(source.contains("baseModel.getAnimationSetMap()"),
                "existing model animation sets must be carried forward");
        assertTrue(source.contains("animations.putIfAbsent(standardId"),
                "modder-provided animation set ids should win over the generic Tamework defaults");
    }

    @Test
    void avatarFlightModelSwapDoesNotMutateGlobalModelAssets() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("modelAsset.getAnimationSetMap().put"),
                "global ModelAsset animation maps should not be mutated for per-player avatar flight poses");
        assertTrue(source.contains("new LinkedHashMap<>(baseModel.getAnimationSetMap())"),
                "pose sets should be added to a runtime copy rather than the shared asset map");
    }

    @Test
    void avatarFlightInjectsOnlyStandardTameworkPoseIds() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("\"TameworkPitchUp\""));
        assertTrue(source.contains("\"TameworkPitchDown\""));
        assertTrue(source.contains("\"TameworkBankLeft\""));
        assertTrue(source.contains("\"TameworkBankRight\""));
        assertTrue(source.contains("\"TameworkPitchUpBankLeft\""));
        assertTrue(source.contains("\"TameworkPitchUpBankRight\""));
        assertTrue(source.contains("\"TameworkPitchDownBankLeft\""));
        assertTrue(source.contains("\"TameworkPitchDownBankRight\""));
        assertTrue(source.contains("if (!standardId.equals(configuredId))"),
                "custom animation ids should remain custom and require the model asset to define them");
    }
}
