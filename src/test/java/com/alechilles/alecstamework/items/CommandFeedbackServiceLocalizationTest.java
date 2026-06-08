package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFeedbackServiceLocalizationTest {

    @Test
    void configuredFeedbackTextUsesSharedConfigValueLocalization() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/items/CommandFeedbackService.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolveConfigValue(language"));
        assertFalse(content.contains("looksLikeLanguageKey"));
    }

    @Test
    void commandItemFeatureResolvesCommandDisplayNamesAsConfigLanguageKeys() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolveConfigValue(language, command.getDisplayName(), fallback)"));
    }
}
