package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelProgressionLocalizationTest {

    @Test
    void progressionPresentationUsesLanguageKeysForOwnedText() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolve(language, \"tamework.ui.linkedPanel.progression.modifiersBreakdown\")"));
        assertTrue(content.contains("LocalizedText.resolveConfigValue(language"));
        assertFalse(content.contains("\"Modifiers: Total - [Level - Talents - Traits]\""));
        assertFalse(content.contains("return displayName;"));
    }

    @Test
    void nameplateBuilderTraitLabelsResolveConfigLanguageKeys() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolveConfigValue(null"));
        assertFalse(content.contains("return displayName;"));
    }
}
