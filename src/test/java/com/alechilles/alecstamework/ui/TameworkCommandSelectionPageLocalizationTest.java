package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCommandSelectionPageLocalizationTest {

    @Test
    void commandOptionLabelsResolveConfigLanguageKeys() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("buildOptions(config, commandOptionPredicate, resolveLanguage())"));
        assertTrue(content.contains("LocalizedText.resolveConfigValue(language"));
        assertFalse(content.contains("return entry.getDisplayName();"));
    }
}
