package com.alechilles.alecstamework.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TameworkDebugPlayerModelCommandArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "commands",
            "TameworkDebugPlayerModelCommand.java"
    );

    @Test
    void unsafeScaleIsNotClampedToModelAssetRange() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("getMinScale()"),
                "debugplayermodel unsafe should not clamp to the model asset minimum scale");
        assertFalse(source.contains("getMaxScale()"),
                "debugplayermodel unsafe should not clamp to the model asset maximum scale");
        assertTrue(source.contains("Float.isFinite(scale) && scale > 0.0f"),
                "debugplayermodel unsafe should still reject invalid scales before creating a model");
        assertTrue(source.contains("Model.createScaledModel(modelAsset, scale)"),
                "debugplayermodel unsafe should pass the requested positive scale through to Hytale");
    }
}
