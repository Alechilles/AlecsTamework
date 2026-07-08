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

        assertTrue(source.contains("createAvatarFlightModel(modelAsset, scale, config)"),
                "transformed avatar models should be enriched before the ModelComponent is applied");
        assertTrue(source.contains("Model.createScaledModel(modelAsset, scale)"),
                "enrichment should start from the normal scaled model so hitbox, camera, attachments, and texture stay intact");
        assertTrue(source.contains("new Model("),
                "runtime enrichment needs a fresh Model instance so the packet cache includes injected animation sets");
        assertTrue(source.contains("baseModel.getAnimationSetMap()"),
                "existing model animation sets must be carried forward");
        assertTrue(source.contains("animations.putIfAbsent(standardId"),
                "modder-provided animation set ids should win over the generic Tamework defaults");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.standardDefinitionsFor(animation)"),
                "runtime injection should use the shared standard pose catalog");
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
    void avatarFlightRestoreMarksSkinNetworkOutdatedAfterSavedModelRestore() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        int savedRestore = source.indexOf("if (savedModel != null)");
        assertTrue(savedRestore >= 0);
        String savedRestoreBlock = source.substring(savedRestore, source.indexOf("return true;", savedRestore));
        assertTrue(savedRestoreBlock.contains("skin.setNetworkOutdated()"),
                "dismount restore should force a skin refresh so armor visibility toggles do not leave stale clothing");
    }

    @Test
    void avatarFlightInjectsOnlyStandardTameworkPoseIds() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("addStandardPoseAnimation(animations, definition.id(), definition.path())"));
        assertFalse(source.contains("if (!standardId.equals(configuredId))"),
                "the model service should delegate standard-root filtering to the catalog");
    }

    @Test
    void avatarFlightCatalogIncludesExpandedPitchRollBreakpoints() throws Exception {
        String source = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "avatarflight",
                "AvatarFlightPoseAnimationCatalog.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("private static final int[] PITCH_LEVELS = {15, 20, 30, 40}"));
        assertTrue(source.contains("private static final int[] ROLL_LEVELS = {10, 20, 30}"));
        assertTrue(source.contains("return (up ? \"TameworkPitchUp\" : \"TameworkPitchDown\") + degrees"));
        assertTrue(source.contains("return (left ? \"TameworkBankLeft\" : \"TameworkBankRight\") + degrees"));
        assertTrue(source.contains("return \"Tamework\""),
                "custom animation ids should remain custom and require the model asset to define them");
    }
}
